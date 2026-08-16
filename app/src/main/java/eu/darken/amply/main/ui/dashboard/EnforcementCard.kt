package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.twotone.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.enforcement.EnforcementStatus
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

const val ENFORCEMENT_CARD_TEST_TAG = "dashboard.enforcement.card"

/**
 * Explains the enforcement tier on adapters where a charge limit can be written and read back
 * perfectly while the charging hardware ignores it (LineageOS).
 *
 * Nothing is shown for [EnforcementStatus.CONFIRMED] or for adapters the question doesn't apply to —
 * that is the ordinary case, and a card stating the ordinary case is noise. The three cases it does
 * render are the ones where the dashboard would otherwise lie by omission: controls withheld without
 * a reason, controls offered that prove nothing, and controls withdrawn after a refutation.
 */
@Composable
fun EnforcementCard(
    status: EnforcementStatus,
    onStartVerification: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        EnforcementStatus.CONFIRMED -> Unit
        EnforcementStatus.CANDIDATE -> AmplyCard(
            modifier = modifier.testTag(ENFORCEMENT_CARD_TEST_TAG),
            tone = AmplyCardTone.TertiaryContainer,
            verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
        ) {
            AmplyCardHeader(
                title = stringResource(R.string.dashboard_enforcement_candidate_title),
                icon = Icons.TwoTone.Shield,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(R.string.dashboard_enforcement_candidate_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onStartVerification, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dashboard_enforcement_candidate_action))
            }
        }
        EnforcementStatus.UNDER_TEST -> AmplyCard(
            modifier = modifier.testTag(ENFORCEMENT_CARD_TEST_TAG),
            tone = AmplyCardTone.TertiaryContainer,
            verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
        ) {
            AmplyCardHeader(
                title = stringResource(R.string.dashboard_enforcement_testing_title),
                icon = Icons.Default.Science,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(R.string.dashboard_enforcement_testing_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // No contribution action here: a refuted device also flips contributionWanted, so the
        // dashboard's unsupported-device card already carries the report affordance below.
        EnforcementStatus.REFUTED -> AmplyCard(
            modifier = modifier.testTag(ENFORCEMENT_CARD_TEST_TAG),
            tone = AmplyCardTone.SurfaceContainer,
            verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
        ) {
            AmplyCardHeader(
                title = stringResource(R.string.dashboard_enforcement_refuted_title),
                icon = Icons.Default.WarningAmber,
                iconTint = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(R.string.dashboard_enforcement_refuted_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun EnforcementCardPreview() = PreviewWrapper {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EnforcementCard(status = EnforcementStatus.CANDIDATE, onStartVerification = {})
        EnforcementCard(status = EnforcementStatus.UNDER_TEST, onStartVerification = {})
        EnforcementCard(status = EnforcementStatus.REFUTED, onStartVerification = {})
    }
}
