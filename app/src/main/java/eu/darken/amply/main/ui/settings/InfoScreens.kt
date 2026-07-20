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
        topBar = { SettingsTopBar("Acknowledgements", onBack) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SettingsCategoryHeader("Thank you") }
            item {
                SettingsBaseItem(
                    title = "Fabian",
                    subtitle = "For the initial app idea",
                    onClick = { onOpenUrl("https://code-consulting.de/") },
                )
            }
            item { SettingsDivider(hasIcon = false) }
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

@AmplyPreview
@Composable
private fun AcknowledgementsScreenPreview() = PreviewWrapper {
    AcknowledgementsScreen(onBack = {}, onOpenUrl = {})
}
