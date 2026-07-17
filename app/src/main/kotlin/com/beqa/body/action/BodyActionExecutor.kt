package com.beqa.body.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.beqa.body.a11y.BodyAccessibilityService
import com.beqa.body.screen.BodyScreenReader
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes touch/gesture/key actions against the live accessibility tree and
 * verifies each action by comparing screen hashes before/after.
 *
 * Every mutating method returns an ActionOutcome JSONObject:
 *   { ok, action, method, verified, screen_changed, hash_before, hash_after, stuck }
 * plus method-specific extras. Failures return { ok:false, error, hint? }.
 */
object BodyActionExecutor {

    private const val SETTLE_POLL_MS = 100L
    private const val SETTLE_MAX_MS = 500L
    private const val MAX_ANCESTOR_HOPS = 8
    private const val HISTORY_MAX = 6

    private val DIRECTIONS = setOf("up", "down", "left", "right")

    // ---------------------------------------------------------------- history

    private class HistoryEntry(
        val action: String,
        val targetKey: String,
        val hashAfter: String,
        val screenChanged: Boolean
    )

    private val history = ArrayDeque<HistoryEntry>()

    @Synchronized
    private fun recordAndCheckStuck(
        action: String,
        targetKey: String,
        hashAfter: String,
        screenChanged: Boolean
    ): Boolean {
        history.addLast(HistoryEntry(action, targetKey, hashAfter, screenChanged))
        while (history.size > HISTORY_MAX) history.removeFirst()
        // Rule 1: same (action, targetKey) >= 3 times with no screen change.
        val sameNoChange = history.count {
            it.action == action && it.targetKey == targetKey && !it.screenChanged
        }
        if (sameNoChange >= 3) return true
        // Rule 2: last >= 4 entries cycle between <= 2 distinct hashes.
        if (history.size >= 4) {
            val lastFour = history.toList().takeLast(4)
            if (lastFour.map { it.hashAfter }.toSet().size <= 2) return true
        }
        return false
    }

    // ---------------------------------------------------------------- helpers

    private fun service(): BodyAccessibilityService? = BodyAccessibilityService.instance

    private fun fail(action: String, error: String, hint: String? = null): JSONObject {
        val o = JSONObject()
            .put("ok", false)
            .put("action", action)
            .put("error", error)
        if (hint != null) o.put("hint", hint)
        return o
    }

    private fun safeHash(): String = try {
        BodyScreenReader.currentHash()
    } catch (_: Exception) {
        ""
    }

    /** Poll the screen hash until stable (two consecutive equal reads) or 500ms. */
    private fun settle(): String {
        var last = safeHash()
        val deadline = System.currentTimeMillis() + SETTLE_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(SETTLE_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return last
            }
            val cur = safeHash()
            if (cur == last) return cur
            last = cur
        }
        return last
    }

    private fun buildOutcome(
        action: String,
        method: String,
        verified: Boolean,
        hashBefore: String,
        hashAfter: String,
        targetKey: String,
        extras: JSONObject? = null
    ): JSONObject {
        val changed = hashAfter != hashBefore
        val stuck = recordAndCheckStuck(action, targetKey, hashAfter, changed)
        val o = JSONObject()
            .put("ok", true)
            .put("action", action)
            .put("method", method)
            .put("verified", verified)
            .put("screen_changed", changed)
            .put("hash_before", hashBefore)
            .put("hash_after", hashAfter)
            .put("stuck", stuck)
        if (stuck) {
            o.put(
                "hint",
                "loop detected: repeated action without progress; re-read the screen and try a different element or approach"
            )
        }
        if (extras != null) {
            for (k in extras.keys()) o.put(k, extras.get(k))
        }
        return o
    }

    /** Dispatch a single-stroke gesture and await its result on a latch. */
    private fun dispatch(
        svc: AccessibilityService,
        path: Path,
        durationMs: Long,
        awaitMs: Long
    ): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val latch = CountDownLatch(1)
            val completed = AtomicBoolean(false)
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed.set(false)
                    latch.countDown()
                }
            }
            if (!svc.dispatchGesture(gesture, callback, null)) return false
            if (!latch.await(awaitMs, TimeUnit.MILLISECONDS)) return false
            completed.get()
        } catch (_: Exception) {
            false
        }
    }

    private fun dispatchPoint(
        svc: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        awaitMs: Long
    ): Boolean {
        val path = Path()
        path.moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f))
        return dispatch(svc, path, durationMs, awaitMs)
    }

    private fun screenBounds(svc: AccessibilityService): Rect {
        val root = try {
            svc.rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (root != null) {
            val r = Rect()
            try {
                root.getBoundsInScreen(r)
            } catch (_: Exception) {
            }
            if (!r.isEmpty) return r
        }
        val dm = svc.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun nodeBoundsOrScreen(svc: AccessibilityService, node: AccessibilityNodeInfo?): Rect {
        if (node != null) {
            val r = Rect()
            try {
                node.getBoundsInScreen(r)
            } catch (_: Exception) {
            }
            if (!r.isEmpty) return r
        }
        return screenBounds(svc)
    }

    /**
     * A directional stroke within [bounds]. [fingerDirection] is the literal
     * direction the finger travels. ~300ms, distance short/medium/long =
     * 30/60/90 percent of the relevant dimension.
     */
    private fun directionalGesture(
        svc: AccessibilityService,
        bounds: Rect,
        fingerDirection: String,
        distance: String
    ): Boolean {
        val frac = when (distance.lowercase()) {
            "short" -> 0.3f
            "long" -> 0.9f
            else -> 0.6f
        }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val marginX = bounds.width() * 0.08f
        val marginY = bounds.height() * 0.08f
        val minX = bounds.left + marginX
        val maxX = bounds.right - marginX
        val minY = bounds.top + marginY
        val maxY = bounds.bottom - marginY
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        var sx = cx
        var sy = cy
        var ex = cx
        var ey = cy
        when (fingerDirection) {
            "up" -> {
                val half = bounds.height() * frac / 2f
                sy = cy + half
                ey = cy - half
            }
            "down" -> {
                val half = bounds.height() * frac / 2f
                sy = cy - half
                ey = cy + half
            }
            "left" -> {
                val half = bounds.width() * frac / 2f
                sx = cx + half
                ex = cx - half
            }
            "right" -> {
                val half = bounds.width() * frac / 2f
                sx = cx - half
                ex = cx + half
            }
            else -> return false
        }
        sx = sx.coerceIn(minX, maxX)
        ex = ex.coerceIn(minX, maxX)
        sy = sy.coerceIn(minY, maxY)
        ey = ey.coerceIn(minY, maxY)
        val path = Path()
        path.moveTo(sx, sy)
        path.lineTo(ex, ey)
        return dispatch(svc, path, 300L, 3000L)
    }

    private fun oppositeDirection(d: String): String = when (d) {
        "up" -> "down"
        "down" -> "up"
        "left" -> "right"
        "right" -> "left"
        else -> d
    }

    /** Resolve matches[0].id from findNodes for a fallback text lookup. */
    private fun resolveByText(text: String): AccessibilityNodeInfo? {
        val matches = try {
            BodyScreenReader.findNodes(text = text, limit = 1).optJSONArray("matches")
        } catch (_: Exception) {
            null
        }
        if (matches == null || matches.length() == 0) return null
        val mid = matches.optJSONObject(0)?.optString("id", "") ?: ""
        if (mid.isEmpty()) return null
        return try {
            BodyScreenReader.resolveChecked(mid).first
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- tap / longPress

    fun tap(
        id: String? = null,
        x: Int? = null,
        y: Int? = null,
        fallbackText: String? = null
    ): JSONObject = clickLike("tap", id, x, y, fallbackText, longPress = false, pressDurationMs = 50L)

    fun longPress(
        id: String? = null,
        x: Int? = null,
        y: Int? = null,
        durationMs: Int = 600
    ): JSONObject = clickLike(
        "long_press", id, x, y, null,
        longPress = true,
        pressDurationMs = durationMs.toLong().coerceAtLeast(100L)
    )

    private fun clickLike(
        actionName: String,
        id: String?,
        x: Int?,
        y: Int?,
        fallbackText: String?,
        longPress: Boolean,
        pressDurationMs: Long
    ): JSONObject {
        return try {
            val svc = service() ?: return fail(actionName, "service_not_running")
            val targetKey = id
                ?: if (x != null && y != null) "$x,$y" else (fallbackText ?: "")
            val hashBefore = safeHash()

            var node: AccessibilityNodeInfo? = null
            var gestureX: Float? = null
            var gestureY: Float? = null

            when {
                id != null -> {
                    val resolved = try {
                        BodyScreenReader.resolveChecked(id)
                    } catch (_: Exception) {
                        null to "node_stale"
                    }
                    node = resolved.first
                        ?: return fail(actionName, resolved.second ?: "node_stale", "re-read_screen")
                }
                x != null && y != null -> {
                    gestureX = x.toFloat()
                    gestureY = y.toFloat()
                }
                fallbackText != null -> {
                    node = resolveByText(fallbackText)
                        ?: return fail(
                            actionName, "no_match",
                            "no node matching \"$fallbackText\"; re-read screen"
                        )
                }
                else -> return fail(actionName, "no_target", "provide id, x/y coordinates, or fallbackText")
            }

            var method: String? = null
            if (node != null) {
                val wanted =
                    if (longPress) AccessibilityAction.ACTION_LONG_CLICK
                    else AccessibilityAction.ACTION_CLICK
                // 1. Direct performAction on the node itself.
                val direct = try {
                    node.actionList.contains(wanted) && node.performAction(wanted.id)
                } catch (_: Exception) {
                    false
                }
                if (direct) method = if (longPress) "node_long_click" else "node_click"
                // 2. Nearest clickable ancestor (<= 8 hops).
                if (method == null) {
                    var p = try {
                        node.parent
                    } catch (_: Exception) {
                        null
                    }
                    var hops = 0
                    while (p != null && hops < MAX_ANCESTOR_HOPS) {
                        val has = try {
                            p.actionList.contains(wanted)
                        } catch (_: Exception) {
                            false
                        }
                        if (has) {
                            val okParent = try {
                                p.performAction(wanted.id)
                            } catch (_: Exception) {
                                false
                            }
                            if (okParent) method = "parent_click"
                            break
                        }
                        p = try {
                            p.parent
                        } catch (_: Exception) {
                            null
                        }
                        hops++
                    }
                }
                // 3. Gesture at the node's bounds center.
                if (method == null) {
                    val b = Rect()
                    try {
                        node.getBoundsInScreen(b)
                    } catch (_: Exception) {
                    }
                    gestureX = b.exactCenterX()
                    gestureY = b.exactCenterY()
                }
            }

            if (method == null) {
                val gx = gestureX
                val gy = gestureY
                if (gx == null || gy == null) return fail(actionName, "no_target")
                val awaitMs = if (longPress) 3000L else 2000L
                if (!dispatchPoint(svc, gx, gy, pressDurationMs, awaitMs)) {
                    return fail(actionName, "gesture_failed", "dispatchGesture returned false or timed out")
                }
                method = if (node != null) "gesture_fallback" else "coordinate"
            }

            val hashAfter = settle()
            buildOutcome(
                actionName, method,
                verified = hashAfter != hashBefore,
                hashBefore = hashBefore,
                hashAfter = hashAfter,
                targetKey = targetKey
            )
        } catch (e: Exception) {
            fail(actionName, "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- typeText

    fun typeText(
        text: String,
        id: String? = null,
        clearFirst: Boolean = true,
        submit: Boolean = false
    ): JSONObject {
        return try {
            val svc = service() ?: return fail("type_text", "service_not_running")
            val hashBefore = safeHash()

            val node: AccessibilityNodeInfo = if (id != null) {
                val resolved = try {
                    BodyScreenReader.resolveChecked(id)
                } catch (_: Exception) {
                    null to "node_stale"
                }
                resolved.first
                    ?: return fail("type_text", resolved.second ?: "node_stale", "re-read_screen")
            } else {
                try {
                    svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) {
                    null
                } ?: return fail(
                    "type_text", "no_focused_field",
                    "no input-focused node; tap the text field first or pass id"
                )
            }

            try {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            } catch (_: Exception) {
            }

            val existing = try {
                node.text?.toString() ?: ""
            } catch (_: Exception) {
                ""
            }
            val newText = if (clearFirst) text else existing + text
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText
            )
            val setOk = try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Exception) {
                false
            }
            if (!setOk) {
                return fail(
                    "type_text", "set_text_failed",
                    "node may not be editable; re-read screen and target an EditText"
                )
            }

            var submitted = false
            if (submit) {
                submitted = try {
                    node.actionList.contains(AccessibilityAction.ACTION_IME_ENTER) &&
                        node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
                } catch (_: Exception) {
                    false
                }
            }

            val hashAfter = settle()
            val fieldAfter = try {
                node.refresh()
                node.text?.toString() ?: ""
            } catch (_: Exception) {
                ""
            }
            val extras = JSONObject().put("field_text_after", fieldAfter)
            if (submit) extras.put("submitted", submitted)
            buildOutcome(
                "type_text", "set_text",
                verified = fieldAfter.contains(text),
                hashBefore = hashBefore,
                hashAfter = hashAfter,
                targetKey = id?.toString() ?: "focused",
                extras = extras
            )
        } catch (e: Exception) {
            fail("type_text", "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- scroll

    fun scroll(direction: String, id: String? = null, distance: String = "medium"): JSONObject {
        return try {
            val svc = service() ?: return fail("scroll", "service_not_running")
            val dir = direction.lowercase()
            if (dir !in DIRECTIONS) {
                return fail("scroll", "unknown_direction", "use up/down/left/right")
            }
            val hashBefore = safeHash()
            val targetKey = id ?: dir

            var baseNode: AccessibilityNodeInfo? = null
            var method: String? = null

            if (id != null) {
                val resolved = try {
                    BodyScreenReader.resolveChecked(id)
                } catch (_: Exception) {
                    null to "node_stale"
                }
                baseNode = resolved.first
                    ?: return fail("scroll", resolved.second ?: "node_stale", "re-read_screen")

                // down/right => SCROLL_FORWARD, up/left => SCROLL_BACKWARD
                val wanted =
                    if (dir == "down" || dir == "right") AccessibilityAction.ACTION_SCROLL_FORWARD
                    else AccessibilityAction.ACTION_SCROLL_BACKWARD
                var n: AccessibilityNodeInfo? = baseNode
                var hops = 0
                while (n != null && hops <= MAX_ANCESTOR_HOPS) {
                    val has = try {
                        n.actionList.contains(wanted)
                    } catch (_: Exception) {
                        false
                    }
                    if (has) {
                        val okScroll = try {
                            n.performAction(wanted.id)
                        } catch (_: Exception) {
                            false
                        }
                        if (okScroll) {
                            method = "node_scroll"
                            baseNode = n
                        }
                        break
                    }
                    n = try {
                        n.parent
                    } catch (_: Exception) {
                        null
                    }
                    hops++
                }
            }

            if (method == null) {
                // Gesture fallback: scrolling content "down" means the finger moves up.
                val bounds = nodeBoundsOrScreen(svc, baseNode)
                if (!directionalGesture(svc, bounds, oppositeDirection(dir), distance)) {
                    return fail("scroll", "gesture_failed", "dispatchGesture returned false or timed out")
                }
                method = "gesture_fallback"
            }

            val hashAfter = settle()
            val progressed = hashAfter != hashBefore
            buildOutcome(
                "scroll", method,
                verified = progressed,
                hashBefore = hashBefore,
                hashAfter = hashAfter,
                targetKey = targetKey,
                extras = JSONObject()
                    .put("direction", dir)
                    .put("scroll_progressed", progressed)
            )
        } catch (e: Exception) {
            fail("scroll", "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- swipe

    fun swipe(direction: String, distance: String = "medium"): JSONObject {
        return try {
            val svc = service() ?: return fail("swipe", "service_not_running")
            val dir = direction.lowercase()
            if (dir !in DIRECTIONS) {
                return fail("swipe", "unknown_direction", "use up/down/left/right")
            }
            val hashBefore = safeHash()
            val bounds = screenBounds(svc)
            if (!directionalGesture(svc, bounds, dir, distance)) {
                return fail("swipe", "gesture_failed", "dispatchGesture returned false or timed out")
            }
            val hashAfter = settle()
            buildOutcome(
                "swipe", "gesture",
                verified = hashAfter != hashBefore,
                hashBefore = hashBefore,
                hashAfter = hashAfter,
                targetKey = dir,
                extras = JSONObject()
                    .put("direction", dir)
                    .put("distance", distance)
            )
        } catch (e: Exception) {
            fail("swipe", "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- pressKey

    fun pressKey(key: String): JSONObject {
        return try {
            val svc = service() ?: return fail("press_key", "service_not_running")
            val globalAction = when (key.lowercase()) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else -> return fail(
                    "press_key", "unknown_key",
                    "use back/home/recents/notifications/quick_settings/lock_screen"
                )
            }
            val hashBefore = safeHash()
            val performed = try {
                svc.performGlobalAction(globalAction)
            } catch (_: Exception) {
                false
            }
            if (!performed) {
                return fail("press_key", "global_action_failed", "performGlobalAction returned false")
            }
            val hashAfter = settle()
            buildOutcome(
                "press_key", "global_action",
                verified = hashAfter != hashBefore,
                hashBefore = hashBefore,
                hashAfter = hashAfter,
                targetKey = key.lowercase(),
                extras = JSONObject().put("key", key.lowercase())
            )
        } catch (e: Exception) {
            fail("press_key", "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- waitFor

    fun waitFor(
        text: String? = null,
        resourceId: String? = null,
        app: String? = null,
        gone: Boolean = false,
        timeoutMs: Int = 5000
    ): JSONObject {
        return try {
            service() ?: return fail("wait_for", "service_not_running")
            if (text == null && resourceId == null && app == null) {
                return fail("wait_for", "no_condition", "provide text, resourceId, or app")
            }
            val start = System.currentTimeMillis()
            var found: Boolean
            while (true) {
                val present = conditionPresent(text, resourceId, app)
                found = present != gone
                if (found) break
                if (System.currentTimeMillis() - start >= timeoutMs) break
                try {
                    Thread.sleep(100L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            JSONObject()
                .put("ok", true)
                .put("action", "wait_for")
                .put("found", found)
                .put("waited_ms", System.currentTimeMillis() - start)
                .put("screen_hash", safeHash())
        } catch (e: Exception) {
            fail("wait_for", "exception", e.message ?: e.javaClass.simpleName)
        }
    }

    /** All provided conditions must currently hold. */
    private fun conditionPresent(text: String?, resourceId: String?, app: String?): Boolean {
        var present = true
        if (text != null || resourceId != null) {
            val matches = try {
                BodyScreenReader.findNodes(text = text, resourceId = resourceId, limit = 1)
                    .optJSONArray("matches")
            } catch (_: Exception) {
                null
            }
            present = matches != null && matches.length() > 0
        }
        if (present && app != null) {
            val fg = try {
                BodyAccessibilityService.instance?.foregroundPackage?.toString()
            } catch (_: Exception) {
                null
            }
            present = fg != null && (fg == app || fg.contains(app, ignoreCase = true))
        }
        return present
    }
}
