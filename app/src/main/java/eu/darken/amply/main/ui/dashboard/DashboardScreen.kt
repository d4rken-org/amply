package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.SETTLING_WINDOW_MILLIS
import eu.darken.amply.charging.core.isSettling
import eu.darken.amply.charging.core.settlingTarget
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
    onNativeSettings: () -> Unit,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onGrantWss: () -> Unit,
    onCopyAdb: () -> Unit,
    onPrepareSupportReport: () -> Unit,
    onCopySupportReport: () -> Unit,
    onOpenSupportIssue: () -> Unit,
    onHelp: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Amply", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.TwoTone.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(state, onRefresh) }

            if (state.charging.observation is ChargeObservation.Unsupported) {
                // Unsupported devices cannot use the Pixel policy/charge controls; showing them
                // greyed-out (and a "Pixel settings" action) only confuses non-Pixel users. Offer
                // a contribution path instead, and keep only the restore card if a session lingers.
                if (state.session != null) {
                    item {
                        FullChargeCard(
                            active = true,
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
                            canControl = state.charging.controlEnabled &&
                                state.charging.access?.canControl == true,
                            onEnabledChange = onQuickFullChargeChange,
                        )
                    }
                }
                if (state.charging.contributionWanted) {
                    item {
                        UnsupportedDeviceCard(
                            manufacturer = state.charging.device.manufacturer.ifBlank { "these" },
                            reportPreview = state.deviceReport?.let(::formatReport),
                            onPrepareReport = onPrepareSupportReport,
                            onCopyReport = onCopySupportReport,
                            onOpenIssue = onOpenSupportIssue,
                            onHelp = onHelp,
                        )
                    }
                }
            } else {
                if (state.charging.access?.direct?.ready != true) {
                    item {
                        AccessSetupGuide(
                            state = state,
                            adbCommand = adbCommand,
                            onOpenShizuku = onOpenShizuku,
                            onAllowShizuku = onAllowShizuku,
                            onGrantWss = onGrantWss,
                            onCopyAdb = onCopyAdb,
                        )
                    }
                }

                item {
                    FullChargeCard(
                        active = state.session != null,
                        canControl = state.charging.controlEnabled && state.charging.access?.canControl == true,
                        onStart = onStartFull,
                        onRestore = onRestore,
                    )
                }
                item { PolicyCard(state, onApply, onNativeSettings) }
                item {
                    QuickFullChargeCard(
                        enabled = state.quickFullChargeEnabled,
                        canControl = state.charging.controlEnabled && state.charging.access?.canControl == true,
                        onEnabledChange = onQuickFullChargeChange,
                    )
                }
                if (state.charging.access?.shizuku?.ready != true) {
                    item {
                        ShizukuBanner(
                            running = state.charging.access?.shizuku?.available == true,
                            onOpen = onOpenShizuku,
                            onAllow = onAllowShizuku,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: DashboardUiState, onRefresh: () -> Unit) {
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
                horizontalArrangement = Arrangement.spacedBy(16.dp),
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
                    if (settling) "Applying…" else observation.title(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                observation.detail(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${state.charging.device.model} · Android API ${state.charging.device.sdk}",
                style = MaterialTheme.typography.labelMedium,
            )
            if (settling) {
                val target = state.charging.settlingTarget()?.shortLabel() ?: "the new policy"
                Spacer(Modifier.height(4.dp))
                Text(
                    "Waiting for the system to switch to $target…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                // The repository's "may take ~15s" message duplicates the settling line, so only show it
                // once settling has resolved.
                state.charging.message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun FullChargeCard(
    active: Boolean,
    canControl: Boolean,
    onStart: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                if (active) "Charging fully once" else "Need a full battery?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (active) {
                    "The protective policy returns at 100% or when you unplug."
                } else {
                    "Temporarily allow 100%, then restore your protective policy automatically."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = if (active) onRestore else onStart,
                enabled = active || canControl,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (active) Icons.Default.PowerSettingsNew else Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                )
                Text(
                    if (active) "Restore limit now" else "Charge to 100% once",
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PolicyCard(
    state: DashboardUiState,
    onApply: (ChargePolicy) -> Unit,
    onNativeSettings: () -> Unit,
) {
    val choices = listOf(
        ChargePolicy.FixedLimit(80) to "80%",
        ChargePolicy.Adaptive to "Adaptive",
        ChargePolicy.Unrestricted to "100%",
    )
    val selectedPolicy = state.charging.observation.policyOrNull()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Charging policy",
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
                    Text("Pixel settings", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { index, (policy, label) ->
                    SegmentedButton(
                        selected = selectedPolicy == policy,
                        onClick = { onApply(policy) },
                        enabled = state.charging.controlEnabled &&
                            state.charging.access?.canControl == true &&
                            !state.charging.busy,
                        shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    ) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFullChargeCard(
    enabled: Boolean,
    canControl: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    // When the gesture can neither be used nor turned off, dim the whole card so it reads as
    // disabled like the other controls, instead of showing a full-colour but inert toggle.
    val interactive = enabled || canControl
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .alpha(if (interactive) 1f else 0.38f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Reconnect for 100%",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled || canControl,
                )
            }
            Text(
                if (enabled) {
                    "At the 80% limit, unplug and reconnect within 10 seconds. An ongoing notification keeps the gesture reliable."
                } else {
                    "Optionally use a quick unplug/replug at the 80% limit to charge fully once."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShizukuBanner(
    running: Boolean,
    onOpen: () -> Unit,
    onAllow: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Better with Shizuku", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Shizuku gives Amply exact policy readback instead of relying on hardware state and the last request.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = if (running) onAllow else onOpen,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(if (running) "Allow access" else "Open Shizuku")
            }
        }
    }
}

private fun ChargeObservation.policyOrNull(): ChargePolicy? = when (this) {
    is ChargeObservation.Verified -> policy
    is ChargeObservation.LastRequested -> policy
    else -> null
}

private fun ChargeObservation.title(): String = when (this) {
    is ChargeObservation.Verified -> if (backend == BackendKind.BATTERY_HARDWARE) {
        "${policy.shortLabel()} active"
    } else {
        policy.shortLabel()
    }
    is ChargeObservation.LastRequested -> policy.shortLabel()
    is ChargeObservation.NeedsSetup -> "Setup required"
    is ChargeObservation.Unsupported -> "Unsupported device"
    is ChargeObservation.Unknown -> "Policy not verified"
}

private fun ChargeObservation.detail(): String = when (this) {
    is ChargeObservation.Verified -> if (backend == BackendKind.BATTERY_HARDWARE) {
        "Confirmed by Android's charging hardware"
    } else {
        "Read back through ${backend.name.replace('_', ' ').lowercase()}"
    }
    is ChargeObservation.LastRequested -> "Last requested by Amply; connect Shizuku for exact readback"
    is ChargeObservation.NeedsSetup -> reason
    is ChargeObservation.Unsupported -> reason
    is ChargeObservation.Unknown -> reason
}

private fun ChargePolicy.shortLabel(): String = when (this) {
    ChargePolicy.Adaptive -> "Adaptive charging"
    ChargePolicy.Unrestricted -> "100% charging"
    is ChargePolicy.FixedLimit -> "$percent% limit"
}
