package eu.darken.amply.main.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ArrowBack
import androidx.compose.material.icons.twotone.ColorLens
import androidx.compose.material.icons.twotone.Contrast
import androidx.compose.material.icons.twotone.DarkMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.settings.SettingsCategoryHeader
import eu.darken.amply.common.settings.SettingsDivider
import eu.darken.amply.common.settings.SettingsPreferenceItem
import eu.darken.amply.common.theming.ThemeColor
import eu.darken.amply.common.theming.ThemeMode
import eu.darken.amply.common.theming.ThemeState
import eu.darken.amply.common.theming.ThemeStyle

@Composable
fun GeneralSettingsScreen(
    state: ThemeState,
    onBack: () -> Unit,
    onModeChange: (ThemeMode) -> Unit,
    onStyleChange: (ThemeStyle) -> Unit,
    onColorChange: (ThemeColor) -> Unit,
) {
    var dialog by remember { mutableStateOf<ThemeDialog?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("General") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.TwoTone.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            SettingsCategoryHeader("Appearance")
            SettingsPreferenceItem(
                title = "Theme mode",
                value = state.mode.label,
                icon = Icons.TwoTone.DarkMode,
                onClick = { dialog = ThemeDialog.MODE },
            )
            SettingsDivider()
            SettingsPreferenceItem(
                title = "Theme style",
                value = state.style.label,
                icon = Icons.TwoTone.Contrast,
                onClick = { dialog = ThemeDialog.STYLE },
            )
            SettingsDivider()
            SettingsPreferenceItem(
                title = "Accent color",
                value = if (state.style == ThemeStyle.MATERIAL_YOU) {
                    "Controlled by Material You"
                } else {
                    state.color.label
                },
                icon = Icons.TwoTone.ColorLens,
                onClick = { if (state.style != ThemeStyle.MATERIAL_YOU) dialog = ThemeDialog.COLOR },
            )
        }
    }

    when (dialog) {
        ThemeDialog.MODE -> ChoiceDialog(
            title = "Theme mode",
            options = ThemeMode.entries.map { it.label to (it == state.mode) },
            onSelect = { onModeChange(ThemeMode.entries[it]); dialog = null },
            onDismiss = { dialog = null },
        )
        ThemeDialog.STYLE -> ChoiceDialog(
            title = "Theme style",
            options = ThemeStyle.entries.map { it.label to (it == state.style) },
            onSelect = { onStyleChange(ThemeStyle.entries[it]); dialog = null },
            onDismiss = { dialog = null },
        )
        ThemeDialog.COLOR -> ChoiceDialog(
            title = "Accent color",
            options = ThemeColor.entries.map { it.label to (it == state.color) },
            onSelect = { onColorChange(ThemeColor.entries[it]); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, (label, selected) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(index) })
                        Text(label, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private enum class ThemeDialog { MODE, STYLE, COLOR }
