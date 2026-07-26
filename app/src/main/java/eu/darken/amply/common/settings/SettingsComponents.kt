package eu.darken.amply.common.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import kotlin.math.roundToInt

@Composable
fun SettingsBaseItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (icon != null) 16.dp else 0.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            subtitle?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent?.invoke()
    }
}

@Composable
fun SettingsNavigationItem(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    onClick: () -> Unit,
) = SettingsBaseItem(
    title = title,
    subtitle = subtitle,
    icon = icon,
    onClick = onClick,
    trailingContent = {
        Icon(
            Icons.AutoMirrored.TwoTone.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
)

@Composable
fun SettingsPreferenceItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) = SettingsBaseItem(title = title, subtitle = value, icon = icon, onClick = onClick)

@Composable
fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) = SettingsBaseItem(
    title = title,
    subtitle = subtitle,
    icon = icon,
    enabled = enabled,
    onClick = { onCheckedChange(!checked) },
    trailingContent = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
)

/**
 * A discrete whole-number slider row. Deliberately not a [SettingsBaseItem]: the row itself is not a
 * click target — the track is — so it lays out its own title line (icon + title + [valueLabel]) and
 * puts the slider beneath, inset to line up with the title.
 *
 * The dragged value is local state and is committed **only on release**, and only when it actually
 * changed. Material's [Slider] fires `onValueChangeFinished` for a plain press-and-release on the
 * thumb too, so without that guard a no-op gesture would write a preference and kick off whatever
 * work the change triggers. [value] changing from outside re-syncs the local state.
 *
 * [valueLabel] formats the **live** dragged value, not [value] — a caller must not pre-format the
 * persisted number, or the label would sit frozen on the old value for the whole gesture.
 */
@Composable
fun SettingsSliderItem(
    title: String,
    valueLabel: @Composable (Int) -> String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    var dragged by remember(value) { mutableStateOf(value) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (icon != null) 16.dp else 0.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                subtitle?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                valueLabel(dragged),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = dragged.toFloat(),
            onValueChange = { dragged = it.roundToInt() },
            onValueChangeFinished = { if (dragged != value) onValueChange(dragged) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // One stop per whole step, ends excluded.
            steps = (range.count() - 2).coerceAtLeast(0),
            modifier = Modifier.padding(start = if (icon != null) 40.dp else 0.dp),
        )
    }
}

@Composable
fun SettingsCategoryHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SettingsDivider(hasIcon: Boolean = true) {
    HorizontalDivider(
        modifier = Modifier.padding(start = if (hasIcon) 56.dp else 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@AmplyPreview
@Composable
private fun SettingsComponentsPreview() = PreviewWrapper {
    Column {
        SettingsCategoryHeader("Appearance")
        SettingsNavigationItem(
            title = "Theme",
            subtitle = "Branded green",
            icon = Icons.Default.Palette,
            onClick = {},
        )
        SettingsDivider()
        SettingsPreferenceItem(
            title = "Contrast",
            value = "Standard",
            icon = Icons.Default.Palette,
            onClick = {},
        )
        SettingsDivider()
        SettingsSwitchItem(
            title = "Quick full charge",
            subtitle = "Reconnect at 80% to charge fully once",
            checked = true,
            onCheckedChange = {},
            icon = Icons.Default.Palette,
        )
        SettingsDivider()
        SettingsSliderItem(
            title = "Keep history for",
            valueLabel = { days -> "$days days" },
            value = 7,
            range = 3..14,
            onValueChange = {},
            subtitle = "Charges older than this are deleted automatically.",
            icon = Icons.Default.Palette,
        )
    }
}
