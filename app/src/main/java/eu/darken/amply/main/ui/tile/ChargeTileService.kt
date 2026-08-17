package eu.darken.amply.main.ui.tile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargingRepository
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.charging.core.isAwaitingReplug
import eu.darken.amply.charging.core.isSettling
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.fullcharge.core.FullChargeStore
import eu.darken.amply.fullcharge.core.ChargeSessionService
import eu.darken.amply.main.core.QuickAccessStore
import eu.darken.amply.main.ui.MainActivity
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.isProForUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class ChargeTileService : TileService() {
    @Inject lateinit var repository: ChargingRepository
    @Inject lateinit var sessionStore: FullChargeStore
    @Inject lateinit var quickAccessStore: QuickAccessStore
    @Inject lateinit var upgradeRepo: UpgradeRepo

    private var scope: CoroutineScope? = null

    // Deliberately the only tile-side discovery signal: onStartListening also fires when SystemUI
    // merely previews the tile in the QS edit sheet, so marking there would permanently hide the
    // dashboard promotion for users who only browsed. Installs that added the tile before this
    // marker existed self-heal through the add request's TILE_ALREADY_ADDED result.
    override fun onTileAdded() {
        super.onTileAdded()
        markTileDiscovered()
    }

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { worker ->
            worker.launch {
                val isPro = upgradeRepo.isProForUi()
                val sessionActive = sessionStore.currentSession() != null
                val state = repository.refresh()
                withContext(Dispatchers.Main) {
                    render(
                        isPro = isPro,
                        active = sessionActive,
                        available = state.canApply,
                        // Pending states beat the static access/adapter label: opening QS is the
                        // tile's refresh cadence, so this is exactly when they are fresh. The two
                        // pending kinds are mutually exclusive by construction (a latched request is
                        // never "settling"); the order documents intent, not a real priority.
                        detail = when {
                            state.isAwaitingReplug() -> getString(R.string.tile_replug_hint)
                            state.isSettling(System.currentTimeMillis()) ->
                                getString(R.string.tile_applying)
                            // The tile can act on this build, but nothing observable can show the
                            // limit holding — say so rather than print the reassuring access label.
                            state.enforcement == EnforcementStatus.UNVERIFIED ->
                                getString(R.string.tile_enforcement_unverified)
                            else -> (state.access?.label ?: state.adapterName)
                                .get(this@ChargeTileService)
                        },
                    )
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            if (!upgradeRepo.isProForUi()) {
                // Checked before anything else, including a running session: the tile is the gated
                // affordance, and opening the upgrade screen is a better answer than silently doing
                // nothing. A session started elsewhere can still be ended from the app or the widget.
                withContext(Dispatchers.Main) { openApp(requestNotifications = false, openUpgrade = true) }
            } else if (sessionStore.currentSession() != null) {
                startChargeService(ChargeSessionService.ACTION_RESTORE)
            } else if (!canShowNotifications()) {
                withContext(Dispatchers.Main) { openApp(requestNotifications = true) }
            } else {
                val state = repository.refresh()
                if (state.canApply) {
                    startChargeService(ChargeSessionService.ACTION_START)
                } else {
                    withContext(Dispatchers.Main) { openApp(requestNotifications = false) }
                }
            }
        }
    }

    // Fire-and-forget on a throwaway scope (same pattern as onClick): the system may deliver
    // onTileAdded outside any listening window and tear the service down right after, so the write
    // must not hang off a service-lifecycle scope — and a DataStore failure on this optional promo
    // marker must never crash the functioning tile.
    private fun markTileDiscovered() {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { quickAccessStore.markTileAdded() }
                .onFailure { log(TAG, Logging.Priority.WARN) { "markTileAdded failed: ${it.message}" } }
        }
    }

    private fun startChargeService(action: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ChargeSessionService::class.java).setAction(action),
        )
    }

    private fun canShowNotifications(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(requestNotifications: Boolean, openUpgrade: Boolean = false) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_NOTIFICATIONS, requestNotifications)
            if (openUpgrade) putExtra(MainActivity.EXTRA_OPEN_UPGRADE, true)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    40,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(isPro: Boolean, active: Boolean, available: Boolean, detail: String) {
        qsTile?.apply {
            state = when {
                // Never STATE_UNAVAILABLE for the upgrade lock: SystemUI renders that as an inert,
                // untappable tile, and the whole point here is that the tap leads somewhere.
                !isPro -> Tile.STATE_INACTIVE
                active -> Tile.STATE_ACTIVE
                available -> Tile.STATE_INACTIVE
                else -> Tile.STATE_UNAVAILABLE
            }
            label = getString(if (isPro && active) R.string.tile_active else R.string.tile_label)
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = when {
                    !isPro -> getString(
                        R.string.tile_upgrade_required,
                        getString(R.string.app_name_upgraded_template, getString(R.string.app_name), upgradeTier()),
                    )
                    active -> getString(R.string.tile_override_active)
                    else -> detail
                }
            }
            updateTile()
        }
    }

    private fun upgradeTier(): String = getString(R.string.app_name_upgrade_postfix)

    private companion object {
        val TAG = logTag("Tile", "ChargeTileService")
    }
}
