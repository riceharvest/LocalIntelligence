package dev.localintelligence.app.settings

import dev.localintelligence.android.inference.LlamaBridge
import dev.localintelligence.android.inference.LlamaCompute
import dev.localintelligence.android.inference.LlamaComputeSource
import dev.localintelligence.core.settings.CoreSettings
import dev.localintelligence.core.settings.SettingDef
import dev.localintelligence.core.settings.SettingSection
import java.io.File

/**
 * Where llama.cpp runs each load: the Compute setting, the speed test's winner
 * for Auto, and a guard against a GPU driver that takes the process down.
 *
 * ## The crash guard
 *
 * A driver bug in a GPU load is a native crash, not an error: the process dies,
 * and the next launch loads the same model on the same GPU again. So [marker] is
 * written before every GPU load and deleted when the load returns. A marker found
 * at the next load means that GPU load never returned. That backend is then
 * blocked: Auto and the speed test skip it, and a Compute setting pinned to it is
 * set back to Auto, a change the user sees on the settings screen. Pinning that
 * GPU again is an explicit retry, and a load on it that returns lifts the block.
 */
class ComputeChoice(
    private val settings: SettingsStore,
    private val marker: File,
) : LlamaComputeSource {

    override fun forNextLoad(): LlamaCompute {
        recoverFromCrash()
        val backend = when (val choice = settings[CoreSettings.COMPUTE]) {
            CoreSettings.COMPUTE_CPU -> null
            CoreSettings.COMPUTE_AUTO -> settings.stored(AUTO_WINNER).takeIf { it.isNotEmpty() && !isBlocked(it) }
            else -> BACKENDS[choice]
        }
        return backend?.let { LlamaCompute(it, gpuLayers()) } ?: LlamaCompute.CPU
    }

    override fun loadStarting(compute: LlamaCompute) {
        compute.gpuBackend?.let { runCatching { marker.writeText(it) } }
    }

    override fun loadReturned(compute: LlamaCompute, error: String?) {
        val backend = compute.gpuBackend ?: return
        marker.delete()
        if (error == null && isBlocked(backend)) {
            settings.set(GPU_BLOCKED, blocked().minus(backend).joinToString(","))
        }
    }

    /** True when Compute is Auto, the only case where the speed test picks the winner. */
    val isAuto: Boolean get() = settings[CoreSettings.COMPUTE] == CoreSettings.COMPUTE_AUTO

    /**
     * What Auto chooses between: the CPU and every GPU found here that has not
     * crashed a load. Listing the GPUs starts their drivers, so this is only
     * called from the speed test.
     */
    fun candidates(): List<LlamaCompute> {
        if (BACKENDS.values.all(::isBlocked)) return listOf(LlamaCompute.CPU)
        val layers = gpuLayers()
        // Listing starts the GPU drivers, which can crash the process like a load
        // can, so it runs under the marker too: a crash here blocks both backends.
        runCatching { marker.writeText(BACKENDS.values.joinToString(",")) }
        val devices = LlamaBridge.gpuDevices()
        marker.delete()
        val gpus = devices
            .map { it.backend }
            .distinct()
            .filterNot(::isBlocked)
            .map { LlamaCompute(it, layers) }
        return listOf(LlamaCompute.CPU) + gpus
    }

    /** Stores the speed test's fastest for Auto. */
    fun setAutoWinner(compute: LlamaCompute) {
        settings.set(AUTO_WINNER, compute.gpuBackend.orEmpty())
    }

    private fun gpuLayers(): Int =
        settings[CoreSettings.GPU_LAYERS].let { if (it >= CoreSettings.GPU_LAYERS.max) LlamaCompute.ALL_LAYERS else it }

    private fun recoverFromCrash() {
        if (!marker.exists()) return
        val crashed = runCatching { marker.readText() }.getOrDefault("")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        marker.delete()
        if (crashed.isEmpty()) return
        settings.set(GPU_BLOCKED, (blocked() + crashed).joinToString(","))
        if (BACKENDS[settings.stored(CoreSettings.COMPUTE)] in crashed) {
            settings.set(CoreSettings.COMPUTE, CoreSettings.COMPUTE_AUTO)
        }
    }

    private fun blocked(): Set<String> =
        settings.stored(GPU_BLOCKED).split(',').filter { it.isNotBlank() }.toSet()

    private fun isBlocked(backend: String) = backend in blocked()

    private companion object {
        /** Compute setting id to ggml backend name. */
        val BACKENDS = mapOf(
            CoreSettings.COMPUTE_VULKAN to "Vulkan",
            CoreSettings.COMPUTE_OPENCL to "OpenCL",
        )

        // Bookkeeping, not options: kept in the settings file but never listed
        // in CoreSettings.all, so no screen shows them. Read with stored(), which
        // Simple mode does not override.
        val AUTO_WINNER = SettingDef.Text(
            "model.compute_auto_winner", "Auto compute", "The speed test's fastest; empty is the CPU.",
            SettingSection.MODEL, default = "", maxLength = 16,
        )
        val GPU_BLOCKED = SettingDef.Text(
            "model.gpu_blocked", "Blocked GPUs", "GPU backends whose load crashed the app.",
            SettingSection.MODEL, default = "", maxLength = 64,
        )
    }
}
