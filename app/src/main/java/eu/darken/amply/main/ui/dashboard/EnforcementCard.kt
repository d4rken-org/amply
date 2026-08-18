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
import androidx.compose.material3.TextButton
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
 * that is the ordinary case, and a card stating the ordinary case is noise. The cases it does render
 * are the ones where the dashboard would otherwise lie by omission: controls withheld without a
 * reason, controls offered that prove nothing, controls withdrawn after a refutation, and controls
 * the user earned themselves on this one build rather than the maintainer having qualified the
 * device.
 *
 * [onStartVerification] is an opt-in to control on an unconfirmed build, NOT the start of a check:
 * nothing Amply can observe confirms a cap (see `EnforcementVerdictEngine`), so the copy must not
 * promise a verdict that never arrives. The callback keeps its name alongside the stored
 * `enforcement.verification_started_for` opt-in it drives.
 */
@Composable
fun EnforcementCard(
    status: EnforcementStatus,
    onStartVerification: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the guided qualification run. Defaulted so the dashboard's many preview fixtures need no
     * change; the composition root passes the real one.
     */
    onProveLimit: () -> Unit = {},
    /** Whether the guided run is available in this build (`BuildConfig.ENABLE_QUALIFICATION_RUN`). */
    proveLimitAvailable: Boolean = false,
) {
    when (status) {
        EnforcementStatus.CONFIRMED -> Unit
        // A quiet confirmation rather than nothing: unlike CONFIRMED, this claim is the user's own
        // and is scoped to one build, so the card says which build it applies to instead of letting
        // the dashboard imply the device is supported outright.
        EnforcementStatus.SELF_QUALIFIED -> AmplyCard(
            modifier = modifier.testTag(ENFORCEMENT_CARD_TEST_TAG),
            tone = AmplyCardTone.SurfaceContainer,
            verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
        ) {
            AmplyCardHeader(
                title = stringResource(R.string.dashboard_enforcement_self_qualified_title),
                icon = Icons.TwoTone.Shield,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.dashboard_enforcement_self_qualified_body),
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
            // Where the guided run exists it is the primary action: it can actually answer the
            // question this card is about, while the opt-in only accepts not knowing. So the opt-in
            // demotes to a text button rather than disappearing — the run needs the charger connected
            // for up to an hour and a half, and someone who just wants their limit back now should
            // not be forced through it.
            if (proveLimitAvailable) {
                Button(onClick = onProveLimit, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.dashboard_enforcement_prove_action))
                }
                TextButton(onClick = onStartVerification, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.dashboard_enforcement_candidate_action))
                }
            } else {
                Button(onClick = onStartVerification, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.dashboard_enforcement_candidate_action))
                }
            }
        }
        EnforcementStatus.UNVERIFIED -> AmplyCard(
            modifier = modifier.testTag(ENFORCEMENT_CARD_TEST_TAG),
            tone = AmplyCardTone.TertiaryContainer,
            verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
        ) {
            AmplyCardHeader(
                title = stringResource(R.string.dashboard_enforcement_unverified_title),
                icon = Icons.Default.Science,
                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                stringResource(R.string.dashboard_enforcement_unverified_body),
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
        EnforcementCard(status = EnforcementStatus.UNVERIFIED, onStartVerification = {})
        EnforcementCard(status = EnforcementStatus.REFUTED, onStartVerification = {})
    }
}
