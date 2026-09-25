package dev.localintelligence.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * A model the agent can be pointed at. Deliberately backend-agnostic:
 * no llama.cpp types, no LiteRT types, no file paths assumed.
 */
data class ModelSpec(
    val id: String,
    val displayName: String,
    /** Bytes on disk, when known (from GGUF metadata at import time). */
    val sizeBytes: Long = 0,
    val parameterCount: Long? = null,
    val quantType: String? = null,
    /** Backend ids that declare they can run this model. */
    val supportedBackends: Set<String> = emptySet(),
)

data class ModelCapabilities(
    val contextLength: Int,
    val supportsToolCalling: Boolean,
    val supportsGrammar: Boolean,
    val supportsVision: Boolean,
    val supportsKvCache: Boolean,
) {
    companion object {
        val UNKNOWN = ModelCapabilities(
            contextLength = 0,
            supportsToolCalling = false,
            supportsGrammar = false,
            supportsVision = false,
            supportsKvCache = false,
        )
    }
}

/** One message in the conversation. Plain data, no backend types. */
sealed interface ChatMessage {
    data class System(val text: String) : ChatMessage
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String) : ChatMessage
    /**
     * A tool result fed back to the model. [observation] is the ONLY part the
     * model ever sees — it is already truncated and model-facing.
     */
    data class ToolObservation(
        val toolName: String,
        val observation: String,
        val success: Boolean,
    ) : ChatMessage
}

data class SamplingParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val seed: Int = -1,
    val maxOutputTokens: Int = 512,
)

/**
 * Constrained-generation request. [grammar] is a backend-neutral GBNF/JSON-schema
 * constraint; backends that cannot honour it must report
 * capabilities.supportsGrammar = false so the runtime can fall back.
 */
data class GenerationRequest(
    val messages: List<ChatMessage>,
    val params: SamplingParams = SamplingParams(),
    val grammar: String? = null,
    /** Tool names the grammar may choose from. Empty for a plain completion. */
    val allowedToolNames: List<String> = emptyList(),
)

data class GenerationResult(
    val text: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val stopReason: StopReason = StopReason.COMPLETED,
    val prefillMs: Long = 0,
    val decodeMs: Long = 0,
) {
    val decodeTokensPerSecond: Double
        get() = if (decodeMs > 0) completionTokens * 1000.0 / decodeMs else 0.0
}

enum class StopReason {
    COMPLETED,
    MAX_TOKENS,
    CANCELLED,
    ERROR,
}

/**
 * The only inference surface the agent core knows about.
 *
 * Implementations MUST be cancelable and MUST NOT throw on generation error;
 * report failures through [GenerationResult.stopReason] instead.
 */
interface ModelBackend {
    val id: String
    val capabilities: ModelCapabilities

    suspend fun load(model: ModelSpec)
    suspend fun generate(request: GenerationRequest): GenerationResult
    suspend fun unload()

    /** Best-effort token count. Used for context budgeting. */
    fun countTokens(text: String): Int

    /** Cooperative cancellation. Must be safe to call when idle. */
    fun cancel()
}

/** A backend that can stream tokens. Optional capability, not required. */
interface StreamingModelBackend : ModelBackend {
    suspend fun generateStreaming(
        request: GenerationRequest,
        onToken: (String) -> Unit,
    ): GenerationResult
}

/** No-op backend for tests and for the first end-to-end vertical slice. */
class NoopModelBackend(
    override val id: String = "noop",
    override val capabilities: ModelCapabilities = ModelCapabilities.UNKNOWN,
) : ModelBackend {
    override suspend fun load(model: ModelSpec) = Unit
    override suspend fun generate(request: GenerationRequest) =
        GenerationResult(text = "", stopReason = StopReason.COMPLETED)

    override suspend fun unload() = Unit
    override fun countTokens(text: String): Int = text.length / 4
    override fun cancel() = Unit
}

/** Marker for a backend-agnostic structured payload, used by tools. */
typealias ToolPayload = JsonElement
typealias ToolArgs = JsonObject
