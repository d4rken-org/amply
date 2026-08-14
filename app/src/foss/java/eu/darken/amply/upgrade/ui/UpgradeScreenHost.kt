package eu.darken.amply.upgrade.ui

import android.widget.Toast
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The FOSS upgrade destination. Same fully-qualified name as the gplay host, so the composition root
 * in `src/main` can render "the upgrade screen" without knowing which flavor it was built for.
 *
 * The ViewModel is resolved through [viewModel] rather than a navigation-scoped factory: the store
 * owner here is the hosting activity, whose default factory is Hilt's, so this is an
 * activity-scoped injected ViewModel — which is what the sponsor-return heuristic needs, since it
 * has to survive the browser being in front.
 *
 * @param manage true for the settings "upgrade status" entry, which shows a status view first
 *   instead of the support pitch, and never auto-dismisses.
 */
@Composable
fun UpgradeScreenHost(
    manage: Boolean,
    onBack: () -> Unit,
) {
    val vm: UpgradeViewModel = viewModel()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Per-visit, not per-ViewModel: the ViewModel is activity-scoped and outlives this screen. Keyed
    // on `manage` as well, because the composition root can change it in place while this screen
    // stays composed (a widget's "open upgrade" intent arriving on the open status view) — keyed on
    // Unit the binding would never be redone and the screen would keep the previous entry's view.
    LaunchedEffect(vm, manage) { vm.onVisitStart(manage) }

    // ...and released when the screen leaves, so the next entry decides from its own `manage` rather
    // than inheriting this visit's binding.
    DisposableEffect(Unit) {
        onDispose { vm.onVisitEnd() }
    }

    // Seeded from the ViewModel's handle-backed pending launch: after a process death while the
    // sponsor page was open, a blank tracker would swallow the very first return. The handle is the
    // authority on whether a return is still expected, so it reconstructs the tracker's state.
    val sponsorReturnTracker = remember(vm) {
        SponsorReturnTracker(wentToBackground = vm.hasPendingSponsorLaunch())
    }

    LaunchedEffect(vm) {
        vm.snackbarEvents.collect { stringRes ->
            snackbarHostState.showSnackbar(context.getString(stringRes))
        }
    }

    LaunchedEffect(vm) {
        vm.toastEvents.collect { stringRes ->
            Toast.makeText(context, context.getString(stringRes), Toast.LENGTH_LONG).show()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        sponsorReturnTracker.onStop()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (sponsorReturnTracker.consumeResumeReturn()) {
            vm.checkSponsorReturn()
        }
    }

    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.view, state.isPro, manage) {
        if (shouldDismissUpgradeScreen(view = state.view, isPro = state.isPro, manage = manage)) onBack()
    }

    UpgradeScreen(
        view = upgradeViewFor(view = state.view, manage = manage),
        supporterSince = state.supporterSince,
        snackbarHostState = snackbarHostState,
        onGithubSponsors = vm::goGithubSponsors,
        onOpenSponsors = vm::openSponsors,
        onShowUpgradeOptions = vm::onShowUpgradeOptions,
        onNavigateUp = onBack,
    )
}

/**
 * Which presentation to render for a given entry. [view] is the ViewModel's decision and is null
 * until this visit's binding lands (one frame): the plain entry keeps rendering the pitch exactly as
 * before, only the manage entry waits for the status decision rather than flashing the pitch it is
 * there to replace.
 *
 * Pure and frame-order-independent on purpose — this is the part of the host that a stale state can
 * get wrong, and it is worth being able to assert without standing up the whole screen.
 */
internal fun upgradeViewFor(view: FossUpgradeView?, manage: Boolean): FossUpgradeView? =
    view ?: FossUpgradeView.PITCH.takeIf { !manage }

/**
 * The pitch is a request to upgrade: once the upgrade lands the request is answered, so the screen
 * closes and the user goes back to what they were doing. The manage views are the destination itself
 * and stay — and [manage] is checked here too, not just via the view: a manage entry that arrives
 * while the previous visit's PITCH is still bound would otherwise dismiss itself on sight.
 */
internal fun shouldDismissUpgradeScreen(view: FossUpgradeView?, isPro: Boolean, manage: Boolean): Boolean =
    view == FossUpgradeView.PITCH && isPro && !manage
