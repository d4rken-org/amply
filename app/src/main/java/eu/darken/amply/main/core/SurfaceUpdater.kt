package eu.darken.amply.main.core

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import eu.darken.amply.main.ui.tile.ChargeTileService
import eu.darken.amply.main.ui.widget.AmplyWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SurfaceUpdater {
    fun update(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, ChargeTileService::class.java))
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { AmplyWidget().updateAll(context) }
        }
    }

    /** Awaits the widget update in the caller's scope — use from a worker so it can't die before the push lands. */
    suspend fun updateNow(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, ChargeTileService::class.java))
        AmplyWidget().updateAll(context)
    }
}
