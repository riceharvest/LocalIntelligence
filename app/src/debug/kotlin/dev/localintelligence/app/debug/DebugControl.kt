package dev.localintelligence.app.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dev.localintelligence.app.DebugPort
import dev.localintelligence.app.DebugPorts
import dev.localintelligence.app.LocalIntelligenceApp
import dev.localintelligence.app.ModelAvailability
import dev.localintelligence.app.RunState
import dev.localintelligence.app.isActive
import dev.localintelligence.app.ui.ChatMessage
import dev.localintelligence.app.ui.ChatViewModel
import dev.localintelligence.core.agent.AgentResult
import dev.localintelligence.core.agent.StepTrace
import dev.localintelligence.core.metrics.GenerationRecord
import dev.localintelligence.core.metrics.RunMetrics
import dev.localintelligence.core.metrics.RunRecorder
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingsMode
import dev.localintelligence.core.settings.mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * The adb control channel for development. Debug builds only: this file, and the
 * manifest beside it, are not part of the release source sets.
 *
 * Commands arrive as an explicit broadcast with extras `cmd` and `arg`. Every
 * answer is one JSON object, set with setResultData, which `adb shell am
 * broadcast` prints. `DONE` lines go to logcat under tag LIControl.
 *
 * Approval is not bypassed: `approve` calls the same ChatViewModel.resolveConfirmation
 * that the Allow and Deny buttons call. RiskPolicy still decides what is staged.
 */

private const val TAG = "LIControl"

/** Max characters of the answer in a DONE line. Longer answers are cut and say so. */
private const val ANSWER_CAP = 3000

/** Keep each DONE line under logcat's per-entry limit, with room for the tag. */
private const val LINE_LIMIT = 3800

private const val ROW_CAP = 2000
private const val TRANSCRIPT_ROWS = 30
private const val TOOL_CAP = 15
private const val GENERATION_CAP = 10

/** Arguments per tool in a DONE line. Longer ones are left out and marked argsCut, never cut mid-JSON. */
private const val ARGS_CAP = 120

/** Receives adb commands and answers each with one JSON object. Runs on the main thread. */
class DebugControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        val arg = intent.getStringExtra("arg").orEmpty()
        val bridge = DebugPorts.current as? DebugBridge
        val answer = bridge?.handle(cmd, arg) ?: JSONObject().put("ok", false).put("error", "debug bridge is not installed")
        setResultData(answer.toString())
    }
}

/**
 * Starts once per process, before the app's own code runs. It only installs [DebugBridge]:
 * the bridge keeps no state that needs the app to be created yet.
 */
class DebugPortInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? LocalIntelligenceApp ?: return true
        DebugPorts.current = DebugBridge(app)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** The debug port: runs commands against the app, and writes the run report. */
class DebugBridge(private val app: LocalIntelligenceApp) : DebugPort {

    private val container get() = app.container

    /** The chat screen's view model, once the screen has been created. Null until then. */
    @Volatile
    private var chat: ChatViewModel? = null

    /** The recorder of the run in flight, or null. One run at a time, so one slot is enough. */
    @Volatile
    private var recorder: RunRecorder? = null

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun chatCreated(chat: ChatViewModel) {
        this.chat = chat
    }

    override fun newRunRecorder(): RunRecorder? {
        val created = RunRecorder(modelId = container.residentBackend().id)
        recorder = created
        return created
    }

    override fun runEnded(result: AgentResult, trace: List<StepTrace>, controllerMetrics: RunMetrics?) {
        val finished = recorder
        recorder = null
        Log.i(TAG, "DONE " + doneLine(result, trace, controllerMetrics, finished?.generations()))
    }

    /** Runs one command. Never throws: a failure is an answer with ok=false. */
    fun handle(cmd: String, arg: String): JSONObject = try {
        when (cmd) {
            "state" -> state()
            "send" -> send(arg)
            "newchat" -> newChat()
            "models" -> models()
            "usemodel" -> useModel(arg)
            "mode" -> setMode(arg)
            "approve" -> approve(arg)
            "transcript" -> transcript()
            else -> error("unknown command '$cmd'")
        }
    } catch (t: Throwable) {
        error(t.javaClass.simpleName + ": " + (t.message ?: ""))
    }

    private fun state(): JSONObject {
        val chat = requireChat()
        val run = chat.runState.value
        val pending = chat.pendingApproval
        val availability = container.modelAvailability.current
        val lastAnswer = chat.messages.value.filterIsInstance<ChatMessage.Assistant>().lastOrNull()?.text
        return JSONObject()
            .put("ok", true)
            .put("mode", container.settings.mode.id)
            .put("model", container.selectedModel?.displayName ?: JSONObject.NULL)
            .put("modelState", modelState(availability, run))
            .put("running", run.isActive)
            .put("runState", run.javaClass.simpleName)
            .put("pendingApproval", pending?.let {
                JSONObject().put("tool", it.toolName).put("args", cap(it.arguments, ROW_CAP))
            } ?: JSONObject.NULL)
            .put("messages", chat.messages.value.size)
            .put("lastAnswer", lastAnswer?.let { cap(it, ANSWER_CAP) } ?: JSONObject.NULL)
    }

    private fun modelState(availability: ModelAvailability, run: RunState): String = when {
        run == RunState.LoadingModel -> "loading"
        availability is ModelAvailability.Ready -> "ready"
        availability is ModelAvailability.Loading -> "loading"
        availability is ModelAvailability.Failed -> "failed"
        else -> "none"
    }

    /** Starts a message exactly as the Send button does: the composer text goes through send(). */
    private fun send(text: String): JSONObject {
        val chat = requireChat()
        if (text.isBlank()) return error("send needs text in arg")
        val before = chat.messages.value.size
        val composed = chat.composerText
        chat.onInputChange(text)
        chat.send()
        val started = chat.messages.value.size > before
        // A refused send leaves the composer as the Send button would, so put the user's own draft back.
        if (!started) chat.onInputChange(composed)
        val answer = JSONObject().put("ok", true).put("started", started)
        if (!started) answer.put("reason", "the chat is busy, loading a model, or has no model")
        return answer
    }

    /** Same as confirming New chat with no dialog. Refused while a run is active, as the menu is. */
    private fun newChat(): JSONObject {
        val chat = requireChat()
        if (chat.runState.value.isActive) return error("a run is active")
        worker.launch {
            container.startNewConversation()
            chat.clear()
        }
        return JSONObject().put("ok", true)
    }

    private fun models(): JSONObject {
        val selected = container.selectedModel
        val list = JSONArray()
        for (model in container.models) {
            list.put(
                JSONObject()
                    .put("file", model.uri.lastPathSegment ?: JSONObject.NULL)
                    .put("name", model.displayName)
                    .put("bytes", model.fileSizeBytes)
                    .put("quant", model.quantType ?: JSONObject.NULL)
                    .put("inUse", model.uri == selected?.uri),
            )
        }
        return JSONObject().put("ok", true).put("models", list)
    }

    /** Same as the Use button: loads the model through the container. The outcome comes back as a LOADED line. */
    private fun useModel(name: String): JSONObject {
        val model = container.models.firstOrNull { it.uri.lastPathSegment == name || it.displayName == name }
            ?: return error("no installed model named '$name'; see models")
        worker.launch {
            val outcome = runCatching { container.loadModel(model) }
                .getOrElse { ModelAvailability.Failed(it.javaClass.simpleName + ": " + (it.message ?: "")) }
            val line = JSONObject().put("file", model.uri.lastPathSegment ?: JSONObject.NULL)
            when (outcome) {
                is ModelAvailability.Ready -> line.put("ok", true)
                    .put("name", outcome.displayName)
                    .put("quant", outcome.quantType ?: JSONObject.NULL)
                is ModelAvailability.Failed -> line.put("ok", false).put("reason", outcome.reason)
                else -> line.put("ok", false).put("state", outcome.javaClass.simpleName)
            }
            Log.i(TAG, "LOADED $line")
        }
        return JSONObject().put("ok", true).put("started", true).put("model", model.displayName)
    }

    private fun setMode(value: String): JSONObject {
        val mode = when (value) {
            SettingsMode.SIMPLE.id -> SettingsMode.SIMPLE
            SettingsMode.TECHNICAL.id -> SettingsMode.TECHNICAL
            else -> return error("mode must be simple or technical")
        }
        container.settings.set(CoreSettings.MODE, mode.id)
        return JSONObject().put("ok", true).put("mode", mode.id)
    }

    /** Answers the pending approval. The same call the Allow and Deny buttons make; RiskPolicy already staged it. */
    private fun approve(value: String): JSONObject {
        val allow = when (value) {
            "allow" -> true
            "deny" -> false
            else -> return error("approve needs allow or deny")
        }
        val chat = requireChat()
        val pending = chat.pendingApproval ?: return error("no approval is pending")
        chat.resolveConfirmation(allow)
        return JSONObject().put("ok", true).put("tool", pending.toolName).put("approved", allow)
    }

    private fun transcript(): JSONObject {
        val rows = JSONArray()
        for (message in requireChat().messages.value.takeLast(TRANSCRIPT_ROWS)) {
            rows.put(
                when (message) {
                    is ChatMessage.User -> JSONObject().put("role", "user").put("text", cap(message.text, ROW_CAP))
                    is ChatMessage.Assistant -> JSONObject().put("role", "assistant").put("text", cap(message.text, ROW_CAP))
                    is ChatMessage.Notice -> JSONObject().put("role", "notice").put("text", cap(message.text, ROW_CAP))
                    is ChatMessage.Transcript -> JSONObject().put("role", "transcript")
                        .put("title", message.title)
                        .put("running", message.running)
                        .put("text", cap(message.text, ROW_CAP))
                    is ChatMessage.ToolStep -> JSONObject().put("role", "tool")
                        .put("tool", message.toolName)
                        .put("success", message.success)
                        .put("text", cap(message.observation ?: message.detail, ROW_CAP))
                    is ChatMessage.Approval -> JSONObject().put("role", "approval")
                        .put("tool", message.toolName)
                        .put("approved", message.approved)
                },
            )
        }
        return JSONObject().put("ok", true).put("rows", rows)
    }

    private fun requireChat(): ChatViewModel =
        chat ?: throw IllegalStateException("no chat screen yet; open the app once after it starts")

    private fun error(reason: String): JSONObject = JSONObject().put("ok", false).put("error", reason)

    private fun cap(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "...[cut ${text.length - limit} chars]"
}

/**
 * The DONE line: one JSON object per run, with null for anything the run did not measure.
 *
 * WHY nulls: GenerationResult defaults its numbers to zero when a backend does not
 * report them, so a zero here means "not reported" and is shown as null. Tokens reused
 * and first-token time are not carried by the metrics at all, so they are always null.
 */
private fun doneLine(
    result: AgentResult,
    trace: List<StepTrace>,
    controllerMetrics: RunMetrics?,
    generations: List<GenerationRecord>?,
): String {
    val root = JSONObject()
    when (result) {
        is AgentResult.Success -> root.put("outcome", "answer")
        is AgentResult.Stop -> root.put("outcome", "failed")
        AgentResult.StepLimitReached -> root.put("outcome", "step_limit")
        AgentResult.Cancelled -> root.put("outcome", "cancelled")
        is AgentResult.AwaitingConfirmation -> root.put("outcome", "awaiting_approval")
    }
    root.put("reason", (result as? AgentResult.Stop)?.reason ?: JSONObject.NULL)
    val answer = (result as? AgentResult.Success)?.text
    root.put("answer", answer ?: JSONObject.NULL)
    root.put("answerCut", false)
    root.put("total_ms", controllerMetrics?.totalMs ?: JSONObject.NULL)

    val tools = JSONArray()
    var toolCount = 0
    trace.forEachIndexed { index, step ->
        if (step.kind != StepTrace.Kind.TOOL_CALL) return@forEachIndexed
        toolCount++
        if (tools.length() >= TOOL_CAP) return@forEachIndexed
        // A call the runtime refused has no observation after it, and no time either.
        val executed = trace.getOrNull(index + 1)?.kind == StepTrace.Kind.OBSERVATION
        val args = step.toolArgsJson
        tools.put(
            JSONObject()
                .put("name", step.toolName ?: step.detail)
                .put("success", step.success)
                .put("ms", if (executed) step.durationMs else JSONObject.NULL)
                .put("args", if (args != null && args.length <= ARGS_CAP) JSONObject(args) else JSONObject.NULL)
                .put("argsCut", args != null && args.length > ARGS_CAP),
        )
    }
    root.put("tools", tools)
    if (toolCount > TOOL_CAP) root.put("toolsCut", toolCount - TOOL_CAP)
    // Arguments are the first thing dropped when the line is too long: the answer is trimmed below, and a
    // run that cannot be checked is reported as unverified, never as a pass.
    if (root.toString().length > LINE_LIMIT) {
        for (i in 0 until tools.length()) {
            tools.getJSONObject(i).put("args", JSONObject.NULL).put("argsCut", true)
        }
    }

    if (generations == null) {
        root.put("generations", JSONObject.NULL)
    } else {
        val rows = JSONArray()
        for (g in generations.take(GENERATION_CAP)) rows.put(generationRow(g))
        root.put("generations", rows)
        if (generations.size > GENERATION_CAP) root.put("generationsCut", generations.size - GENERATION_CAP)
    }

    // The line has a size limit. Trim the answer, never the numbers, until it fits.
    var line = root.toString()
    if (answer != null && line.length > LINE_LIMIT) {
        val overflow = line.length - LINE_LIMIT
        val keep = (answer.length - overflow - 40).coerceAtLeast(0).coerceAtMost(ANSWER_CAP)
        root.put("answer", answer.take(keep))
        root.put("answerCut", true)
        root.put("answerChars", answer.length)
        line = root.toString()
    } else if (answer != null && answer.length > ANSWER_CAP) {
        root.put("answer", answer.take(ANSWER_CAP))
        root.put("answerCut", true)
        root.put("answerChars", answer.length)
        line = root.toString()
    }
    return line
}

private fun generationRow(g: GenerationRecord): JSONObject {
    val decodeTokPerS = if (g.completionTokens > 0 && g.decodeMs > 0) {
        Math.round(g.completionTokens * 1000.0 / g.decodeMs * 100.0) / 100.0
    } else {
        null
    }
    return JSONObject()
        .put("promptTokens", g.promptTokens.nonZero())
        // The metrics do not carry the prompt-prefix reuse count, so the tokens actually prefilled are unknown.
        // That makes prefill tok/s unknown too.
        .put("tokensReused", JSONObject.NULL)
        .put("prefillMs", g.prefillMs.takeIf { it > 0 } ?: JSONObject.NULL)
        .put("prefillTokPerS", JSONObject.NULL)
        // Not measured by the run: the generation result has no time-to-first-token field.
        .put("firstTokenMs", JSONObject.NULL)
        .put("decodeTokens", g.completionTokens.nonZero())
        .put("decodeTokPerS", decodeTokPerS ?: JSONObject.NULL)
        .put("stop", g.stopReason.name)
}

private fun Int.nonZero(): Any = if (this > 0) this else JSONObject.NULL
