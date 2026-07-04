package com.beqa.body.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.beqa.body.a11y.BodyAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes / queries the live AccessibilityService UI tree into compact JSON.
 *
 * Framework + org.json only. No AndroidX, no Gradle. Set-of-Marks: each dump/query
 * assigns small integer ids to nodes and remembers a StableKey per id so a later
 * describeNode() can re-locate the (possibly moved) node across a re-read.
 */
object BodyScreenReader {

    private val lock = Any()

    // ---- Set-of-Marks state (guarded by [lock]) ----
    private var generation: Int = 0
    private val idMap: MutableMap<Int, String> = LinkedHashMap()

    private const val HARD_MAX = 2000
    private const val DEFAULT_MAX = 500
    private const val SYSTEM_UI_PKG = "com.android.systemui"

    // ------------------------------------------------------------------
    // Safe property readers (framework throws on stale nodes -> swallow).
    // ------------------------------------------------------------------

    private inline fun <T> safe(default: T, block: () -> T): T =
        try { block() } catch (t: Throwable) { default }

    private fun shortClass(cls: CharSequence?): String =
        (cls?.toString() ?: "").substringAfterLast('.')

    private fun classNameOf(node: AccessibilityNodeInfo): String =
        safe("") { node.className?.toString() ?: "" }

    private fun pkgOf(node: AccessibilityNodeInfo): String =
        safe("") { node.packageName?.toString() ?: "" }

    private fun textOf(node: AccessibilityNodeInfo): String =
        safe("") { node.text?.toString() ?: "" }

    private fun descOf(node: AccessibilityNodeInfo): String =
        safe("") { node.contentDescription?.toString() ?: "" }

    private fun ridOf(node: AccessibilityNodeInfo): String? =
        safe(null) { node.viewIdResourceName }

    private fun hintOf(node: AccessibilityNodeInfo): String =
        safe("") { node.hintText?.toString() ?: "" }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect {
        val r = Rect()
        safe(Unit) { node.getBoundsInScreen(r) }
        return r
    }

    private fun packState(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder(8)
        if (safe(false) { node.isClickable })     sb.append('c')
        if (safe(false) { node.isEditable })      sb.append('e')
        if (safe(false) { node.isScrollable })    sb.append('s')
        if (safe(false) { node.isCheckable })     sb.append('k')
        if (safe(false) { node.isFocusable })     sb.append('f')
        if (!safe(true)  { node.isEnabled })      sb.append('d')
        if (safe(false) { node.isSelected })      sb.append('x')
        if (safe(false) { node.isLongClickable }) sb.append('p')
        return sb.toString()
    }

    private fun isInteresting(node: AccessibilityNodeInfo): Boolean {
        if (safe(false) { node.isClickable }) return true
        if (safe(false) { node.isEditable }) return true
        if (safe(false) { node.isCheckable }) return true
        if (safe(false) { node.isScrollable }) return true
        if (textOf(node).isNotBlank()) return true
        if (descOf(node).isNotBlank()) return true
        return false
    }

    private fun isVisibleUsable(node: AccessibilityNodeInfo): Boolean {
        if (!safe(false) { node.isVisibleToUser }) return false
        val b = boundsOf(node)
        return b.width() > 0 && b.height() > 0
    }

    // ------------------------------------------------------------------
    // Identity + serialization
    // ------------------------------------------------------------------

    /** "packageName|shortClass|treepath|l_t_r_b" — stable-ish across re-reads. */
    private fun stableKey(node: AccessibilityNodeInfo, treepath: String): String {
        val pkg = pkgOf(node)
        val sc = shortClass(classNameOf(node))
        val b = boundsOf(node)
        return "$pkg|$sc|$treepath|${b.left}_${b.top}_${b.right}_${b.bottom}"
    }

    /** CompactNode: omit empty/false fields for token savings. */
    private fun compactNode(node: AccessibilityNodeInfo, id: Int, includeBounds: Boolean): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        val role = shortClass(classNameOf(node))
        if (role.isNotBlank()) o.put("role", role)
        val text = textOf(node); if (text.isNotBlank()) o.put("text", text)
        val desc = descOf(node); if (desc.isNotBlank()) o.put("desc", desc)
        val rid = ridOf(node); if (rid != null) o.put("rid", rid)
        val hint = hintOf(node); if (hint.isNotBlank()) o.put("hint", hint)
        val state = packState(node); if (state.isNotEmpty()) o.put("state", state)
        if (safe(false) { node.isCheckable }) o.put("checked", safe(false) { node.isChecked })
        if (includeBounds) {
            val b = boundsOf(node)
            o.put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
        }
        return o
    }

    // ------------------------------------------------------------------
    // Walk state
    // ------------------------------------------------------------------

    private class Walk(
        val includeBounds: Boolean,
        val includeSystemUi: Boolean,
        val maxNodes: Int
    ) {
        var nextId = 1
        var count = 0
        var truncated = false
        val flat = JSONArray()
        val marks = LinkedHashMap<Int, String>()
    }

    /** Interactive mode: surface only interesting nodes (flat), traverse everything. */
    private fun walkInteractive(node: AccessibilityNodeInfo?, treepath: String, w: Walk) {
        if (node == null || w.truncated) return
        if (!w.includeSystemUi && pkgOf(node) == SYSTEM_UI_PKG) return
        if (!isVisibleUsable(node)) return

        if (isInteresting(node)) {
            if (w.count >= w.maxNodes) {
                w.truncated = true
                return
            }
            val id = w.nextId++
            w.marks[id] = stableKey(node, treepath)
            w.flat.put(compactNode(node, id, w.includeBounds))
            w.count++
        }

        val cc = safe(0) { node.childCount }
        for (i in 0 until cc) {
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            walkInteractive(child, "$treepath.$i", w)
            safe(Unit) { child.recycle() }
        }
    }

    /** Full mode: emit every visible node with nested children. */
    private fun walkFull(node: AccessibilityNodeInfo?, treepath: String, w: Walk): JSONObject? {
        if (node == null) return null
        if (!w.includeSystemUi && pkgOf(node) == SYSTEM_UI_PKG) return null
        if (!isVisibleUsable(node)) return null
        if (w.count >= w.maxNodes) { w.truncated = true; return null }

        val id = w.nextId++
        w.marks[id] = stableKey(node, treepath)
        val cn = compactNode(node, id, w.includeBounds)
        w.count++

        val childArr = JSONArray()
        val cc = safe(0) { node.childCount }
        for (i in 0 until cc) {
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            val cj = walkFull(child, "$treepath.$i", w)
            if (cj != null) childArr.put(cj)
            safe(Unit) { child.recycle() }
        }
        if (childArr.length() > 0) cn.put("children", childArr)
        return cn
    }

    // ------------------------------------------------------------------
    // Structural hash (over pruned interactive set; ignores id + bounds)
    // ------------------------------------------------------------------

    private fun hashWalk(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (pkgOf(node) == SYSTEM_UI_PKG) return
        if (!isVisibleUsable(node)) return
        if (isInteresting(node)) {
            val checked = if (safe(false) { node.isCheckable }) safe(false) { node.isChecked }.toString() else ""
            sb.append(shortClass(classNameOf(node))).append('|')
                .append(textOf(node)).append('|')
                .append(descOf(node)).append('|')
                .append(ridOf(node) ?: "").append('|')
                .append(packState(node)).append('|')
                .append(checked).append(';')
        }
        val cc = safe(0) { node.childCount }
        for (i in 0 until cc) {
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            hashWalk(child, sb)
            safe(Unit) { child.recycle() }
        }
    }

    private fun computeScreenHash(svc: BodyAccessibilityService): String {
        val sb = StringBuilder(256)
        val roots = safe(emptyList<AccessibilityNodeInfo>()) { svc.allRoots() }
        roots.forEach { hashWalk(it, sb) }
        return Integer.toHexString(sb.toString().hashCode())
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun readScreen(
        mode: String = "interactive",
        includeBounds: Boolean = false,
        includeSystemUi: Boolean = false,
        maxNodes: Int = DEFAULT_MAX
    ): JSONObject {
        val svc = BodyAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "service_not_running")

        val cap = maxNodes.coerceIn(1, HARD_MAX)
        synchronized(lock) {
            generation++
            idMap.clear()

            val hash = computeScreenHash(svc)
            val w = Walk(includeBounds, includeSystemUi, cap)
            val roots = safe(emptyList<AccessibilityNodeInfo>()) { svc.allRoots() }

            val out = JSONObject()
            out.put("ok", true)
            out.put("app", svc.foregroundPackage ?: JSONObject.NULL)
            out.put("screen_hash", hash)
            out.put("generation", generation)

            if (mode == "full") {
                val rootJsons = ArrayList<JSONObject>()
                roots.forEachIndexed { idx, r ->
                    walkFull(r, idx.toString(), w)?.let { rootJsons.add(it) }
                }
                val tree: JSONObject = when (rootJsons.size) {
                    1 -> rootJsons[0]
                    else -> JSONObject()
                        .put("role", "window_roots")
                        .put("children", JSONArray(rootJsons))
                }
                out.put("tree", tree)
            } else {
                roots.forEachIndexed { idx, r -> walkInteractive(r, idx.toString(), w) }
                out.put("elements", w.flat)
            }

            idMap.putAll(w.marks)
            out.put("node_count", w.count)
            out.put("truncated", w.truncated)
            return out
        }
    }

    fun findNodes(
        text: String? = null,
        contentDesc: String? = null,
        resourceId: String? = null,
        className: String? = null,
        clickableOnly: Boolean = false,
        exact: Boolean = false,
        limit: Int = 20
    ): JSONObject {
        val svc = BodyAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "service_not_running")

        val cap = limit.coerceIn(1, HARD_MAX)
        synchronized(lock) {
            generation++
            idMap.clear()

            val matches = JSONArray()
            val next = intArrayOf(1)
            val roots = safe(emptyList<AccessibilityNodeInfo>()) { svc.allRoots() }
            roots.forEachIndexed { idx, root ->
                findWalk(root, idx.toString(), matches, next, cap,
                    text, contentDesc, resourceId, className, clickableOnly, exact)
            }

            val out = JSONObject()
            out.put("ok", true)
            out.put("count", matches.length())
            out.put("generation", generation)
            out.put("matches", matches)
            return out
        }
    }

    private fun findWalk(
        node: AccessibilityNodeInfo?,
        treepath: String,
        matches: JSONArray,
        next: IntArray,
        limit: Int,
        text: String?, contentDesc: String?, resourceId: String?,
        className: String?, clickableOnly: Boolean, exact: Boolean
    ) {
        if (node == null || matches.length() >= limit) return
        if (pkgOf(node) == SYSTEM_UI_PKG) return
        if (!isVisibleUsable(node)) return

        if (nodeMatches(node, text, contentDesc, resourceId, className, clickableOnly, exact)) {
            val id = next[0]++
            idMap[id] = stableKey(node, treepath)
            matches.put(compactNode(node, id, includeBounds = false))
            if (matches.length() >= limit) return
        }

        val cc = safe(0) { node.childCount }
        for (i in 0 until cc) {
            if (matches.length() >= limit) break
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            findWalk(child, "$treepath.$i", matches, next, limit,
                text, contentDesc, resourceId, className, clickableOnly, exact)
            safe(Unit) { child.recycle() }
        }
    }

    private fun strMatch(hay: String, needle: String, exact: Boolean): Boolean =
        if (exact) hay.equals(needle, ignoreCase = true) else hay.contains(needle, ignoreCase = true)

    private fun nodeMatches(
        node: AccessibilityNodeInfo,
        text: String?, contentDesc: String?, resourceId: String?,
        className: String?, clickableOnly: Boolean, exact: Boolean
    ): Boolean {
        if (clickableOnly && !safe(false) { node.isClickable }) return false
        if (text != null && !strMatch(textOf(node), text, exact)) return false
        if (contentDesc != null && !strMatch(descOf(node), contentDesc, exact)) return false
        if (resourceId != null) {
            val rid = ridOf(node) ?: ""
            val leaf = rid.substringAfterLast('/')
            val ok = if (exact) rid == resourceId || leaf == resourceId
            else rid.contains(resourceId, true) || leaf.contains(resourceId, true)
            if (!ok) return false
        }
        if (className != null) {
            val full = classNameOf(node)
            val sc = shortClass(full)
            val ok = if (exact) full == className || sc == className
            else full.contains(className, true) || sc.contains(className, true)
            if (!ok) return false
        }
        return true
    }

    fun describeNode(id: Int): JSONObject {
        val svc = BodyAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "service_not_running")

        synchronized(lock) {
            val key = idMap[id]
                ?: return staleResult()

            // parse StableKey: pkg|shortClass|treepath|bounds
            val parts = key.split("|", limit = 4)
            val wantClass = if (parts.size > 1) parts[1] else ""
            val wantPath = if (parts.size > 2) parts[2] else ""

            val roots = safe(emptyList<AccessibilityNodeInfo>()) { svc.allRoots() }
            var exactHit: JSONObject? = null
            var fallbackHit: JSONObject? = null

            val holder = arrayOfNulls<JSONObject>(2) // [0]=exact, [1]=fallback
            roots.forEachIndexed { idx, root ->
                if (holder[0] == null) {
                    describeWalk(root, idx.toString(), key, wantClass, wantPath, id, holder)
                }
            }
            exactHit = holder[0]
            fallbackHit = holder[1]

            val node = exactHit ?: fallbackHit ?: return staleResult()
            return JSONObject().put("ok", true).put("node", node)
        }
    }

    /**
     * Resolve a Set-of-Marks id to a LIVE node (for acting on it), by navigating the
     * StableKey's treepath from the window root and sanity-checking the class. Returns
     * null if the id is unknown or the tree has shifted (caller should re-read_screen).
     */
    fun resolveNode(id: Int): AccessibilityNodeInfo? {
        val svc = BodyAccessibilityService.instance ?: return null
        synchronized(lock) {
            val key = idMap[id] ?: return null
            val parts = key.split("|", limit = 4)
            val wantClass = if (parts.size > 1) parts[1] else ""
            val treepath = if (parts.size > 2) parts[2] else return null
            val idxs = treepath.split(".").mapNotNull { it.toIntOrNull() }
            if (idxs.isEmpty()) return null
            val roots = safe(emptyList<AccessibilityNodeInfo>()) { svc.allRoots() }
            var node: AccessibilityNodeInfo = roots.getOrNull(idxs[0]) ?: return null
            for (k in 1 until idxs.size) {
                node = safe<AccessibilityNodeInfo?>(null) { node.getChild(idxs[k]) } ?: return null
            }
            if (wantClass.isNotEmpty() && shortClass(classNameOf(node)) != wantClass) return null
            return node
        }
    }

    /** Current structural screen hash (for before/after action verification). */
    fun currentHash(): String {
        val svc = BodyAccessibilityService.instance ?: return ""
        synchronized(lock) { return computeScreenHash(svc) }
    }

    private fun staleResult(): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", "node_stale")
            .put("hint", "re-read_screen; id map is generation $generation")

    private fun describeWalk(
        node: AccessibilityNodeInfo?,
        treepath: String,
        wantKey: String,
        wantClass: String,
        wantPath: String,
        id: Int,
        holder: Array<JSONObject?>
    ) {
        if (node == null || holder[0] != null) return
        if (!isVisibleUsable(node)) {
            // still traverse children so treepath indices stay aligned
        } else {
            val thisKey = stableKey(node, treepath)
            if (thisKey == wantKey) {
                holder[0] = fullProps(node, id)
                return
            }
            if (holder[1] == null &&
                treepath == wantPath &&
                shortClass(classNameOf(node)) == wantClass
            ) {
                holder[1] = fullProps(node, id)
            }
        }
        val cc = safe(0) { node.childCount }
        for (i in 0 until cc) {
            if (holder[0] != null) break
            val child = safe<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            describeWalk(child, "$treepath.$i", wantKey, wantClass, wantPath, id, holder)
            safe(Unit) { child.recycle() }
        }
    }

    private fun fullProps(node: AccessibilityNodeInfo, id: Int): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        val role = shortClass(classNameOf(node)); if (role.isNotBlank()) o.put("role", role)
        val text = textOf(node); if (text.isNotBlank()) o.put("text", text)
        val desc = descOf(node); if (desc.isNotBlank()) o.put("desc", desc)
        o.put("viewId", ridOf(node) ?: JSONObject.NULL)
        o.put("hint", hintOf(node))
        o.put("state", packState(node))
        o.put("enabled", safe(true) { node.isEnabled })
        o.put("selected", safe(false) { node.isSelected })
        o.put("checked", safe(false) { node.isChecked })
        val b = boundsOf(node)
        o.put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
        o.put("childCount", safe(0) { node.childCount })

        val actions = JSONArray()
        val list = safe(emptyList<AccessibilityNodeInfo.AccessibilityAction>()) { node.actionList }
        list.forEach { actions.put(actionName(it)) }
        o.put("actions", actions)
        return o
    }

    private fun actionName(a: AccessibilityNodeInfo.AccessibilityAction): String = when (a.id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
        AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear_focus"
        AccessibilityNodeInfo.ACTION_SELECT -> "select"
        AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear_selection"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set_selection"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> "a11y_focus"
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> "clear_a11y_focus"
        AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT -> "next_html_element"
        AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT -> "prev_html_element"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        else -> a.label?.toString() ?: ("0x" + Integer.toHexString(a.id))
    }

    fun screenDiff(previousHash: String): JSONObject {
        val svc = BodyAccessibilityService.instance
            ?: return JSONObject().put("ok", false).put("error", "service_not_running")

        synchronized(lock) {
            val hash = computeScreenHash(svc)
            if (hash == previousHash) {
                return JSONObject().put("ok", true).put("changed", false).put("hash", hash)
            }
            // We don't cheaply retain the previous label set keyed by hash, so labels are
            // reported empty while still signalling the structural change + new hash.
            return JSONObject()
                .put("ok", true)
                .put("changed", true)
                .put("hash", hash)
                .put("app", svc.foregroundPackage ?: JSONObject.NULL)
                .put("added_labels", JSONArray())
                .put("removed_labels", JSONArray())
        }
    }
}
