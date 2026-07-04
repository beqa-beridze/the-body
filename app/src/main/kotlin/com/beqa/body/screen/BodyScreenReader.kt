package com.beqa.body.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.beqa.body.a11y.BodyAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * BodyScreenReader — converts the live accessibility tree into compact JSON
 * using the Set-of-Marks technique: every emitted element gets a small integer
 * label, and the label -> StableKey map lets callers re-resolve the live node
 * later (resolveNode / describeNode) without brittle paths.
 *
 * StableKey format: "pkg|shortClass|treepath|l_t_r_b"
 *   - treepath is the child-index path from the root's index in allRoots(),
 *     e.g. "0" (root itself) or "0.2.1".
 *
 * Pure tree traversal + JSON shaping; Android framework + org.json only.
 */
object BodyScreenReader {

    private const val SYSTEM_UI_PKG = "com.android.systemui"

    private val lock = Any()

    /** Bumped every time the label map is rebuilt (readScreen / findNodes). */
    private var generation: Int = 0

    /** Set-of-Marks state: integer label -> StableKey. */
    private val idMap: MutableMap<Int, String> = HashMap()

    /** Emission budget for a single traversal. */
    private class Budget(val max: Int) {
        var emitted = 0
        var truncated = false
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Read the current screen. mode == "full" returns a nested tree; anything
     * else returns a flat list of "interesting" (interactive/labelled) nodes.
     */
    fun readScreen(
        mode: String = "interactive",
        includeBounds: Boolean = false,
        includeSystemUi: Boolean = false,
        maxNodes: Int = 500
    ): JSONObject {
        val svc = BodyAccessibilityService.instance ?: return notRunning()
        val cap = maxNodes.coerceIn(1, 2000)
        synchronized(lock) {
            generation += 1
            idMap.clear()
            val roots = safe { svc.allRoots() } ?: emptyList()
            val budget = Budget(cap)
            val out = JSONObject()
            out.put("ok", true)
            out.put("app", (safe { svc.foregroundPackage } as Any?) ?: JSONObject.NULL)
            out.put("screen_hash", hashRoots(roots, includeSystemUi))
            out.put("generation", generation)
            if (mode == "full") {
                val trees = JSONArray()
                for ((idx, root) in roots.withIndex()) {
                    val t = buildFullNode(root, idx.toString(), includeBounds, includeSystemUi, budget)
                    if (t != null) trees.put(t)
                }
                val tree: Any = if (trees.length() == 1) {
                    trees.get(0)
                } else {
                    // Multiple (or zero) windows: wrap in a synthetic container.
                    JSONObject().put("role", "windows").put("children", trees)
                }
                out.put("tree", tree)
            } else {
                val elements = JSONArray()
                for ((idx, root) in roots.withIndex()) {
                    collectFlat(root, idx.toString(), includeBounds, includeSystemUi, elements, budget)
                }
                out.put("elements", elements)
            }
            out.put("node_count", budget.emitted)
            out.put("truncated", budget.truncated)
            return out
        }
    }

    /**
     * Search the current trees. All provided selectors must match (AND).
     * Matches get fresh labels under a new generation.
     */
    fun findNodes(
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        className: String? = null,
        clickableOnly: Boolean = false,
        exact: Boolean = false,
        limit: Int = 20
    ): JSONObject {
        val svc = BodyAccessibilityService.instance ?: return notRunning()
        val cap = limit.coerceIn(1, 2000)
        synchronized(lock) {
            generation += 1
            idMap.clear()
            val roots = safe { svc.allRoots() } ?: emptyList()
            val budget = Budget(cap)
            val matches = JSONArray()
            for ((idx, root) in roots.withIndex()) {
                findWalk(
                    root, idx.toString(),
                    text, contentDesc, resourceId, className,
                    clickableOnly, exact, matches, budget
                )
            }
            return JSONObject()
                .put("ok", true)
                .put("count", matches.length())
                .put("generation", generation)
                .put("matches", matches)
        }
    }

    /**
     * Full property dump for a previously labelled node. Re-locates the live
     * node via its StableKey (exact match first, treepath+class fallback).
     */
    fun describeNode(id: Int): JSONObject {
        val svc = BodyAccessibilityService.instance ?: return notRunning()
        val key: String?
        val gen: Int
        synchronized(lock) {
            key = idMap[id]
            gen = generation
        }
        if (key == null) return staleError(gen)
        val node = locateByKey(svc, key) ?: return staleError(gen)

        val o = JSONObject()
        o.put("id", id)
        o.put("role", shortClassOf(node))
        o.put("text", textOf(node) ?: "")
        o.put("desc", descOf(node) ?: "")
        o.put("viewId", ridOf(node) ?: "")
        o.put("hint", hintOf(node) ?: "")
        o.put("state", stateOf(node))
        o.put("enabled", safe { node.isEnabled } ?: true)
        o.put("selected", safe { node.isSelected } ?: false)
        o.put("checked", safe { node.isChecked } ?: false)
        o.put("bounds", boundsArrayOf(node))
        o.put("childCount", safe { node.childCount } ?: 0)
        val actions = JSONArray()
        val actionList = safe { node.actionList } ?: emptyList()
        for (a in actionList) {
            val actionId = safe { a.id } ?: continue
            actions.put(actionName(actionId))
        }
        o.put("actions", actions)

        return JSONObject().put("ok", true).put("node", o)
    }

    /**
     * Resolve a label back to a live AccessibilityNodeInfo by navigating the
     * StableKey's treepath from the recorded root index. Returns null when the
     * label is unknown or the tree changed underneath it.
     */
    fun resolveNode(id: Int): AccessibilityNodeInfo? {
        val svc = BodyAccessibilityService.instance ?: return null
        val key = synchronized(lock) { idMap[id] } ?: return null
        return navigateKey(svc, key)
    }

    /**
     * Structural hash of the current pruned interesting set. Stable under
     * re-labelling and scroll jitter (no ids, no bounds in the hash).
     */
    fun currentHash(): String {
        val svc = BodyAccessibilityService.instance ?: return "0"
        val roots = safe { svc.allRoots() } ?: return "0"
        return hashRoots(roots, includeSystemUi = false)
    }

    /** Compare the current structural hash against a previous one. */
    fun screenDiff(previousHash: String): JSONObject {
        val svc = BodyAccessibilityService.instance ?: return notRunning()
        val hash = currentHash()
        val out = JSONObject().put("ok", true)
        return if (hash == previousHash) {
            out.put("changed", false).put("hash", hash)
        } else {
            out.put("changed", true)
                .put("hash", hash)
                .put("app", (safe { svc.foregroundPackage } as Any?) ?: JSONObject.NULL)
                .put("added_labels", JSONArray())
                .put("removed_labels", JSONArray())
        }
    }

    // ------------------------------------------------------------------
    // Traversal
    // ------------------------------------------------------------------

    /**
     * Interactive-mode DFS: traverses ALL children (so treepaths stay correct)
     * but only emits interesting nodes. Pruned subtrees are not descended.
     */
    private fun collectFlat(
        node: AccessibilityNodeInfo?,
        path: String,
        includeBounds: Boolean,
        includeSystemUi: Boolean,
        out: JSONArray,
        budget: Budget
    ) {
        if (node == null || budget.truncated) return
        if (isPruned(node, includeSystemUi)) return

        if (isInteresting(node)) {
            if (budget.emitted >= budget.max) {
                budget.truncated = true
                return
            }
            budget.emitted += 1
            val label = idMap.size + 1
            idMap[label] = stableKey(node, path)
            out.put(compactNode(node, label, includeBounds))
        }

        val n = safe { node.childCount } ?: 0
        for (i in 0 until n) {
            if (budget.truncated) return
            val child = safe { node.getChild(i) } ?: continue
            collectFlat(child, "$path.$i", includeBounds, includeSystemUi, out, budget)
        }
    }

    /** Full-mode DFS: emits every non-pruned node, nested via "children". */
    private fun buildFullNode(
        node: AccessibilityNodeInfo?,
        path: String,
        includeBounds: Boolean,
        includeSystemUi: Boolean,
        budget: Budget
    ): JSONObject? {
        if (node == null || budget.truncated) return null
        if (isPruned(node, includeSystemUi)) return null
        if (budget.emitted >= budget.max) {
            budget.truncated = true
            return null
        }
        budget.emitted += 1
        val label = idMap.size + 1
        idMap[label] = stableKey(node, path)
        val obj = compactNode(node, label, includeBounds)

        val children = JSONArray()
        val n = safe { node.childCount } ?: 0
        for (i in 0 until n) {
            if (budget.truncated) break
            val child = safe { node.getChild(i) } ?: continue
            val c = buildFullNode(child, "$path.$i", includeBounds, includeSystemUi, budget)
            if (c != null) children.put(c)
        }
        if (children.length() > 0) obj.put("children", children)
        return obj
    }

    /** findNodes DFS: same pruning as interactive mode, selectors ANDed. */
    private fun findWalk(
        node: AccessibilityNodeInfo?,
        path: String,
        text: String?,
        contentDesc: String?,
        resourceId: String?,
        className: String?,
        clickableOnly: Boolean,
        exact: Boolean,
        out: JSONArray,
        budget: Budget
    ) {
        if (node == null || budget.truncated) return
        if (isPruned(node, includeSystemUi = false)) return

        if (matchesSelectors(node, text, contentDesc, resourceId, className, clickableOnly, exact)) {
            if (budget.emitted >= budget.max) {
                budget.truncated = true
                return
            }
            budget.emitted += 1
            val label = idMap.size + 1
            idMap[label] = stableKey(node, path)
            out.put(compactNode(node, label, includeBounds = true))
        }

        val n = safe { node.childCount } ?: 0
        for (i in 0 until n) {
            if (budget.truncated) return
            val child = safe { node.getChild(i) } ?: continue
            findWalk(child, "$path.$i", text, contentDesc, resourceId, className, clickableOnly, exact, out, budget)
        }
    }

    private fun matchesSelectors(
        node: AccessibilityNodeInfo,
        text: String?,
        contentDesc: String?,
        resourceId: String?,
        className: String?,
        clickableOnly: Boolean,
        exact: Boolean
    ): Boolean {
        if (clickableOnly && safe { node.isClickable } != true) return false
        if (text != null && !matchStr(textOf(node), text, exact)) return false
        if (contentDesc != null && !matchStr(descOf(node), contentDesc, exact)) return false
        if (resourceId != null) {
            val rid = ridOf(node)
            val short = rid?.substringAfterLast('/')
            if (!matchStr(rid, resourceId, exact) && !matchStr(short, resourceId, exact)) return false
        }
        if (className != null) {
            val full = safe { node.className?.toString() }
            val short = shortClassOf(node)
            if (!matchStr(full, className, exact) && !matchStr(short, className, exact)) return false
        }
        return true
    }

    private fun matchStr(value: String?, query: String, exact: Boolean): Boolean {
        if (value == null) return false
        return if (exact) value == query else value.contains(query, ignoreCase = true)
    }

    // ------------------------------------------------------------------
    // Node re-resolution
    // ------------------------------------------------------------------

    /** Exact StableKey search across all roots, then treepath fallback. */
    private fun locateByKey(svc: BodyAccessibilityService, key: String): AccessibilityNodeInfo? {
        val roots = safe { svc.allRoots() } ?: emptyList()
        for ((idx, root) in roots.withIndex()) {
            val found = searchKey(root, idx.toString(), key)
            if (found != null) return found
        }
        return navigateKey(svc, key)
    }

    private fun searchKey(node: AccessibilityNodeInfo?, path: String, key: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (stableKey(node, path) == key) return node
        val n = safe { node.childCount } ?: 0
        for (i in 0 until n) {
            val child = safe { node.getChild(i) } ?: continue
            val found = searchKey(child, "$path.$i", key)
            if (found != null) return found
        }
        return null
    }

    /** Navigate the treepath recorded in a StableKey; verify shortClass. */
    private fun navigateKey(svc: BodyAccessibilityService, key: String): AccessibilityNodeInfo? {
        val parts = key.split("|")
        if (parts.size < 4) return null
        val shortClass = parts[1]
        val idxs = parts[2].split(".").mapNotNull { it.toIntOrNull() }
        if (idxs.isEmpty()) return null
        val roots = safe { svc.allRoots() } ?: return null
        var node: AccessibilityNodeInfo = roots.getOrNull(idxs[0]) ?: return null
        for (i in 1 until idxs.size) {
            node = safe { node.getChild(idxs[i]) } ?: return null
        }
        return if (shortClassOf(node) == shortClass) node else null
    }

    // ------------------------------------------------------------------
    // Structural hash
    // ------------------------------------------------------------------

    private fun hashRoots(roots: List<AccessibilityNodeInfo>, includeSystemUi: Boolean): String {
        var agg = 0
        for (root in roots) {
            agg = agg * 31 + hashTree(root, includeSystemUi)
        }
        return Integer.toHexString(agg)
    }

    /**
     * Per-node contribution uses ONLY role|text|desc|rid|state|checked —
     * no labels, no bounds — combined with child hashes in order, so the hash
     * is stable under re-labeling and scroll jitter.
     */
    private fun hashTree(node: AccessibilityNodeInfo?, includeSystemUi: Boolean): Int {
        if (node == null) return 0
        if (isPruned(node, includeSystemUi)) return 0
        var h = if (isInteresting(node)) structuralSig(node).hashCode() else 0
        val n = safe { node.childCount } ?: 0
        for (i in 0 until n) {
            val child = safe { node.getChild(i) }
            h = h * 31 + hashTree(child, includeSystemUi)
        }
        return h
    }

    private fun structuralSig(node: AccessibilityNodeInfo): String {
        val checked = if (safe { node.isCheckable } == true) {
            (safe { node.isChecked } == true).toString()
        } else ""
        return shortClassOf(node) + "|" +
            (textOf(node) ?: "") + "|" +
            (descOf(node) ?: "") + "|" +
            (ridOf(node) ?: "") + "|" +
            stateOf(node) + "|" +
            checked
    }

    // ------------------------------------------------------------------
    // Pruning & interest
    // ------------------------------------------------------------------

    private fun isPruned(node: AccessibilityNodeInfo, includeSystemUi: Boolean): Boolean {
        if (!includeSystemUi && pkgOf(node) == SYSTEM_UI_PKG) return true
        if (safe { node.isVisibleToUser } != true) return true
        val r = boundsOf(node)
        if (r.width() <= 0 || r.height() <= 0) return true
        return false
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean {
        if (safe { node.isClickable } == true) return true
        if (safe { node.isEditable } == true) return true
        if (safe { node.isCheckable } == true) return true
        if (safe { node.isScrollable } == true) return true
        if (!textOf(node).isNullOrBlank()) return true
        if (!descOf(node).isNullOrBlank()) return true
        return false
    }

    // ------------------------------------------------------------------
    // JSON shaping
    // ------------------------------------------------------------------

    /** Compact node JSON: omits empty strings and absent/false flags. */
    private fun compactNode(node: AccessibilityNodeInfo, label: Int, includeBounds: Boolean): JSONObject {
        val o = JSONObject()
        o.put("id", label)
        val role = shortClassOf(node)
        if (role.isNotEmpty()) o.put("role", role)
        val text = textOf(node)
        if (!text.isNullOrEmpty()) o.put("text", text)
        val desc = descOf(node)
        if (!desc.isNullOrEmpty()) o.put("desc", desc)
        val rid = ridOf(node)
        if (!rid.isNullOrEmpty()) o.put("rid", rid)
        val hint = hintOf(node)
        if (!hint.isNullOrEmpty()) o.put("hint", hint)
        val state = stateOf(node)
        if (state.isNotEmpty()) o.put("state", state)
        if (safe { node.isCheckable } == true) {
            o.put("checked", safe { node.isChecked } == true)
        }
        if (includeBounds) o.put("bounds", boundsArrayOf(node))
        return o
    }

    /**
     * Packed state flags: c=clickable e=editable s=scrollable k=checkable
     * f=focusable d=disabled x=selected p=longClickable.
     */
    private fun stateOf(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (safe { node.isClickable } == true) sb.append('c')
        if (safe { node.isEditable } == true) sb.append('e')
        if (safe { node.isScrollable } == true) sb.append('s')
        if (safe { node.isCheckable } == true) sb.append('k')
        if (safe { node.isFocusable } == true) sb.append('f')
        if (safe { node.isEnabled } == false) sb.append('d')
        if (safe { node.isSelected } == true) sb.append('x')
        if (safe { node.isLongClickable } == true) sb.append('p')
        return sb.toString()
    }

    private fun boundsArrayOf(node: AccessibilityNodeInfo): JSONArray {
        val r = boundsOf(node)
        return JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom)
    }

    // ------------------------------------------------------------------
    // Guarded property reads (framework throws on stale nodes)
    // ------------------------------------------------------------------

    private fun pkgOf(node: AccessibilityNodeInfo): String =
        safe { node.packageName?.toString() } ?: ""

    private fun shortClassOf(node: AccessibilityNodeInfo): String =
        (safe { node.className?.toString() } ?: "").substringAfterLast('.')

    private fun textOf(node: AccessibilityNodeInfo): String? =
        safe { node.text?.toString() }

    private fun descOf(node: AccessibilityNodeInfo): String? =
        safe { node.contentDescription?.toString() }

    private fun ridOf(node: AccessibilityNodeInfo): String? =
        safe { node.viewIdResourceName }

    private fun hintOf(node: AccessibilityNodeInfo): String? =
        safe { node.hintText?.toString() }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (_: Throwable) {
            r.setEmpty()
        }
        return r
    }

    private fun stableKey(node: AccessibilityNodeInfo, path: String): String {
        val r = boundsOf(node)
        return pkgOf(node) + "|" + shortClassOf(node) + "|" + path + "|" +
            r.left + "_" + r.top + "_" + r.right + "_" + r.bottom
    }

    private inline fun <T> safe(block: () -> T?): T? = try {
        block()
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // Errors & misc
    // ------------------------------------------------------------------

    private fun notRunning(): JSONObject =
        JSONObject().put("ok", false).put("error", "service_not_running")

    private fun staleError(gen: Int): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", "node_stale")
            .put("hint", "re-read_screen; id map is generation $gen")

    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "accessibility_focus"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "clear_accessibility_focus"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set_selection"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id -> "show_on_screen"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> "scroll_up"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> "scroll_down"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> "scroll_left"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "scroll_right"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id -> "ime_enter"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id -> "scroll_to_position"
        else -> "action_0x" + Integer.toHexString(id)
    }
}
