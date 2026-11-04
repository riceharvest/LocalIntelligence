package dev.localintelligence.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localintelligence.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ===========================================================================
// Thumbnails of the photos a photos.search found, drawn under the answer. Tapping one opens the
// photo in the phone's own viewer, through the same MediaStore URI the search read. Full build only
// in practice: only that build's tool fills the list.
// ===========================================================================

/** Side of one thumbnail, in dp. Small: the answer is text, and the photo is one tap away. */
private val THUMB_SIZE = 72.dp

/** Longest side, in pixels, a thumbnail is decoded at. */
private const val THUMB_PX = 256

@Composable
fun PhotoHitsRow(uris: List<String>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    ) {
        items(uris, key = { it }) { uri -> PhotoThumb(uri) }
    }
}

@Composable
private fun PhotoThumb(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, Uri.parse(uri))?.asImageBitmap() }
    }
    val description = stringResource(R.string.photo_from_your_search)
    Box(
        modifier = Modifier
            .size(THUMB_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { openPhoto(context, uri) },
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(THUMB_SIZE),
            )
        }
    }
}

/**
 * A small decoded picture of [uri]. API 29+ asks MediaStore for a thumbnail, which also honours
 * the picture's rotation. Older phones decode a sampled copy, and the rotation is not applied there.
 * Returns null when the picture cannot be read: the tile stays blank.
 */
private fun loadThumbnail(context: Context, uri: Uri): Bitmap? = try {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
} catch (e: Exception) {
    null
}

/** Opens the photo in the phone's viewer. A phone with no viewer for it does nothing. */
private fun openPhoto(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No app opens images on this phone. Nothing else to offer.
    }
}
