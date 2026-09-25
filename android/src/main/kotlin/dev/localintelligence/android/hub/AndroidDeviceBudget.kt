package dev.localintelligence.android.hub

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import dev.localintelligence.core.hub.DeviceBudget
import java.io.File

/**
 * The real device's memory and storage, for [DeviceBudget].
 *
 * ## Why the RAM number is 55% of physical and not more
 *
 * An app that gets OOM-killed mid-conversation is worse than an app that
 * declined a model, so this deliberately leaves a lot on the table. The other
 * consumer of that RAM is the system: the kernel page cache, the compositor,
 * and every other app the user has open. A model that consumes 90% of physical
 * RAM is unloadable in practice even though the arithmetic says it fits.
 *
 * This mirrors `RamEstimate.usableDeviceBytes()` in the existing
 * `ModelImporter`, deliberately: a file that the downloader accepted and then
 * the loader refuses is the worst outcome, and two different numbers would make
 * that likely.
 */
class AndroidDeviceBudget(
    context: Context,
    private val modelsDir: File,
) : DeviceBudget {

    private val appContext = context.applicationContext

    override fun availableRamBytes(): Long {
        val physical = totalRamBytes()
        if (physical <= 0L) return FALLBACK_RAM_BYTES
        return (physical * USABLE_FRACTION).toLong()
    }

    override fun totalRamBytes(): Long {
        val fromActivityManager = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        }.getOrNull() ?: 0L
        if (fromActivityManager > 0L) return fromActivityManager
        // WHY the /proc fallback: some OEM builds restrict the ActivityManager
        // call in the background, and a download can legitimately start from a
        // notification action with the app not in the foreground.
        return runCatching {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemTotal:") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)
                    ?.toLongOrNull()?.times(1024)
            } ?: 0L
        }.getOrDefault(0L)
    }

    override fun freeDiskBytes(): Long {
        val dir = if (modelsDir.exists()) modelsDir else modelsDir.parentFile ?: return 0L
        return runCatching {
            val stat = StatFs(dir.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                stat.availableBytes
            } else {
                @Suppress("DEPRECATION")
                stat.availableBlocks.toLong() * stat.blockSize.toLong()
            }
        }.getOrDefault(0L)
    }

    companion object {
        /**
         * WHY 0.55: the system and every other app need the rest. See the class
         * KDoc. This matches `RamEstimate.usableDeviceBytes()`.
         */
        const val USABLE_FRACTION = 0.55

        /**
         * Used only when every route to the real figure failed. 4 GB is a
         * conservative guess that refuses a 7B model rather than inviting an
         * OOM.
         */
        const val FALLBACK_RAM_BYTES = 4L * 1024 * 1024 * 1024
    }
}
