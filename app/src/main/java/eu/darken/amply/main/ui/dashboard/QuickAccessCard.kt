package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.main.core.QuickAccessState
import eu.darken.amply.upgrade.ui.ProBadge

/**
 * Whether the quick-access promotion is rendered (within the supported-device branch). Hidden until
 * the initial widget-presence check completes — offering "Add widget" before knowing one already
 * exists could create a duplicate, since launchers allow multiple instances.
 */
internal fun shouldShowQuickAccess(
    canApply: Boolean,
    presenceChecked: Boolean,
    quickAccess: QuickAccessState,
): Boolean = canApply &&
    presenceChecked &&
    !quickAccess.dismissed &&
    !(quickAccess.widgetAdded && quickAccess.tileAdded)

@Composable
fun QuickAccessCard(
    widgetAdded: Boolean,
    tileAdded: Boolean,
    tileRequestPending: Boolean,
    // Settled-and-not-Pro only: an unsettled entitlement would badge the card at a paying user on
    // every cold start, while billing is still connecting.
    showProBadge: Boolean,
    onPinWidget: () -> Unit,
    onAddTile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AmplyCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        // One badge, at the header: both shortcuts need the same upgrade, so badging each button
        // said it twice and — inside buttons that already wrap their labels — cost the row the width
        // the labels need. The card is what is gated, so the card's title carries the marker.
        val badge: (@Composable () -> Unit)? = if (showProBadge) {
            { ProBadge() }
        } else {
            null
        }
        AmplyCardHeader(
            title = stringResource(R.string.dashboard_quickaccess_title),
            icon = Icons.Default.Widgets,
            titleAccessory = badge,
            trailing = {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.dashboard_quickaccess_dismiss),
                    )
                }
            },
        )
        Text(
            stringResource(R.string.dashboard_quickaccess_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Weighted so long labels wrap inside the buttons instead of overflowing the row; a
        // surface that is already added drops its button and the other expands to full width.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!widgetAdded) {
                FilledTonalButton(
                    onClick = onPinWidget,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dashboard_quickaccess_add_widget))
                }
            }
            if (!tileAdded) {
                FilledTonalButton(
                    onClick = onAddTile,
                    enabled = !tileRequestPending,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dashboard_quickaccess_add_tile))
                }
            }
        }
    }
}

@AmplyPreview
@Composable
private fun QuickAccessCardPreview() = PreviewWrapper {
    QuickAccessCard(
        widgetAdded = false,
        tileAdded = false,
        tileRequestPending = false,
        showProBadge = true,
        onPinWidget = {},
        onAddTile = {},
        onDismiss = {},
        modifier = Modifier.padding(16.dp),
    )
}

// Widget already on the home screen: only the tile action remains, at full width. Upgraded, so the
// remaining action carries no badge.
@AmplyPreview
@Composable
private fun QuickAccessCardWidgetAddedPreview() = PreviewWrapper {
    QuickAccessCard(
        widgetAdded = true,
        tileAdded = false,
        tileRequestPending = false,
        showProBadge = false,
        onPinWidget = {},
        onAddTile = {},
        onDismiss = {},
        modifier = Modifier.padding(16.dp),
    )
}

// The two squeezes the header has to survive with the badge in it: a narrow screen and a large font
// scale. The title must stay readable in full and the dismiss button must stay reachable, with the
// single badge between them.
@Preview(showBackground = true, name = "Compact width", widthDp = 320)
@Preview(showBackground = true, name = "Large font", fontScale = 1.5f)
@Composable
private fun QuickAccessCardTightPreview() = PreviewWrapper {
    QuickAccessCard(
        widgetAdded = false,
        tileAdded = false,
        tileRequestPending = false,
        showProBadge = true,
        onPinWidget = {},
        onAddTile = {},
        onDismiss = {},
        modifier = Modifier.padding(16.dp),
    )
}
