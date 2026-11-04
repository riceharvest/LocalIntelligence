package dev.localintelligence.app.screen

/**
 * The window that tells the person a run is driving their screen, and that can end it.
 *
 * Implemented by the full build's accessibility service, which draws a small overlay window
 * over every app (`TYPE_ACCESSIBILITY_OVERLAY`). An interface, not the class, because this
 * file is in the shared `main` source set and the Play build has no service at all.
 */
interface ScreenOverlay {
    /** Shows the Stop control. [onStop] runs when the person taps it, on the main thread. */
    fun show(onStop: () -> Unit)

    /** Removes the Stop control. Safe to call when nothing is shown. */
    fun hide()
}

/**
 * The run-level state of screen control: whether a run is using the screen, and the Stop
 * overlay that goes with it.
 *
 * WHY a process-wide object: the tools that drive the screen run inside the agent loop, but the
 * Stop button belongs to the view model that owns the run and the overlay belongs to the
 * accessibility service. None of the three can reach the others directly, so they meet here.
 * The overlay is shown the first time a run touches the screen, and removed when the run
 * reaches a terminal state (not while it waits for an approval). Tapping Stop cancels the run,
 * exactly as the in-app Stop button does.
 */
object ScreenRun {
    /** True while the accessibility service is connected. The Settings setup card reads it. */
    @Volatile
    var serviceConnected: Boolean = false

    /** The overlay, set by the service while it is connected, null otherwise. */
    @Volatile
    var overlay: ScreenOverlay? = null

    private var stopHandler: (() -> Unit)? = null
    private var shown = false

    /** Set by the view model that owns the run: what tapping Stop does. */
    @Synchronized
    fun setStopHandler(handler: (() -> Unit)?) {
        stopHandler = handler
    }

    /** Shows the Stop overlay for this run, once. A no-op without a connected service. */
    @Synchronized
    fun begin() {
        if (shown) return
        val window = overlay ?: return
        shown = true
        window.show { requestStop() }
    }

    /** Removes the Stop overlay when the run has ended. Safe to call more than once. */
    @Synchronized
    fun end() {
        if (!shown) return
        shown = false
        overlay?.hide()
    }

    private fun requestStop() {
        val handler = synchronized(this) { stopHandler }
        end()
        handler?.invoke()
    }
}
