package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCardActionLabel
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyClickableCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * The dashboard's upgrade ask. Shown only while the entitlement is settled and absent (see
 * [shouldShowUpgradePromo]) — an unsettled state would flash it at a paying user on cold start.
 *
 * The copy names no features: the upgrade screen is the single source of truth for what the upgrade
 * contains, so this card cannot drift out of sync with it.
 *
 * The whole card is the tap target: it has exactly one action, and a card-sized ask whose only live
 * target was a small button in its corner made the rest of the card look inert. The action label
 * below is therefore just a label — the surface owns the tap.
 */
@Composable
fun UpgradePromoCard(
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AmplyClickableCard(
        onClick = onUpgrade,
        onClickLabel = stringResource(R.string.dashboard_upgrade_action),
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
        AmplyCardActionLabel(
            text = stringResource(R.string.dashboard_upgrade_action),
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@AmplyPreview
@Composable
private fun UpgradePromoCardPreview() = PreviewWrapper {
    UpgradePromoCard(onUpgrade = {}, modifier = Modifier.padding(16.dp))
}
