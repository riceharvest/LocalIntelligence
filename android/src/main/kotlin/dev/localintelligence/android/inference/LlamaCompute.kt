package dev.localintelligence.android.inference

/**
 * Where llama.cpp runs a model.
 *
 * [gpuBackend] null is the CPU. Otherwise it names a ggml GPU backend, "Vulkan"
 * (Adreno, Mali, PowerVR, Xclipse) or "OpenCL" (Adreno), and [gpuLayers] of the
 * model's layers go to that backend's GPU, the rest stay on the CPU.
 *
 * Neither is assumed to be faster. On a phone the CPU and GPU share one memory
 * bus, and decode is bound by it, so which one wins depends on the chip, the
 * driver and the quant. The speed test measures them and Auto uses its winner.
 */
data class LlamaCompute(
    val gpuBackend: String? = null,
    val gpuLayers: Int = ALL_LAYERS,
) {
    val isGpu: Boolean get() = gpuBackend != null

    /** "CPU", or "GPU (Vulkan)". What the speed test and the settings screen show. */
    val label: String get() = gpuBackend?.let { "GPU ($it)" } ?: "CPU"

    companion object {
        val CPU = LlamaCompute()

        /** llama.cpp reads a negative layer count as "every layer". */
        const val ALL_LAYERS = -1
    }
}

/**
 * Decides [LlamaCompute] for each llama.cpp load, and hears how the load went.
 *
 * Asked at every load rather than fixed at construction, so a settings change or
 * a new speed-test winner applies from the next load.
 */
interface LlamaComputeSource {
    /** Where the next load should run. */
    fun forNextLoad(): LlamaCompute

    /** A load on [compute] is about to start. A GPU driver can kill the process in there. */
    fun loadStarting(compute: LlamaCompute) {}

    /** The load on [compute] returned, with [error] null on success. */
    fun loadReturned(compute: LlamaCompute, error: String?) {}

    companion object {
        val CPU_ONLY: LlamaComputeSource = object : LlamaComputeSource {
            override fun forNextLoad(): LlamaCompute = LlamaCompute.CPU
        }
    }
}
