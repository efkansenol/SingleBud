package app.singlebud

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/** Quick Settings tile that toggles mono audio. */
class MonoTileService : TileService() {

    override fun onStartListening() {
        update(MonoController.isMono(this))
    }

    override fun onClick() {
        val target = !MonoController.isMono(this)
        MonoController.setMonoAsync(this, target) { ok ->
            if (ok) {
                update(target)
            } else {
                Toast.makeText(this, R.string.toast_failed, Toast.LENGTH_LONG).show()
                openApp()
            }
        }
    }

    private fun update(mono: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (mono) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (mono) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
        }
    }
}
