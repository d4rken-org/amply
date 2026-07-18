package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.BuildConfig
import eu.darken.amply.common.settings.SettingsBaseItem
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider

@Composable
fun ChangelogScreen(onBack: () -> Unit) = InfoScreen(title = "Changelog", onBack = onBack) {
    Text(
        "Amply ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Text("Current development build", color = MaterialTheme.colorScheme.primary)
    InfoBullet("Feature-first package organization")
    InfoBullet("First-run access setup and reusable missing-permission guide")
    InfoBullet("Branded light/dark themes with Material You and contrast options")
    InfoBullet("Hierarchical settings and support screens")
    InfoBullet("Opt-in debug-log recording and sharing")
    InfoBullet("Reconnect at the 80% policy hold to charge fully once")
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) = InfoScreen(title = "Privacy policy", onBack = onBack) {
    Text("Amply works locally on your device.", style = MaterialTheme.typography.titleMedium)
    Text("It contains no analytics, advertising, account system, or automatic data transmission.")
    Text(
        "Charging settings are accessed only through permissions you explicitly grant. Diagnostic comparisons " +
            "remain local until you choose a share target.",
    )
    Text(
        "Debug recording is opt-in. It records app events, device and Android versions, and control results in " +
            "private app storage. Recordings are shared only through Android's share sheet and can be deleted at any time.",
    )
    Text(
        "The foreground service observes battery and cable state only for one-time full charging, automatic restoration, " +
            "and the optional reconnect gesture.",
    )
    Text("Last updated: 2026-07-17", style = MaterialTheme.typography.labelLarge)
}

@Composable
fun AcknowledgementsScreen(
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(
        topBar = { SettingsTopBar("Acknowledgements", onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SettingsCategoryHeader("Thank you") }
            item {
                SettingsBaseItem(
                    title = "Android open-source community",
                    subtitle = "The platform and tools Amply is built on",
                    onClick = { onOpenUrl("https://source.android.com") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = "Shizuku",
                    subtitle = "Privileged API and permission bridge · Apache 2.0",
                    onClick = { onOpenUrl("https://github.com/RikkaApps/Shizuku") },
                )
            }
            item { SettingsCategoryHeader("Libraries") }
            item {
                SettingsBaseItem(
                    title = "Kotlin",
                    subtitle = "JetBrains · Apache 2.0",
                    onClick = { onOpenUrl("https://github.com/JetBrains/kotlin") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = "Jetpack Compose and AndroidX",
                    subtitle = "Google · Apache 2.0",
                    onClick = { onOpenUrl("https://github.com/androidx/androidx") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
            item {
                SettingsBaseItem(
                    title = "Dagger and Hilt",
                    subtitle = "Google · Apache 2.0",
                    onClick = { onOpenUrl("https://github.com/google/dagger") },
                )
            }
        }
    }
}

@Composable
private fun InfoScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(topBar = { SettingsTopBar(title, onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(20.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun InfoBullet(text: String) {
    Text("•  $text", style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = "Back")
            }
        },
    )
}
