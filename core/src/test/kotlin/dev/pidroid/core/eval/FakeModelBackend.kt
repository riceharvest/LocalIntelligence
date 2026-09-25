package dev.pidroid.core.eval

import dev.pidroid.core.model.ChatMessage
import dev.pidroid.core.model.GenerationRequest
import dev.pidroid.core.model.GenerationResult
import dev.pidroid.core.model.ModelBackend
import dev.pidroid.core.model.ModelCapabilities
import dev.pidroid.core.model.ModelSpec
import dev.pidroid.core.model.SamplingParams
import dev.pidroid.core.model.StopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A scripted [ModelBackend]. Returns queued outputs in order and records
 * everything it was asked, so a task can assert on the prompt it received.
 *
 * Deterministic by construction: no RNG, no clock, no threads, no I/O. Given the
 * same script it returns byte-identical results, forever.
 *
 * Synthetic-but-fixed timings: 1 ms per prompt token and 1 ms per output token.
 * Real timings come from a real backend; fakes must not pretend to be llama.cpp.
 */
class FakeModelBackend(
    private val script: List<String>,
    override val id: String = "fake",
    override val capabilities: ModelCapabilities = ModelCapabilities(
        contextLength = 4096,
        supportsToolCalling = true,
        supportsGrammar = true,
        supportsVision = false,
        supportsKvCache = false,
    ),
) : ModelBackend {

    /** Every request the loop made, in order. */
    val requests: MutableList<GenerationRequest> = mutableListOf()

    /** Rendered prompts, in order. Lets a task assert on what the model saw. */
    val prompts: MutableList<String> = mutableListOf()

    /** Generations performed. Equal to the loop's step count. */
    val generationCount: Int get() = requests.size

    /** Running total of prompt tokens across every generation. */
    var promptTokensTotal: Int = 0
        private set

    /** Running total of generated (completion) tokens. The secondary metric's denominator. */
    var outputTokensTotal: Int = 0
        private set

    /** Observes every request as it arrives. Used by tasks that assert on prompts. */
    var onRequest: ((request: GenerationRequest, index: Int) -> Unit)? = null

    private var cursor = 0
    private var loaded = false
    private var cancelled = false

    /** True when the loop asked for more generations than the script had. */
    val exhausted: Boolean get() = cursor >= script.size

    /** How many times the script ran dry. Must be 0 in a healthy suite. */
    var exhaustionCount: Int = 0
        private set

    override suspend fun load(model: ModelSpec) { loaded = true; cancelled = false }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        if (cancelled) return GenerationResult(text = "", stopReason = StopReason.CANCELLED)

        requests += request
        val rendered = request.render()
        prompts += rendered
        onRequest?.invoke(request, requests.size - 1)

        val raw = if (cursor < script.size) {
            script[cursor].also { cursor++ }
        } else {
            exhaustionCount++
            // Deterministic fallback. A real model would ramble; a fake says so
            // plainly, which makes an exhausted script a loud test failure rather
            // than a mystery.
            RESPOND_PREFIX + "I am out of scripted steps."
        }

        val promptTokens = request.messages.sumOf { countTokens(renderMessage(it)) }.coerceAtLeast(1)
        val completionTokens = countTokens(raw)
        promptTokensTotal += promptTokens
        outputTokensTotal += completionTokens
        return GenerationResult(
            text = raw,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            stopReason = StopReason.COMPLETED,
            prefillMs = promptTokens.toLong(),
            decodeMs = completionTokens.toLong(),
        )
    }

    override suspend fun unload() { loaded = false }

    /** 4 chars per token, floor 1. Same heuristic as NoopModelBackend: stable, cheap. */
    override fun countTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    override fun cancel() { cancelled = true }

    companion object {
        const val RESPOND_PREFIX = "{\"action\":\"respond\",\"text\":\""
        const val CALL_PREFIX = "{\"action\":\"call\",\"name\":\""

        /**
         * The eval action protocol. Deliberately the *same* JSON shape a real
         * model is constrained to emit by the grammar, so the parser under test
         * is the parser that will be used in production.
         */
        fun respond(text: String): String =
            JsonObject(
                mapOf(
                    "action" to JsonPrimitive("respond"),
                    "text" to JsonPrimitive(text),
                )
            ).toString()

        fun callTool(name: String, args: JsonObject = EMPTY_ARGS): String =
            JsonObject(
                mapOf(
                    "action" to JsonPrimitive("call"),
                    "name" to JsonPrimitive(name),
                    "args" to args,
                )
            ).toString()

        /**
         * Deterministic parse-failure bait. Looks like prose, is not the
         * protocol, so the parser must reject it without throwing.
         */
        fun garbage(): String =
            "Sure! I looked it up and it seems like the value is probably around " +
                "forty-something, give or take. Let me know if you want more detail."

        private val EMPTY_ARGS = buildJsonObject { }
    }
}

/** Renders one message to the text the model would actually see. */
fun renderMessage(message: ChatMessage): String = when (message) {
    is ChatMessage.System -> message.text
    is ChatMessage.User -> message.text
    is ChatMessage.Assistant -> message.text
    is ChatMessage.ToolObservation ->
        "${message.toolName} -> ${message.observation}"
}

/**
 * Full flattened prompt. Tasks assert on this, so it must be stable and total —
 * a prompt assertion that passes on a good day is worse than no prompt assertion.
 */
fun GenerationRequest.render(): String = buildString {
    appendLine("allowedTools=" + allowedToolNames.joinToString(","))
    appendLine("grammar=" + (grammar ?: "<none>"))
    appendLine("params=" + SamplingParams().toString())
    messageList().forEach { appendLine(renderMessage(it)) }
}

private fun GenerationRequest.messageList(): List<ChatMessage> = messages
