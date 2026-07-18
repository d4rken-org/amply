package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.Book
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Favorite
import androidx.compose.material.icons.twotone.History
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.SupportAgent
import androidx.compose.material.icons.twotone.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.amply.BuildConfig
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
    onTranslation: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text("Settings")
                        Text(
                            "Amply ${BuildConfig.VERSION_NAME}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                SettingsNavigationItem(
                    title = "General",
                    subtitle = "Theme and appearance",
                    icon = Icons.TwoTone.Settings,
                    onClick = onGeneral,
                )
            }
            item { SettingsDivider() }
            if (showDiagnostics) {
                item { SettingsCategoryHeader("Advanced") }
                item {
                    SettingsNavigationItem(
                        title = "Diagnostics",
                        subtitle = if (diagnosticsReady) {
                            "Charging-setting discovery · Shizuku ready"
                        } else {
                            "Charging-setting discovery · Shizuku setup required"
                        },
                        icon = Icons.TwoTone.BugReport,
                        onClick = onDiagnostics,
                    )
                }
                item { SettingsDivider() }
            }
            item { SettingsCategoryHeader("Other") }
            item {
                SettingsNavigationItem(
                    title = "Support",
                    subtitle = "Help, contact, and debug logs",
                    icon = Icons.TwoTone.SupportAgent,
                    onClick = onSupport,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = "Changelog",
                    subtitle = BuildConfig.VERSION_NAME,
                    icon = Icons.TwoTone.History,
                    onClick = onChangelog,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = "Acknowledgements",
                    subtitle = "Credits, licenses, and thanks",
                    icon = Icons.TwoTone.Favorite,
                    onClick = onAcknowledgements,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = "Privacy policy",
                    subtitle = "How Amply handles data",
                    icon = Icons.TwoTone.Book,
                    onClick = onPrivacy,
                )
            }
            item { SettingsDivider() }
            item {
                SettingsNavigationItem(
                    title = "Translation",
                    subtitle = "Help make Amply available in more languages",
                    icon = Icons.TwoTone.Translate,
                    onClick = onTranslation,
                )
            }
        }
    }
}
