package eu.darken.amply.main.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.twotone.BatteryChargingFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * Shown on unsupported/diagnostics-only devices: Amply can't control charge protection directly
 * here, so this points the user at the OEM's own built-in setting with brief, honest guidance and
 * a deep link. Instructions are keyed by **manufacturer**, not adapter id, because a live-but-
 * uncontrollable adapter (e.g. Samsung with an absent key) still reports an OEM-specific id.
 */
@Composable
fun OemGuideCard(
    manufacturer: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isLineageOs: Boolean = false,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.TwoTone.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(R.string.setup_oem_guide_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                stringResource(oemInstructions(manufacturer, isLineageOs)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FilledTonalButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(
                    stringResource(R.string.setup_oem_guide_open_action),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Keyed by manufacturer, except LineageOS wins first: a custom ROM replaces the OEM's charge feature
 * with its own "Charging control", so the OEM-specific wording (e.g. Pixel's "charging optimization")
 * would point the user at a feature name that isn't there.
 */
@StringRes
private fun oemInstructions(manufacturer: String, isLineageOs: Boolean): Int = when {
    isLineageOs -> R.string.setup_oem_guide_lineageos
    manufacturer.equals("samsung", ignoreCase = true) -> R.string.setup_oem_guide_samsung
    manufacturer.equals("oneplus", ignoreCase = true) ||
        manufacturer.equals("oppo", ignoreCase = true) -> R.string.setup_oem_guide_oneplus
    manufacturer.equals("xiaomi", ignoreCase = true) -> R.string.setup_oem_guide_xiaomi
    manufacturer.equals("google", ignoreCase = true) -> R.string.setup_oem_guide_google
    else -> R.string.setup_oem_guide_generic
}

@AmplyPreview
@Composable
private fun OemGuideCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OemGuideCard(manufacturer = "Samsung", onOpenSettings = {})
        OemGuideCard(manufacturer = "Google", isLineageOs = true, onOpenSettings = {})
    }
}
