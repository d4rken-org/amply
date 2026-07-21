package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.charging.core.PendingRequest
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import eu.darken.amply.charging.core.access.AccessSnapshot
import eu.darken.amply.charging.core.access.BackendStatus
import eu.darken.amply.charging.core.isSettling
import eu.darken.amply.charging.core.settlingTarget
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.caString
import eu.darken.amply.common.ca.toCaString
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.asComposable
import eu.darken.amply.fullcharge.core.ChargeSessionRecord
import eu.darken.amply.fullcharge.core.policyOrNull
import eu.darken.amply.main.core.formatReport
import eu.darken.amply.main.ui.setup.AccessSetupGuide
import eu.darken.amply.main.ui.setup.UnsupportedDeviceCard
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    adbCommand: String,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onStartFull: () -> Unit,
    onRestore: () -> Unit,
    onApply: (ChargePolicy) -> Unit,
    onQuickFullChargeChange: (Boolean) -> Unit,
    onOpenReconnectSettings: () -> Unit,
    onPinWidget: () -> Unit,
    onAddTile: () -> Unit,
    onDismissQuickAccess: () -> Unit,
    onNativeSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onGrantWss: () -> Unit,
    onCopyAdb: () -> Unit,
    onCopyWebUsbLink: () -> Unit,
    onPrepareSupportReport: () -> Unit,
    onCopySupportReport: () -> Unit,
    onOpenSupportIssue: () -> Unit,
    onEmailSupport: () -> Unit,
    onHelp: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.TwoTone.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        // Centre and cap the content width so cards don't stretch edge-to-edge on tablets.
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val sidePadding = ((maxWidth - DASHBOARD_MAX_WIDTH) / 2).coerceAtLeast(16.dp)
            // Derived once and shared by the status + full-charge cards so they can never disagree
            // about whether the one-time charge is actually in effect.
            val sessionPresentation = SessionPresentation.from(state.session, state.charging.observation)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StatusCard(state, sessionPresentation, onRefresh) }

                if (state.charging.observation is ChargeObservation.Unsupported) {
                    // Unsupported devices cannot use the Pixel policy/charge controls; showing them
                    // greyed-out (and a "Pixel settings" action) only confuses non-Pixel users. Offer
                    // a contribution path instead, and keep only the restore card if a session lingers.
                    if (state.session != null) {
                        item {
                            FullChargeCard(
                                presentation = sessionPresentation,
                                canControl = state.charging.controlEnabled &&
                                    state.charging.access?.canControl == true,
                                onStart = onStartFull,
                                onRestore = onRestore,
                            )
                        }
                    }
                    // If the reconnect gesture is still switched on (e.g. the device became unsupported
                    // after it was enabled), keep the card so the user can turn it — and its foreground
                    // service — off. The card renders as disable-only when control isn't available.
                    if (state.quickFullChargeEnabled) {
                        item {
                            QuickFullChargeCard(
                                enabled = true,
                                anyLevel = state.quickFullChargeAnyLevel,
                                canControl = state.charging.controlEnabled &&
                                    state.charging.access?.canControl == true,
                                onEnabledChange = onQuickFullChargeChange,
                                onOpenSettings = onOpenReconnectSettings,
                            )
                        }
                    }
                    if (state.charging.contributionWanted) {
                        item {
                            UnsupportedDeviceCard(
                                manufacturer = state.charging.device.manufacturer
                                    .ifBlank { stringResource(R.string.dashboard_manufacturer_fallback) },
                                reportPreview = state.deviceReport?.let(::formatReport),
                                onPrepareReport = onPrepareSupportReport,
                                onCopyReport = onCopySupportReport,
                                onOpenIssue = onOpenSupportIssue,
                                onEmail = onEmailSupport,
                                onHelp = onHelp,
                            )
                        }
                    }
                } else {
                    // Shizuku-only adapters (OnePlus/ColorOS) can't use WSS at all, so the WSS/ADB
                    // setup guide would ask for an ineffective grant — the dedicated
                    // "Shizuku required" banner below covers their setup instead.
                    if (!state.charging.writeRequiresShizuku && state.charging.access?.direct?.ready != true) {
                        item {
                            AccessSetupGuide(
                                state = state,
                                adbCommand = adbCommand,
                                onOpenShizuku = onOpenShizuku,
                                onAllowShizuku = onAllowShizuku,
                                onGrantWss = onGrantWss,
                                onCopyAdb = onCopyAdb,
                                onCopyWebUsbLink = onCopyWebUsbLink,
                            )
                        }
                    }

                    item {
                        FullChargeCard(
                            presentation = sessionPresentation,
                            canControl = state.charging.canApply,
                            onStart = onStartFull,
                            onRestore = onRestore,
                        )
                    }
                    item { PolicyCard(state, onApply, onNativeSettings) }
                    // Hidden where the adapter lacks the gesture's hardware signal (non-Pixel) —
                    // unless it is still switched on and needs a way to be turned off.
                    if (state.charging.reconnectSupported || state.quickFullChargeEnabled) {
                        item {
                            QuickFullChargeCard(
                                enabled = state.quickFullChargeEnabled,
                                anyLevel = state.quickFullChargeAnyLevel,
                                canControl = state.charging.reconnectSupported && state.charging.canApply,
                                onEnabledChange = onQuickFullChargeChange,
                                onOpenSettings = onOpenReconnectSettings,
                            )
                        }
                    }
                    // Promote the widget/tile shortcuts only once setup is done (the setup guide above
                    // has disappeared) and while at least one shortcut is still undiscovered.
                    if (shouldShowQuickAccess(
                            // Promote the shortcuts only once they'd actually work — for Shizuku-only
                            // adapters WSS alone isn't enough, so gate on canApply, not just WSS.
                            canApply = state.charging.canApply,
                            presenceChecked = state.quickAccessChecked,
                            quickAccess = state.quickAccess,
                        )
                    ) {
                        item {
                            QuickAccessCard(
                                widgetAdded = state.quickAccess.widgetAdded,
                                tileAdded = state.quickAccess.tileAdded,
                                tileRequestPending = state.tileRequestPending,
                                onPinWidget = onPinWidget,
                                onAddTile = onAddTile,
                                onDismiss = onDismissQuickAccess,
                            )
                        }
                    }
                    val access = state.charging.access
                    when {
                        // System-namespace adapters (OnePlus/ColorOS): writes need Shizuku even
                        // when WSS is granted, so nudge toward it and the controls above are
                        // disabled until it is connected.
                        state.charging.controlEnabled &&
                            state.charging.writeRequiresShizuku &&
                            access?.shizuku?.ready != true -> item {
                            ShizukuBanner(
                                running = access?.shizuku?.available == true,
                                requiredForControl = true,
                                onOpen = onOpenShizuku,
                                onAllow = onAllowShizuku,
                            )
                        }
                        // Only nudge toward Shizuku once the user is already on the WSS-only
                        // (computer) path: durable control is present but Shizuku isn't, so exact
                        // readback/diagnostics are missing. Sync-readback adapters verify through
                        // any backend, so the readback pitch would be wrong there.
                        access?.direct?.ready == true && !access.canVerify && !state.charging.syncVerification -> item {
                            ShizukuBanner(
                                running = access.shizuku.available,
                                requiredForControl = false,
                                onOpen = onOpenShizuku,
                                onAllow = onAllowShizuku,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val DASHBOARD_MAX_WIDTH = 600.dp

@Composable
private fun StatusCard(
    state: DashboardUiState,
    presentation: SessionPresentation,
    onRefresh: () -> Unit,
) {
    val observation = state.charging.observation
    val pending = state.charging.pending

    // Drive a live clock so the "applying…" cue and its spinner clear promptly at the end of the window,
    // independent of WorkManager's (possibly slightly late) durable clear.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pending) {
        if (pending != null) {
            val end = pending.requestedAt + SETTLING_WINDOW_MILLIS
            while (System.currentTimeMillis() < end) {
                now = System.currentTimeMillis()
                delay(500)
            }
            now = System.currentTimeMillis()
            onRefresh() // re-read hardware to promote to Verified / clear the pending marker
        }
    }
    val settling = state.charging.isSettling(now)
    val verified = observation is ChargeObservation.Verified

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (settling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Icon(
                        imageVector = if (verified) Icons.Default.CheckCircle else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (verified) Color(0xFF1A7F5A) else MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    when {
                        settling -> stringResource(R.string.dashboard_applying)
                        presentation == SessionPresentation.ACTIVE ->
                            stringResource(R.string.dashboard_session_once_title)
                        else -> observation.title().asComposable()
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                observation.detail().asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!settling) {
                // One explanatory line: during a one-time charge, which policy comes back; otherwise
                // what the current policy does. Phrased as definitions so it stays honest when the
                // observation is only "last requested".
                val explanation = if (presentation == SessionPresentation.ACTIVE) {
                    state.session?.let {
                        stringResource(R.string.dashboard_session_returns, it.restorePolicy.shortLabel().asComposable())
                    }
                } else {
                    observation.policyOrNull()?.description()?.asComposable()
                }
                explanation?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.dashboard_device_line,
                    state.charging.device.model,
                    state.charging.device.sdk,
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            if (settling) {
                val target = state.charging.settlingTarget()?.shortLabel()?.asComposable()
                    ?: stringResource(R.string.dashboard_settling_fallback_target)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.dashboard_waiting_target, target),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                // The repository's apply message describes the request the settling line already
                // narrates, so only show it once settling has resolved.
                state.charging.message?.let {
                    Text(
                        it.asComposable(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullChargeCard(
    presentation: SessionPresentation,
    canControl: Boolean,
    onStart: () -> Unit,
    onRestore: () -> Unit,
) {
    // Any session record offers the restore action; only an observed full-charge policy (verified
    // or last requested) claims the one-time charge is running.
    val active = presentation != SessionPresentation.NONE
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (presentation) {
                    SessionPresentation.NONE -> stringResource(R.string.dashboard_fullcharge_idle_title)
                    SessionPresentation.ACTIVE -> stringResource(R.string.dashboard_fullcharge_active_title)
                    SessionPresentation.RECORDED -> stringResource(R.string.dashboard_fullcharge_recorded_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            when (presentation) {
                // The status card explains an ACTIVE session; repeating it here would duplicate.
                SessionPresentation.ACTIVE -> Unit
                SessionPresentation.NONE -> Text(
                    stringResource(R.string.dashboard_fullcharge_idle_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                SessionPresentation.RECORDED -> Text(
                    stringResource(R.string.dashboard_fullcharge_recorded_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = if (active) onRestore else onStart,
                // Restore also writes, so it needs a working backend too — gating only on `active`
                // would keep it enabled after Shizuku drops mid-session on a Shizuku-only adapter,
                // where tapping it just fails. The Shizuku-required banner guides reconnection.
                enabled = canControl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (active) Icons.Default.PowerSettingsNew else Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                )
                Text(
                    if (active) {
                        stringResource(R.string.dashboard_fullcharge_restore)
                    } else {
                        stringResource(R.string.dashboard_fullcharge_start)
                    },
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyCard(
    state: DashboardUiState,
    onApply: (ChargePolicy) -> Unit,
    onNativeSettings: () -> Unit,
) {
    val choices = state.charging.supportedPolicies
        .ifEmpty {
            // Adapter not resolved yet (detecting/preview default): show the classic layout instead
            // of an empty card.
            listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Adaptive, ChargePolicy.Unrestricted)
        }
        .map { it to it.choiceLabel() }
    val selectedPolicy = selectedPolicyFor(state.session, state.charging.observation)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dashboard_policy_card_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onNativeSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.dashboard_native_settings),
                        Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val choiceEnabled = state.charging.canApply && !state.charging.busy
            if (choices.size <= 4) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, (policy, label) ->
                        SegmentedButton(
                            selected = selectedPolicy == policy,
                            onClick = { onApply(policy) },
                            enabled = choiceEnabled,
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            } else {
                // More options than a segmented row can fit legibly (Samsung: four limits + two modes).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { (policy, label) ->
                        FilterChip(
                            selected = selectedPolicy == policy,
                            onClick = { onApply(policy) },
                            enabled = choiceEnabled,
                            label = { Text(label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFullChargeCard(
    enabled: Boolean,
    anyLevel: Boolean,
    canControl: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // When the gesture can neither be used nor turned off, dim the whole card so it reads as
    // disabled like the other controls, instead of showing a full-colour but inert toggle.
    val interactive = enabled || canControl
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(start=20.dp,end=20.dp,top=12.dp,bottom=20.dp)
                .alpha(if (interactive) 1f else 0.38f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.dashboard_reconnect_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) {
                    IconButton(onClick = onOpenSettings, enabled = interactive) {
                        Icon(
                            Icons.TwoTone.Settings,
                            contentDescription = stringResource(R.string.dashboard_reconnect_settings_action),
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled || canControl,
                )
            }
            Text(
                when {
                    enabled && anyLevel -> stringResource(R.string.dashboard_reconnect_body_on_any_level)
                    enabled -> stringResource(R.string.dashboard_reconnect_body_on)
                    else -> stringResource(R.string.dashboard_reconnect_body_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun QuickFullChargeCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QuickFullChargeCard(
            enabled = true,
            anyLevel = false,
            canControl = true,
            onEnabledChange = {},
            onOpenSettings = {},
        )
        QuickFullChargeCard(
            enabled = true,
            anyLevel = true,
            canControl = true,
            onEnabledChange = {},
            onOpenSettings = {},
        )
    }
}

// Gear + switch share the title row; make sure it degrades gracefully at large font scales.
@Preview(name = "Large font", showBackground = true, fontScale = 1.5f)
@Composable
private fun QuickFullChargeCardLargeFontPreview() = PreviewWrapper {
    QuickFullChargeCard(
        enabled = true,
        anyLevel = true,
        canControl = true,
        onEnabledChange = {},
        onOpenSettings = {},
    )
}

@Composable
private fun ShizukuBanner(
    running: Boolean,
    requiredForControl: Boolean,
    onOpen: () -> Unit,
    onAllow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(
                    if (requiredForControl) R.string.dashboard_shizuku_required_title
                    else R.string.dashboard_shizuku_title,
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    if (requiredForControl) R.string.dashboard_shizuku_required_body
                    else R.string.dashboard_shizuku_body,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = if (running) onAllow else onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    if (running) {
                        stringResource(R.string.dashboard_shizuku_allow)
                    } else {
                        stringResource(R.string.dashboard_shizuku_open)
                    },
                )
            }
        }
    }
}

private fun ChargeObservation.title(): CaString = when (this) {
    is ChargeObservation.Verified -> if (backend == BackendKind.BATTERY_HARDWARE) {
        caString { it.getString(R.string.dashboard_status_verified_active, policy.shortLabel().get(it)) }
    } else {
        policy.shortLabel()
    }
    is ChargeObservation.LastRequested -> policy.shortLabel()
    is ChargeObservation.NeedsSetup -> R.string.dashboard_status_setup_required.toCaString()
    is ChargeObservation.Unsupported -> R.string.dashboard_status_unsupported.toCaString()
    is ChargeObservation.Unknown -> R.string.dashboard_status_unknown.toCaString()
}

private fun ChargeObservation.detail(): CaString = when (this) {
    is ChargeObservation.Verified -> if (backend == BackendKind.BATTERY_HARDWARE) {
        R.string.dashboard_detail_hw_confirmed.toCaString()
    } else {
        caString {
            it.getString(R.string.dashboard_detail_readback, backend.name.replace('_', ' ').lowercase())
        }
    }
    is ChargeObservation.LastRequested -> R.string.dashboard_detail_last_requested.toCaString()
    is ChargeObservation.NeedsSetup -> reason
    is ChargeObservation.Unsupported -> reason
    is ChargeObservation.Unknown -> reason
}

@Composable
private fun ChargePolicy.choiceLabel(): String = when (this) {
    is ChargePolicy.FixedLimit -> stringResource(R.string.dashboard_policy_choice_fixed, percent)
    ChargePolicy.Adaptive -> stringResource(R.string.dashboard_policy_choice_adaptive)
    ChargePolicy.Unrestricted -> stringResource(R.string.dashboard_policy_choice_100)
    ChargePolicy.PauseAtFull -> stringResource(R.string.dashboard_policy_choice_pause)
}

private fun ChargePolicy.shortLabel(): CaString = when (this) {
    ChargePolicy.Adaptive -> R.string.dashboard_policy_adaptive.toCaString()
    ChargePolicy.Unrestricted -> R.string.dashboard_policy_full.toCaString()
    ChargePolicy.PauseAtFull -> R.string.dashboard_policy_pause_at_full.toCaString()
    is ChargePolicy.FixedLimit -> R.string.dashboard_policy_fixed.toCaString(percent)
}

private fun ChargePolicy.description(): CaString = when (this) {
    ChargePolicy.Adaptive -> R.string.dashboard_policy_desc_adaptive.toCaString()
    ChargePolicy.Unrestricted -> R.string.dashboard_policy_desc_full.toCaString()
    ChargePolicy.PauseAtFull -> R.string.dashboard_policy_desc_pause.toCaString()
    is ChargePolicy.FixedLimit -> if (percent >= 100) {
        // A 100% "limit" is a full charge; the battery-health claim would be wrong.
        R.string.dashboard_policy_desc_full.toCaString()
    } else {
        R.string.dashboard_policy_desc_fixed.toCaString(percent)
    }
}

@AmplyPreview
@Composable
private fun DashboardScreenPreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            quickFullChargeEnabled = true,
            // Presence check done, nothing discovered yet — renders the quick-access promotion.
            quickAccessChecked = true,
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control".toCaString(),
                adapterId = "pixel",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.Adaptive,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = true,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Shizuku connected".toCaString()
                    ),
                ),
                observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.SHIZUKU),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

// Mid-apply: spinner, "Applying…", and the waiting line with the duration hint.
@AmplyPreview
@Composable
private fun DashboardScreenApplyingPreview() = PreviewWrapper {
    // Captured once so recomposition doesn't rebuild the pending request and restart the settle clock.
    val requestedAt = remember { System.currentTimeMillis() }
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control".toCaString(),
                adapterId = "pixel",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.Adaptive,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = true,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Shizuku connected".toCaString(),
                    ),
                ),
                observation = ChargeObservation.LastRequested(ChargePolicy.FixedLimit(80)),
                pending = PendingRequest(ChargePolicy.FixedLimit(80), requestedAt),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

// One-time full charge running: session-aware hero plus the trimmed restore card.
@AmplyPreview
@Composable
private fun DashboardScreenSessionActivePreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            session = ChargeSessionRecord(
                restorePolicy = ChargePolicy.FixedLimit(80),
                startedAtMillis = 0L,
                connectedSeen = true,
            ),
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control".toCaString(),
                adapterId = "pixel",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.Adaptive,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = true,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Shizuku connected".toCaString(),
                    ),
                ),
                observation = ChargeObservation.Verified(ChargePolicy.Unrestricted, BackendKind.BATTERY_HARDWARE),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

// A session record without confirmed 100% charging (e.g. the override write failed and the record
// is kept for recovery): the hero stays truthful and the full-charge card shows the neutral
// "recorded" wording with the restore action.
@AmplyPreview
@Composable
private fun DashboardScreenSessionRecordedPreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            session = ChargeSessionRecord(
                restorePolicy = ChargePolicy.FixedLimit(80),
                startedAtMillis = 0L,
                connectedSeen = false,
            ),
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control".toCaString(),
                adapterId = "pixel",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.Adaptive,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = true,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Shizuku connected".toCaString(),
                    ),
                ),
                observation = ChargeObservation.Unknown("The settings write failed".toCaString()),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

// WSS granted via the computer path but Shizuku absent: no setup guide, and the "Better with Shizuku"
// banner appears to offer exact readback/diagnostics.
@AmplyPreview
@Composable
private fun DashboardScreenWssOnlyPreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control".toCaString(),
                adapterId = "pixel",
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = false,
                        granted = false,
                        detail = "Shizuku not running".toCaString(),
                    ),
                ),
                observation = ChargeObservation.LastRequested(ChargePolicy.FixedLimit(80)),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

@AmplyPreview
@Composable
private fun DashboardScreenSamsungPreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            charging = ChargingState(
                device = DeviceInfo("samsung", "SM-X210", 36, "preview", oneUiVersion = 80000, hasProtectBattery = true),
                adapterName = "Samsung battery protection".toCaString(),
                adapterId = "samsung-oneui8-v1",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.FixedLimit(85),
                    ChargePolicy.FixedLimit(90),
                    ChargePolicy.FixedLimit(95),
                    ChargePolicy.PauseAtFull,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = false,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "Charge-control access granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = false,
                        granted = false,
                        detail = "Shizuku not installed".toCaString(),
                    ),
                ),
                observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.DIRECT_WSS),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

@AmplyPreview
@Composable
private fun DashboardScreenOnePlusNeedsShizukuPreview() = PreviewWrapper {
    // OnePlus/ColorOS: state is readable via WSS, but writes need Shizuku (not connected here),
    // so the controls are disabled and the Shizuku-required banner shows.
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            charging = ChargingState(
                device = DeviceInfo("OnePlus", "CPH2621", 35, "preview", oplusRomVersion = 15),
                adapterName = "ColorOS charging protection".toCaString(),
                adapterId = "oplus-coloros15-v1",
                supportedPolicies = listOf(
                    ChargePolicy.FixedLimit(80),
                    ChargePolicy.Adaptive,
                    ChargePolicy.Unrestricted,
                ),
                reconnectSupported = false,
                syncVerification = true,
                writeRequiresShizuku = true,
                controlEnabled = true,
                access = AccessSnapshot(
                    direct = BackendStatus(
                        available = true,
                        granted = true,
                        detail = "WRITE_SECURE_SETTINGS granted".toCaString(),
                    ),
                    shizuku = BackendStatus(
                        available = false,
                        granted = false,
                        detail = "Shizuku not connected".toCaString(),
                    ),
                ),
                observation = ChargeObservation.Verified(ChargePolicy.FixedLimit(80), BackendKind.DIRECT_WSS),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}

@AmplyPreview
@Composable
private fun DashboardScreenUnsupportedPreview() = PreviewWrapper {
    DashboardScreen(
        state = DashboardUiState(
            onboardingComplete = true,
            charging = ChargingState(
                device = DeviceInfo("Samsung", "SM-S911B", 34, "preview", hasChargingOptimization = false),
                adapterName = "Diagnostics only".toCaString(),
                controlEnabled = false,
                contributionWanted = true,
                observation = ChargeObservation.Unsupported("This device is not a supported Pixel".toCaString()),
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onRefresh = {},
        onSettings = {},
        onStartFull = {},
        onRestore = {},
        onApply = {},
        onQuickFullChargeChange = {},
        onOpenReconnectSettings = {},
        onPinWidget = {},
        onAddTile = {},
        onDismissQuickAccess = {},
        onNativeSettings = {},
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onCopyWebUsbLink = {},
        onPrepareSupportReport = {},
        onCopySupportReport = {},
        onOpenSupportIssue = {},
        onEmailSupport = {},
        onHelp = {},
    )
}
