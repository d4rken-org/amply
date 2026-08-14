package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.upgrade.ui.ProBadge

/**
 * The one-time opt-in for charge recording, at the top of the battery hub. Shown only while recording
 * is off; once it is on the card is gone, and the durable switch lives in Settings › Charging history.
 *
 * It stays one level in from the dashboard on purpose. Enabling starts an always-on service with a
 * permanent notification, which is closer to a consent than a preference — not something a stray tap
 * on the dashboard should flip. The dashboard's charging card advertises the feature and leads here.
 * An explicit button rather than a switch, for the same reason: a card that only ever turns recording
 * *on* shouldn't present itself as the control you come back to in order to turn it off.
 */
@Composable
fun CaptureOptInCard(
    onEnable: () -> Unit,
    // Settled-and-not-Pro only: an unsettled entitlement would badge the card at a paying user on
    // every cold start, while billing is still connecting.
    showProBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    AmplyCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        // The badge qualifies the feature, so it belongs next to the card's title. Inside the button
        // it sat behind the label, where it read as part of the action rather than as a condition of
        // it, and it stretched the button past its label.
        val badge: (@Composable () -> Unit)? = if (showProBadge) {
            { ProBadge() }
        } else {
            null
        }
        AmplyCardHeader(
            title = stringResource(R.string.stats_capture_title),
            titleAccessory = badge,
        )
        Text(
            stringResource(R.string.stats_capture_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.stats_capture_optin_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onEnable, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.stats_capture_optin_action))
        }
    }
}

// Gated: the badge sits beside the title, the action below is text-only.
@AmplyPreview
@Composable
private fun CaptureOptInCardPreview() = PreviewWrapper {
    CaptureOptInCard(onEnable = {}, showProBadge = true)
}

// Upgraded (or the entitlement not settled yet): the header carries no badge.
@AmplyPreview
@Composable
private fun CaptureOptInCardProPreview() = PreviewWrapper {
    CaptureOptInCard(onEnable = {}, showProBadge = false)
}

// The header's tightest cases with the badge in it: the title must stay readable in full.
@Preview(showBackground = true, name = "Compact width", widthDp = 320)
@Preview(showBackground = true, name = "Large font", fontScale = 1.5f)
@Composable
private fun CaptureOptInCardTightPreview() = PreviewWrapper {
    CaptureOptInCard(onEnable = {}, showProBadge = true)
}
