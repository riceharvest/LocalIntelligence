package dev.localintelligence.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.localintelligence.android.tools.phonesearch.PhoneSearchLink
import dev.localintelligence.app.R
import dev.localintelligence.core.phoneindex.PhoneIndexSource

// =====================================================================================
// One row under a phone.search answer: a chip per hit that opens the original item (the event, the
// contact, the file, the photo, the text, or the app that posted the notification). Tapping a chip
// does nothing on a phone with no app for that item.
// =====================================================================================

@Composable
fun PhoneHitsRow(links: List<PhoneSearchLink>) {
    val context = LocalContext.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    ) {
        // Index keys, not labels: two hits can have the same label (two texts from one sender).
        itemsIndexed(links) { _, link ->
            AssistChip(
                onClick = { openItem(context, link) },
                label = { Text("${stringResource(sourceTitleRes(link.source))}: ${link.detail}", maxLines = 1) },
            )
        }
    }
}

/** The name of a source, the same one the Search my phone screen uses. */
private fun sourceTitleRes(source: PhoneIndexSource): Int = when (source) {
    PhoneIndexSource.NOTIFICATIONS -> R.string.phone_search_notifications
    PhoneIndexSource.MESSAGES -> R.string.phone_search_messages
    PhoneIndexSource.CALENDAR -> R.string.phone_search_calendar
    PhoneIndexSource.CONTACTS -> R.string.phone_search_contacts
    PhoneIndexSource.FILES -> R.string.phone_search_files
    PhoneIndexSource.PHOTOS -> R.string.phone_search_photos
}

/** Opens the item's app. A phone with no app for it does nothing. */
private fun openItem(context: Context, link: PhoneSearchLink) {
    try {
        context.startActivity(link.intent)
    } catch (e: ActivityNotFoundException) {
        // No app takes this item on this phone. Nothing else to offer.
    } catch (e: SecurityException) {
        // The app refused the link (a permission was revoked since the search). Nothing else to offer.
    }
}
