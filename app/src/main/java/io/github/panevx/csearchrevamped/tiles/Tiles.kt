package io.github.panevx.csearchrevamped.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.panevx.csearchrevamped.CaptureService
import io.github.panevx.csearchrevamped.MainActivity
import io.github.panevx.csearchrevamped.MusicActivity

@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.launch(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}

private fun TileService.markInactive() {
    qsTile?.apply {
        state = Tile.STATE_INACTIVE
        updateTile()
    }
}

class CaptureTileService : TileService() {
    override fun onStartListening() = markInactive()

    override fun onClick() {
        val service = CaptureService.instance
        if (service != null) service.capture(delayMs = 450, dismissShade = true)
        else launch(Intent(this, MainActivity::class.java))
    }
}

class MusicTileService : TileService() {
    override fun onStartListening() = markInactive()

    override fun onClick() = launch(Intent(this, MusicActivity::class.java))
}
