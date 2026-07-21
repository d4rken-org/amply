package eu.darken.amply.main.ui.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
            Text(
                stringResource(oemInstructions(manufacturer)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FilledTonalButton(onClick = onOpenSettings) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text(
                    stringResource(R.string.setup_oem_guide_open_action),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@StringRes
private fun oemInstructions(manufacturer: String): Int = when (manufacturer.lowercase()) {
    "samsung" -> R.string.setup_oem_guide_samsung
    "oneplus", "oppo" -> R.string.setup_oem_guide_oneplus
    "xiaomi" -> R.string.setup_oem_guide_xiaomi
    "google" -> R.string.setup_oem_guide_google
    else -> R.string.setup_oem_guide_generic
}

@AmplyPreview
@Composable
private fun OemGuideCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OemGuideCard(manufacturer = "Samsung", onOpenSettings = {})
        OemGuideCard(manufacturer = "SomeOtherBrand", onOpenSettings = {})
    }
}
