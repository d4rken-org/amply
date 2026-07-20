package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Settings
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

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onGeneral: () -> Unit,
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
        onGeneral = {},
        showDiagnostics = true,
        diagnosticsReady = true,
        onDiagnostics = {},
        onSupport = {},
        onChangelog = {},
        onAcknowledgements = {},
        onPrivacy = {},
    )
}
