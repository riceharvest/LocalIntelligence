package dev.localintelligence.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that opens the chat from anywhere on the phone.
 *
 * WHY startActivityAndCollapse AND NOT startActivity: a tile sits in the shade,
 * and a plain start leaves the shade open over the new screen. The collapse
 * variant closes it as part of the same transition.
 *
 * WHY TWO OVERLOADS: API 34 deprecated the Intent form and requires a
 * PendingIntent. minSdk is 26, so both paths are live and the branch is the
 * only correct way to support them.
 */
class AssistantTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
