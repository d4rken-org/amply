package eu.darken.amply.main.core

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import eu.darken.amply.main.ui.tile.ChargeTileService
import eu.darken.amply.main.ui.widget.AmplyWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException

object SurfaceUpdater {
    /**
     * Awaits [updateAll] in the caller's scope. Note [updateAll] only awaits Glance *enqueuing* its durable
     * update work (or handing an event to an active session), NOT the eventual composition +
     * AppWidgetManager push, which Glance performs later in its own worker. So awaiting here guards the
     * enqueue/repo step (a failure there propagates so a worker can retry, and [CancellationException] is
     * rethrown), not delivery — process-death safety comes from Glance's durable work, not from this await.
     * Only the tile update is isolated so its failure can never prevent the widget [updateAll].
     */
    suspend fun updateNow(context: Context) = runSurfaceUpdate(
        tile = { requestTileUpdate(context) },
        widget = { AmplyWidget().updateAll(context) },
    )

    private fun requestTileUpdate(context: Context) {
        TileService.requestListeningState(context, ComponentName(context, ChargeTileService::class.java))
    }
}

/**
 * Run the two surface updates with the required error policy: the [tile] update is best-effort and its
 * ordinary failures are swallowed so they can never prevent the [widget] update, while the [widget] update's
 * failures propagate to the caller. A [CancellationException] from either is always rethrown, never swallowed.
 * Caveat: for the Glance widget, "[widget] failure" means an enqueue/repo failure — the eventual composition +
 * AppWidgetManager delivery happens later in Glance's own worker and is not awaited here.
 */
internal suspend fun runSurfaceUpdate(tile: () -> Unit, widget: suspend () -> Unit) {
    try {
        tile()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Tile is best-effort; a failure here must not block the widget push.
    }
    widget()
}
