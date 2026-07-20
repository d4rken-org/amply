package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider

@Composable
fun AcknowledgementsScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(
        topBar = { SettingsTopBar(stringResource(R.string.settings_acknowledgements_title), onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SettingsCategoryHeader(stringResource(R.string.settings_ack_thanks)) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_fabian_title),
                    subtitle = stringResource(R.string.settings_ack_fabian_subtitle),
                    onClick = { onOpenUrl("https://code-consulting.de/") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_aosp_title),
                    subtitle = stringResource(R.string.settings_ack_aosp_subtitle),
                    onClick = { onOpenUrl("https://source.android.com") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_shizuku_title),
                    subtitle = stringResource(R.string.settings_ack_shizuku_subtitle),
                    onClick = { onOpenUrl("https://github.com/RikkaApps/Shizuku") },
                )
            }
            item { SettingsCategoryHeader(stringResource(R.string.settings_ack_libraries)) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_kotlin_title),
                    subtitle = stringResource(R.string.settings_ack_kotlin_subtitle),
                    onClick = { onOpenUrl("https://github.com/JetBrains/kotlin") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_compose_title),
                    subtitle = stringResource(R.string.settings_ack_compose_subtitle),
                    onClick = { onOpenUrl("https://github.com/androidx/androidx") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = stringResource(R.string.settings_ack_dagger_title),
                    subtitle = stringResource(R.string.settings_ack_dagger_subtitle),
                    onClick = { onOpenUrl("https://github.com/google/dagger") },
                )
            }
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.TwoTone.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    )
}

@AmplyPreview
@Composable
private fun AcknowledgementsScreenPreview() = PreviewWrapper {
    AcknowledgementsScreen(onBack = {}, onOpenUrl = {})
}
