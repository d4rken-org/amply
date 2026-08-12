package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * The dashboard's upgrade ask. Shown only while the entitlement is settled and absent (see
 * [shouldShowUpgradePromo]) — an unsettled state would flash it at a paying user on cold start.
 *
 * Deliberately states what stays free, because the card sits on a screen full of charge controls that
 * are not gated; a bare "Upgrade" would read as if they were.
 */
@Composable
fun UpgradePromoCard(
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AmplyCard(
        modifier = modifier,
        tone = AmplyCardTone.SecondaryContainer,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        AmplyCardHeader(
            title = stringResource(R.string.dashboard_upgrade_title),
            icon = Icons.TwoTone.Stars,
            iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            stringResource(R.string.dashboard_upgrade_body),
            style = MaterialTheme.typography.bodySmall,
        )
        FilledTonalButton(onClick = onUpgrade, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.dashboard_upgrade_action))
        }
    }
}

@AmplyPreview
@Composable
private fun UpgradePromoCardPreview() = PreviewWrapper {
    UpgradePromoCard(onUpgrade = {}, modifier = Modifier.padding(16.dp))
}
