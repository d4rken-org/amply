package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.automirrored.twotone.ShowChart
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Rule
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material.icons.twotone.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.amply.BuildConfig
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider
import eu.darken.amply.common.settings.SettingsNavigationItem
import eu.darken.amply.upgrade.ui.ProBadge
import eu.darken.amply.upgrade.ui.brandTitleText

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    isPro: Boolean,
    // Settled-and-not-Pro, which is NOT just `!isPro`: an entitlement that hasn't resolved yet must
    // not badge gated rows at a paying user on cold start.
    showProBadge: Boolean,
    onUpgrade: () -> Unit,
    onGeneral: () -> Unit,
    gestureEnabled: Boolean,
    onCharging: () -> Unit,
    activeRuleCount: Int,
    onChargeRules: () -> Unit,
    captureEnabled: Boolean,
    onChargingHistory: () -> Unit,
    showDiagnostics: Boolean,
    diagnosticsReady: Boolean,
    onDiagnostics: () -> Unit,
    onSupport: () -> Unit,
    onChangelog: () -> Unit,
    onAcknowledgements: () -> Unit,
    onPrivacy: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(stringResource(R.string.settings_title))
                        Text(
                            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.TwoTone.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_general_title),
                    subtitle = stringResource(R.string.settings_general_subtitle),
                    icon = Icons.TwoTone.Settings,
                    onClick = onGeneral,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_charging_title),
                    subtitle = if (gestureEnabled) {
                        stringResource(R.string.settings_charging_subtitle_on)
                    } else {
                        stringResource(R.string.settings_charging_subtitle_off)
                    },
                    icon = Icons.TwoTone.Bolt,
                    onClick = onCharging,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_rules_title),
                    subtitle = if (activeRuleCount > 0) {
                        stringResource(R.string.settings_rules_subtitle_on, activeRuleCount)
                    } else {
                        stringResource(R.string.settings_rules_subtitle_off)
                    },
                    icon = Icons.TwoTone.Rule,
                    onClick = onChargeRules,
                    // The row stays open to everyone: switching a rule off, or deleting it, must
                    // never sit behind the gate.
                    trailingContent = { if (showProBadge) ProBadge() },
                )
            }
            item { SettingsDivider() }
            // Charge recording is opted into once, from the battery hub's card — so the durable on/off
            // control and the retention window belong here, not next to the data they produce.
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_charging_history_title),
                    subtitle = if (captureEnabled) {
                        stringResource(R.string.settings_charging_history_subtitle_on)
                    } else {
                        stringResource(R.string.settings_charging_history_subtitle_off)
                    },
                    icon = Icons.AutoMirrored.TwoTone.ShowChart,
                    onClick = onChargingHistory,
                    // The row itself stays open to everyone: turning recording *off*, and the
                    // retention window for data already recorded, must never sit behind the gate.
                    trailingContent = { if (showProBadge) ProBadge() },
                )
            }
            item { SettingsDivider() }
            if (showDiagnostics) {
                item { SettingsCategoryHeader(stringResource(R.string.settings_category_advanced)) }
                item {
                    SettingsNavigationItem(
                        title = stringResource(R.string.settings_diagnostics_title),
                        subtitle = if (diagnosticsReady) {
                            stringResource(R.string.settings_diagnostics_subtitle_ready)
                        } else {
                            stringResource(R.string.settings_diagnostics_subtitle_setup)
                        },
                        icon = Icons.TwoTone.BugReport,
                        onClick = onDiagnostics,
                    )
                }
                item { SettingsDivider() }
            }
            item { SettingsCategoryHeader(stringResource(R.string.settings_category_other)) }
            // Heads the "Other" category rather than the whole screen: it is the upgrade entry point
            // and the place a user checks when they wonder whether their purchase applied, but it
            // configures nothing — above the actual settings it read as the app's first preference.
            item {
                SettingsNavigationItem(
                    title = brandTitleText(includeQualifier = true),
                    subtitle = if (isPro) {
                        stringResource(R.string.settings_pro_subtitle_active)
                    } else {
                        stringResource(R.string.settings_pro_subtitle_free)
                    },
                    icon = Icons.TwoTone.Stars,
                    onClick = onUpgrade,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_support_title),
                    subtitle = stringResource(R.string.settings_support_subtitle),
                    icon = Icons.TwoTone.SupportAgent,
                    onClick = onSupport,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_changelog_title),
                    subtitle = BuildConfig.VERSION_NAME,
                    icon = Icons.TwoTone.History,
                    onClick = onChangelog,
                    trailingContent = { ExternalLinkIcon() },
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = stringResource(R.string.settings_acknowledgements_title),
                    subtitle = stringResource(R.string.settings_acknowledgements_subtitle),
                    icon = Icons.TwoTone.Favorite,
                    onClick = onAcknowledgements,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_privacy_title),
                    subtitle = stringResource(R.string.settings_privacy_subtitle),
                    icon = Icons.TwoTone.Book,
                    onClick = onPrivacy,
                    trailingContent = { ExternalLinkIcon() },
                )
            }
        }
    }
}

@Composable
private fun ExternalLinkIcon() {
    Icon(
        Icons.AutoMirrored.TwoTone.OpenInNew,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@AmplyPreview
@Composable
private fun SettingsScreenPreview() = PreviewWrapper {
    SettingsScreen(
        onBack = {},
        isPro = false,
        showProBadge = true,
        onUpgrade = {},
        onGeneral = {},
        gestureEnabled = true,
        onCharging = {},
        activeRuleCount = 2,
        onChargeRules = {},
        captureEnabled = true,
        onChargingHistory = {},
        showDiagnostics = true,
        diagnosticsReady = true,
        onDiagnostics = {},
        onSupport = {},
        onChangelog = {},
        onAcknowledgements = {},
        onPrivacy = {},
    )
}

// Upgraded: the tier row states it, and the gated rows drop their badge.
@AmplyPreview
@Composable
private fun SettingsScreenUpgradedPreview() = PreviewWrapper {
    SettingsScreen(
        onBack = {},
        isPro = true,
        showProBadge = false,
        onUpgrade = {},
        onGeneral = {},
        gestureEnabled = true,
        onCharging = {},
        activeRuleCount = 2,
        onChargeRules = {},
        captureEnabled = true,
        onChargingHistory = {},
        showDiagnostics = false,
        diagnosticsReady = false,
        onDiagnostics = {},
        onSupport = {},
        onChangelog = {},
        onAcknowledgements = {},
        onPrivacy = {},
    )
}
