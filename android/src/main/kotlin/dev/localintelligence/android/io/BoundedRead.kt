package dev.localintelligence.android.io

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads up to [max] bytes, or to the end of the stream, whichever comes first.
 *
 * WHY NOT `InputStream.readNBytes(int)`: it is a Java 11 method, and Android only has it from
 * API 33. The app's minSdk is 26 and core library desugaring is not on, so the call throws
 * `NoSuchMethodError` on Android 8 to 12L. This loop uses only `read(ByteArray, Int, Int)`,
 * which every API level has. Callers ask for one byte more than their cap, so an oversized
 * input shows up as a result larger than the cap.
 */
internal fun InputStream.readUpTo(max: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(max, 64 * 1024))
    val chunk = ByteArray(8 * 1024)
    while (out.size() < max) {
        val n = read(chunk, 0, minOf(chunk.size, max - out.size()))
        if (n < 0) break
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}
