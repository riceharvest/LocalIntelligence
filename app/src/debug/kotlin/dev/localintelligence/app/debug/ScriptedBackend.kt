package dev.localintelligence.app.debug

import dev.localintelligence.core.model.ChatMessage
import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.StopReason
import dev.localintelligence.core.model.StreamingModelBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * Plays scripted replies in place of the model, for the emulator tests (debug builds only).
 *
 * WHY: some states cannot be reached with a real model except by luck: a tool that fails, a run that hits
 * its step limit, a loop, an empty reply, an answer that streams for a minute so the test can press Stop or
 * turn the screen off in the middle of it. A script makes each of them happen every time. Everything else
 * about the run (the tools, the policy, the approvals, the transcript, the stop and retry paths) is the
 * app's real code; only the text the model would have produced is replaced.
 *
 * One entry is used per generation, in order. When the script is used up the real model answers. Entries:
 *  - plain text: the reply as the model would write it (`<tool name="x">{...}</tool>` or `<respond>..</respond>`)
 *  - `@empty`: an empty reply
 *  - `@echo`: a `<respond>` that repeats the start of the latest tool result, so a search result reaches the answer
 *  - `@slow:MS:text`: the text streamed one character every MS milliseconds, until cancelled
 *  - `@real`: the real model for this one generation
 */
class ScriptedBackend(
    private val real: ModelBackend,
    private val next: () -> String?,
) : StreamingModelBackend, ModelBackend by real {

    private val cancelled = AtomicBoolean(false)

    override fun cancel() {
        cancelled.set(true)
        real.cancel()
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult = run(request, null)

    override suspend fun generateStreaming(request: GenerationRequest, onToken: (String) -> Unit): GenerationResult =
        run(request, onToken)

    private suspend fun run(request: GenerationRequest, onToken: ((String) -> Unit)?): GenerationResult {
        cancelled.set(false)
        val entry = next()
        if (entry == null || entry == "@real") return realGenerate(request, onToken)
        val text = when {
            entry == "@empty" -> ""
            entry == "@echo" -> "<respond>" + echoed(request) + "</respond>"
            entry.startsWith("@slow:") -> {
                val ms = entry.removePrefix("@slow:").substringBefore(':').toLongOrNull() ?: 50L
                val body = entry.removePrefix("@slow:").substringAfter(':')
                val sb = StringBuilder()
                for (ch in body) {
                    if (cancelled.get()) return GenerationResult(sb.toString(), stopReason = StopReason.CANCELLED)
                    coroutineContext.ensureActive()
                    sb.append(ch)
                    onToken?.invoke(ch.toString())
                    delay(ms)
                }
                return GenerationResult(sb.toString(), completionTokens = body.length / 4)
            }
            else -> entry
        }
        onToken?.invoke(text)
        return GenerationResult(text, completionTokens = text.length / 4)
    }

    private suspend fun realGenerate(request: GenerationRequest, onToken: ((String) -> Unit)?): GenerationResult {
        val streaming = real as? StreamingModelBackend
        return if (onToken != null && streaming != null) streaming.generateStreaming(request, onToken) else real.generate(request)
    }

    /** The first 200 characters of the newest tool result in the prompt, as a plain sentence. */
    private fun echoed(request: GenerationRequest): String {
        val last = request.messages.lastOrNull { it is ChatMessage.ToolObservation } as? ChatMessage.ToolObservation
            ?: return "Nothing came back."
        return ("Result: " + last.observation).replace(Regex("\\s+"), " ").take(200).replace("<", "(").replace(">", ")")
    }
}
