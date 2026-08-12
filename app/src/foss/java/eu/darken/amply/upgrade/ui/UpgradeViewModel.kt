package eu.darken.amply.upgrade.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.amply.R
import eu.darken.amply.common.debug.logging.Logging.Priority.WARN
import eu.darken.amply.common.debug.logging.asLog
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import eu.darken.amply.common.flow.SingleEventFlow
import eu.darken.amply.upgrade.core.UpgradeRepoFoss
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Which presentation the FOSS upgrade screen shows. */
internal enum class FossUpgradeView {
    PITCH,
    STATUS_FREE,
    STATUS_UPGRADED,
}

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val upgradeRepo: UpgradeRepoFoss,
) : ViewModel() {

    val snackbarEvents = SingleEventFlow<Int>()
    val toastEvents = SingleEventFlow<Int>()

    /**
     * Called once per visit to the screen. This ViewModel is activity-scoped and outlives the
     * screen, so entering it again must not resume the previous visit's state: `showOptions` is a
     * per-visit choice, and a user opening the settings entry expects the status view, not the
     * pitch they flipped to last time.
     */
    fun onVisitStart(manage: Boolean) {
        log(TAG) { "onVisitStart(manage=$manage)" }
        handle[KEY_MANAGE] = manage
        handle[KEY_SHOW_UPGRADE_OPTIONS] = false
    }

    /**
     * Which presentation to render. The manage entry (settings "upgrade status") gets a status view
     * first; the pitch only appears once a free user asks for the upgrade options. Being upgraded
     * wins over that choice — completing the sponsor flow from the pitch must land on the upgraded
     * status, not back on the ask. `view` stays null until [onVisitStart] has run.
     */
    internal val state: StateFlow<State> = combine(
        handle.getStateFlow<Boolean?>(KEY_MANAGE, null),
        upgradeRepo.upgradeInfo,
        handle.getStateFlow(KEY_SHOW_UPGRADE_OPTIONS, false),
    ) { manage, info, showOptions ->
        val view = when {
            manage == null -> null
            manage && info.isPro -> FossUpgradeView.STATUS_UPGRADED
            manage && !showOptions -> FossUpgradeView.STATUS_FREE
            else -> FossUpgradeView.PITCH
        }
        // Derived in the same emission as the view on purpose: a sibling flow would let the upgraded
        // status render for a frame without the date it is supposed to carry, and would let the
        // host's auto-dismiss see an isPro that doesn't match the view being shown.
        State(view = view, supporterSince = info.upgradedAt, isPro = info.isPro)
    }
        .catch { e ->
            if (e is CancellationException) throw e
            log(TAG, WARN) { "Upgrade state failed: ${e.asLog()}" }
            emit(State(view = FossUpgradeView.PITCH))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), State())

    internal data class State(
        val view: FossUpgradeView? = null,
        val supporterSince: Instant? = null,
        val isPro: Boolean = false,
    )

    fun onShowUpgradeOptions() {
        log(TAG) { "onShowUpgradeOptions()" }
        handle[KEY_SHOW_UPGRADE_OPTIONS] = true
    }

    /** Armed variant: the pitch's sponsor button, which starts the return-after-5s unlock heuristic. */
    fun goGithubSponsors() {
        log(TAG) { "goGithubSponsors()" }
        if (hasPendingSponsorLaunch()) {
            log(TAG) { "A sponsor launch is already awaiting its return" }
            return
        }
        // Only arm the heuristic if the page actually opened; otherwise an unrelated later
        // background/foreground round-trip would grant supporter status with no page ever shown.
        if (!upgradeRepo.openGithubSponsorsPage()) {
            log(TAG) { "Sponsor page didn't open; not arming the unlock heuristic" }
            return
        }
        handle[KEY_SPONSOR_PRESSED_AT] = SystemClock.elapsedRealtime()
    }

    /**
     * Unarmed variant: the status view's donate button. An existing supporter re-visiting the page
     * must not re-arm the unlock heuristic — there is nothing left to unlock.
     */
    fun openSponsors() {
        log(TAG) { "openSponsors()" }
        upgradeRepo.openGithubSponsorsPage()
    }

    /**
     * Whether a sponsor-page launch is still awaiting its return.
     *
     * Handle-backed, so it survives process recreation while the browser is in front — the screen's
     * in-memory return tracker does not, and gating on that alone drops the first return after a
     * recreation.
     */
    fun hasPendingSponsorLaunch(): Boolean = handle.contains(KEY_SPONSOR_PRESSED_AT)

    fun checkSponsorReturn() {
        viewModelScope.launch {
            try {
                runSponsorReturnCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The marker was already restored below, so the next return retries the unlock. What
                // must not happen is this escaping the scope: an unhandled exception in
                // viewModelScope takes the process down over an optional convenience.
                log(TAG, WARN) { "checkSponsorReturn() failed: ${e.asLog()}" }
            }
        }
    }

    private suspend fun runSponsorReturnCheck() {
        val pressedAt = handle.remove<Long>(KEY_SPONSOR_PRESSED_AT) ?: return

        try {
            // Evaluated before the duration: an already upgraded supporter (recurring donation
            // button) has nothing left to unlock, so this fast path exists for the UX — return
            // quietly, no redundant write attempt and no thanks toast for an unlock that already
            // happened. Data integrity is not this guard's job: the repo's create-only transaction
            // owns that.
            if (upgradeRepo.upgradeInfo.first().isPro) {
                log(TAG) { "checkSponsorReturn(): Already upgraded, staying quiet" }
                return
            }

            val elapsed = SystemClock.elapsedRealtime() - pressedAt
            log(TAG) { "checkSponsorReturn(): elapsed=${elapsed}ms" }

            if (elapsed < SPONSOR_DELAY_MS) {
                log(TAG) { "checkSponsorReturn(): Too quick, showing snackbar" }
                snackbarEvents.tryEmit(R.string.upgrade_screen_sponsor_return_too_quick)
            } else {
                log(TAG) { "checkSponsorReturn(): Delay passed, persisting upgrade" }
                val created = upgradeRepo.persistUpgrade()
                if (created) {
                    toastEvents.tryEmit(R.string.upgrade_screen_thanks_toast)
                } else {
                    // The isPro fast path read a stale emission; the transaction kept the record.
                    log(TAG) { "checkSponsorReturn(): Record already existed, staying quiet" }
                }
            }
        } catch (e: Exception) {
            // The marker was consumed above; neither a failed entitlement read nor a failed write
            // may eat the user's valid sponsor visit — restore it so the next return can retry the
            // unlock. Conditional: the user may have armed a NEWER launch while this attempt was
            // suspended, and that one must survive. The contains-check has a small check-then-act
            // window against a concurrent new arm; accepted — the create-only transaction owns data
            // integrity, a wrong winner only changes which REAL visit's timestamp gates the unlock.
            // Rethrown unconditionally, so cancellation is not swallowed.
            if (!handle.contains(KEY_SPONSOR_PRESSED_AT)) {
                handle[KEY_SPONSOR_PRESSED_AT] = pressedAt
            }
            throw e
        }
    }

    companion object {
        private const val KEY_SPONSOR_PRESSED_AT = "sponsor_pressed_at"
        private const val KEY_SHOW_UPGRADE_OPTIONS = "show_upgrade_options"
        private const val KEY_MANAGE = "upgrade_manage"
        private const val SPONSOR_DELAY_MS = 5_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private val TAG = logTag("Upgrade", "Foss", "ViewModel")
    }
}
