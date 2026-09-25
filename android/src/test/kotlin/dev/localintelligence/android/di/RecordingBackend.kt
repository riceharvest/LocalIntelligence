package dev.localintelligence.android.di

import dev.localintelligence.core.model.GenerationRequest
import dev.localintelligence.core.model.GenerationResult
import dev.localintelligence.core.model.ModelBackend
import dev.localintelligence.core.model.ModelCapabilities
import dev.localintelligence.core.model.ModelSpec
import dev.localintelligence.core.model.StopReason

/**
 * A [ModelBackend] that records what it was asked to do.
 *
 * WHY not a mock: the property this test suite exists to protect is *"the graph
 * does not load a model while it is being constructed"*, and that is a claim
 * about a real sequence of calls on a real object. A mock asserting
 * `verifyNoInteractions` would pass even if the graph had loaded the model
 * through some path the mock did not intercept. Counting actual [load] calls on
 * the backend the graph holds cannot be fooled that way.
 */
internal class RecordingBackend(
    private val failOnLoad: Boolean = false,
    private val reply: String = "<respond>done</respond>",
) : ModelBackend {

    override val id: String = "recording"
    override val capabilities: ModelCapabilities = ModelCapabilities(
        contextLength = 4096,
        supportsToolCalling = true,
        supportsGrammar = false,
        supportsVision = false,
        supportsKvCache = true,
    )

    var loadCount: Int = 0
        private set
    var unloadCount: Int = 0
        private set
    var generateCount: Int = 0
        private set
    val loaded: MutableList<ModelSpec> = mutableListOf()

    override suspend fun load(model: ModelSpec) {
        loadCount++
        if (failOnLoad) error("no native library")
        loaded += model
    }

    override suspend fun generate(request: GenerationRequest): GenerationResult {
        generateCount++
        return GenerationResult(text = reply, stopReason = StopReason.COMPLETED)
    }

    override suspend fun unload() {
        unloadCount++
    }

    override fun countTokens(text: String): Int = (text.length + 3) / 4

    override fun cancel() = Unit
}

/**
 * A backend whose `load` throws an [Error], not an [Exception].
 *
 * Models the JNI and OOM paths, which do not produce exceptions. A graph that
 * caught only `Exception` would let these straight through to the caller's
 * coroutine scope, which is the difference between a user-facing message and a
 * dead app.
 */
internal class ThrowingBackend : ModelBackend {
    override val id: String = "throwing"
    override val capabilities: ModelCapabilities = ModelCapabilities.UNKNOWN

    override suspend fun load(model: ModelSpec): Unit =
        throw OutOfMemoryError("native allocation failed")

    override suspend fun generate(request: GenerationRequest) =
        GenerationResult(text = "", stopReason = StopReason.ERROR)

    override suspend fun unload() = Unit
    override fun countTokens(text: String): Int = 0
    override fun cancel() = Unit
}
