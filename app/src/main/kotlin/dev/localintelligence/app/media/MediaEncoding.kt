package dev.localintelligence.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.localintelligence.core.model.MediaInput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pictures are re-encoded before they reach the model. A raw photo is 4000 px
 * on its longer side, and the model's vision encoder resizes it anyway, so a
 * larger file only spends memory and prompt tokens on pixels nobody reads.
 */
internal const val PICTURE_MAX_SIDE_PX = 1024
internal const val PICTURE_JPEG_QUALITY = 85
private const val THUMBNAIL_MAX_SIDE_PX = 256
private const val THUMBNAIL_JPEG_QUALITY = 80

/**
 * Decodes a photo the user picked, applies its EXIF rotation, and re-encodes it.
 *
 * Call this off the main thread: it reads the whole file twice (bounds, then
 * pixels) and does the scaling and compression. Returns null for anything that
 * does not decode as an image.
 *
 * WHY BITMAPFACTORY AND NOT IMAGEDECODER: the app supports API 26, and
 * ImageDecoder only exists from 28. BitmapFactory with the platform ExifInterface
 * gives the same orientation result on every supported API without a dependency.
 */
fun encodePickedPicture(resolver: ContentResolver, uri: Uri): PendingAttachment? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val decode = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, PICTURE_MAX_SIDE_PX)
    }
    val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
        ?: return null
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    return pictureFrom(rotateByExif(decoded, orientation))
}

/**
 * A screen capture from the assistant, which is already a bitmap. Only the size
 * and the encoding remain.
 */
fun encodeBitmapPicture(bitmap: Bitmap): PendingAttachment {
    // A hardware bitmap cannot be drawn on or compressed; copy it to memory first.
    val software = if (bitmap.config == Bitmap.Config.HARDWARE) {
        requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
    } else {
        bitmap
    }
    return pictureFrom(software)
}

private fun pictureFrom(source: Bitmap): PendingAttachment {
    val full = scaleToLongerSide(source, PICTURE_MAX_SIDE_PX)
    val jpeg = jpegOf(full, PICTURE_JPEG_QUALITY)
    val thumbnail = jpegOf(scaleToLongerSide(full, THUMBNAIL_MAX_SIDE_PX), THUMBNAIL_JPEG_QUALITY)
    return PendingAttachment(MediaInput(jpeg, "image/jpeg"), ShownAttachment.Picture(thumbnail))
}

/**
 * The power-of-two sample size that keeps the decode at or above [maxSide]. Asking
 * BitmapFactory for a smaller decode first keeps a 48 MP photo from ever existing
 * as a full-size bitmap.
 */
private fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}

/** Applies the EXIF orientation so the picture is upright for the model. */
private fun rotateByExif(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun scaleToLongerSide(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longer = maxOf(bitmap.width, bitmap.height)
    if (longer <= maxSide) return bitmap
    val factor = maxSide.toFloat() / longer
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * factor).roundToInt().coerceAtLeast(1),
        (bitmap.height * factor).roundToInt().coerceAtLeast(1),
        true,
    )
}

private fun jpegOf(bitmap: Bitmap, quality: Int): ByteArray {
    // JPEG has no alpha channel. Flatten onto white, or transparent pixels come out black.
    val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    val out = ByteArrayOutputStream()
    flat.compress(Bitmap.CompressFormat.JPEG, quality, out)
    flat.recycle()
    return out.toByteArray()
}

/**
 * Wraps raw 16-bit little-endian PCM in a 44-byte RIFF/WAVE header.
 *
 * The header is the whole format: a WAV is this header followed by the samples,
 * so no encoder dependency is needed for the one format the mic produces.
 */
fun wavOf(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray {
    val bitsPerSample = 16
    val blockAlign = channels * bitsPerSample / 8
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcm.size)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)                       // PCM fmt chunk length
        putShort(1)                      // PCM, uncompressed
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(sampleRate * blockAlign)  // byte rate
        putShort(blockAlign.toShort())
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcm.size)
    }
    return header.array() + pcm
}
