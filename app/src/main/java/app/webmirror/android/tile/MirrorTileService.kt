package app.webmirror.android.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.webmirror.android.MainActivity
import app.webmirror.android.MirrorService
import app.webmirror.android.ServiceState
import app.webmirror.android.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: starts mirroring (via [StartCaptureActivity]) or stops it. */
class MirrorTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collecting: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        collecting?.cancel()
        collecting = scope.launch { MirrorService.state.collect { render(it) } }
    }

    override fun onStopListening() {
        collecting?.cancel()
        collecting = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(s: ServiceState) {
        val tile = qsTile ?: return
        val mirroring = s.running && !s.paused
        tile.state = if (mirroring) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "WebMirror"
        tile.subtitle = when {
            mirroring -> "Mirroring"
            s.running -> "Paused"
            else -> "Off"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val s = MirrorService.state.value
        if (s.running && !s.paused) {
            MirrorService.stop(this)
            return
        }
        val accepted = Settings(this).safetyAcceptedVersion >= Settings.SAFETY_NOTICE_VERSION
        val target = if (accepted) StartCaptureActivity::class.java else MainActivity::class.java
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isLocked) unlockAndRun { launch(intent) } else launch(intent)
    }

    // The Intent variant runs only below API 34, where it is the only one; API 34+ uses the PendingIntent variant.
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to bind the tile so it redraws even while Quick Settings is closed. */
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, MirrorTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
