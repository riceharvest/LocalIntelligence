package dev.localintelligence.app

import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.metrics.RunMetrics
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.app.ui.ChatViewModel

/**
 * The seam a debug-only control channel reaches the running app through.
 *
 * WHY THIS IS THE ONLY DEBUG HOOK IN MAIN: the control channel lives in
 * `src/debug` and is compiled into debug builds only. Main code can not name it,
 * so it talks to it through this interface. Release builds never install an
 * implementation: [DebugPorts.current] stays null, every call site below is a
 * null-safe no-op, and nothing a release run does changes.
 *
 * WHY NO SETTING: the channel is switched on by the build type, not by the user.
 * A settings row would put a debug switch in front of end users.
 */
interface DebugPort {
    /** The chat screen's view model exists. Commands act on the same state the screen shows. */
    fun chatCreated(chat: ChatViewModel)

    /** A recorder for one run, or null. Passed to the agent controller so it reports per-run numbers. */
    fun newRunRecorder(): RunRecorder?

    /**
     * A run reached a terminal result. Called once per run from `AgentViewModel.publish`.
     * [controllerMetrics] is the controller's frozen metrics, or null when the run never
     * got a controller (a stop during model load, for example).
     */
    fun runEnded(result: AgentResult, trace: List<StepTrace>, controllerMetrics: RunMetrics?)

    /** A run stopped to wait for an approval. It is not over: the same recorder goes on after the answer. */
    fun runPaused(result: AgentResult, trace: List<StepTrace>, controllerMetrics: RunMetrics?)

    /**
     * Where the model hub lives, for the emulator tests: a host-side stand-in for huggingface.co, so
     * a download can be slowed, cut, refused or corrupted on demand. Null is the real hub, which is
     * what every build without a debug port uses.
     */
    fun hubBaseUrl(): String? = null
}

/** Holds the debug port. Written once, by the debug build's content provider, before the first screen opens. */
object DebugPorts {
    @Volatile
    var current: DebugPort? = null
}
