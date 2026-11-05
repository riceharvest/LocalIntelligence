package dev.localintelligence.app.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.localintelligence.core.policy.ScreenScope

/**
 * The accessibility service behind screen control (full build only, docs/adr/0002, docs/adr/0003).
 *
 * It does three things and nothing else: reads the tree of the app in front, performs the few
 * actions the screen tools ask for, and shows the Stop overlay while a run uses the screen. Every
 * decision about whether an action may happen is taken by `RiskPolicy` before a tool gets here; this
 * class only carries it out.
 *
 * WHY the index is checked against the live tree before every use: a model reads the screen, then acts
 * a turn later. In between the app may have changed. A number is therefore resolved against the read
 * it came from, by the identity of the item (see [ScreenElement.key]), and an item that is gone is an
 * error that tells the model to read again. It never silently acts on whatever now sits at that position.
 */
class ScreenControlService : AccessibilityService(), ScreenOverlay {

    private val main = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenRun.overlay = this
        ScreenRun.serviceConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // A window change to another app drops the last read: its numbers describe the app that was in front.
        // Our own overlay and windows are ignored, or showing Stop would clear the read it is meant to stop.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val read = lastRead
        if (read != null && read.packageName != pkg) lastRead = null
    }

    override fun onInterrupt() {
        // Nothing is in flight that can be interrupted: each action is a single call.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun detach() {
        if (instance === this) instance = null
        ScreenRun.overlay = null
        ScreenRun.serviceConnected = false
        lastRead = null
        removeOverlay()
    }

    // ----------------------------------------------------------------- reads

    /** Reads the app in front now, and keeps the read as the one later indexes refer to. */
    internal fun read(): ScreenSnapshot? {
        val snapshot = snapshotNow() ?: return null
        lastRead = snapshot
        return snapshot
    }

    /**
     * Resolves a number from the last read against the live tree, or null.
     *
     * Null when there was no read, the number is not in it, or the item is no longer on screen.
     */
    internal fun resolve(index: Int): ScreenTarget? {
        val read = lastRead ?: return null
        val item = read.elements.getOrNull(index - 1) ?: return null
        val live = snapshotNow() ?: return null
        val at = live.elements.indexOfFirst { it.key == item.key }
        if (at < 0) return null
        return ScreenTarget(live.elements[at], live.nodes[at], live.appLabel)
    }

    /** The app in front, without its items. Used by the tools that act on no item. */
    fun foregroundScope(): ScreenScope? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return ScreenScope(
            packageName = pkg,
            appLabel = labelOf(pkg),
            controlLabel = "",
            controlDescription = "",
        )
    }

    private fun snapshotNow(): ScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: return null
        return ScreenTree.collect(root, pkg, labelOf(pkg))
    }

    private fun labelOf(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (e: Exception) {
        pkg
    }

    // ----------------------------------------------------------------- actions

    /**
     * Taps an item: the click action on the node or the nearest clickable ancestor, else a tap at its
     * centre. Returns false when neither could be performed.
     */
    internal fun tap(target: ScreenTarget): Boolean = clickOrGesture(target.node, longPress = false)

    /** A long press, the same way as [tap]. */
    internal fun longPress(target: ScreenTarget): Boolean = clickOrGesture(target.node, longPress = true)

    /** Replaces the text of an editable item. Refused for a password field, as the policy already does. */
    internal fun typeText(target: ScreenTarget, text: String): Boolean {
        if (target.element.password) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Scrolls the first scrollable area in the app in front. [forward] is "show what is below". */
    internal fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val area = findScrollable(root, 0) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return area.performAction(action)
    }

    /** The Back, Home buttons, through the system's global actions. */
    internal fun globalAction(action: Int): Boolean = performGlobalAction(action)

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickOrGesture(node: AccessibilityNodeInfo, longPress: Boolean): Boolean {
        // Walk up from the item to the first ancestor that takes the click: most labels are not clickable themselves.
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            val acted = if (longPress) {
                current.isLongClickable && current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } else {
                current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (acted) return true
            current = current.parent
            depth++
        }
        // No node took the action: tap its centre with a gesture (the service declares canPerformGestures).
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val duration = if (longPress) LONG_PRESS_MS else TAP_MS
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ----------------------------------------------------------------- overlay

    /**
     * Shows the Stop control: a small window over every app, drawn at the bottom so it does not cover
     * the usual controls. It takes no focus, so typing in the app still works. Tapping it calls [onStop].
     */
    override fun show(onStop: () -> Unit) {
        main.post {
            if (overlayView != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val label = TextView(this).apply {
                text = this@ScreenControlService.getString(dev.localintelligence.app.R.string.screen_stop_overlay)
                textSize = 15f
                setPadding(48, 28, 48, 28)
                setBackgroundColor(0xFF1B1B1F.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { onStop() }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 96
            }
            try {
                wm.addView(label, params)
                overlayView = label
            } catch (e: RuntimeException) {
                // A window that cannot be added leaves the run going without the Stop button; the run still stops from the app.
                overlayView = null
            }
        }
    }

    override fun hide() {
        main.post { removeOverlay() }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: RuntimeException) {
            // Already gone (the service was torn down first); nothing left to remove.
        }
    }

    companion object {
        /** The connected service, or null while the person has not turned it on. */
        @Volatile
        var instance: ScreenControlService? = null
            private set

        /** The last read of the app in front. Dropped when the app changes, and before every new read is taken. */
        @Volatile
        private var lastRead: ScreenSnapshot? = null

        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 600L
    }
}
