package eu.darken.amply.main.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import eu.darken.amply.main.core.QuickAccessState

/**
 * Whether the quick-access promotion is rendered (within the supported-device branch). Hidden until
 * the initial widget-presence check completes — offering "Add widget" before knowing one already
 * exists could create a duplicate, since launchers allow multiple instances.
 */
internal fun shouldShowQuickAccess(
    directReady: Boolean,
    presenceChecked: Boolean,
    quickAccess: QuickAccessState,
): Boolean = directReady &&
    presenceChecked &&
    !quickAccess.dismissed &&
    !(quickAccess.widgetAdded && quickAccess.tileAdded)

@Composable
fun QuickAccessCard(
    widgetAdded: Boolean,
    tileAdded: Boolean,
    tileRequestPending: Boolean,
    onPinWidget: () -> Unit,
    onAddTile: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Widgets,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.dashboard_quickaccess_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.dashboard_quickaccess_dismiss),
                    )
                }
            }
            Text(
                stringResource(R.string.dashboard_quickaccess_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
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
}

@AmplyPreview
@Composable
private fun QuickAccessCardPreview() = PreviewWrapper {
    QuickAccessCard(
        widgetAdded = false,
        tileAdded = false,
        tileRequestPending = false,
        onPinWidget = {},
        onAddTile = {},
        onDismiss = {},
        modifier = Modifier.padding(16.dp),
    )
}

// Widget already on the home screen: only the tile action remains, at full width.
@AmplyPreview
@Composable
private fun QuickAccessCardWidgetAddedPreview() = PreviewWrapper {
    QuickAccessCard(
        widgetAdded = true,
        tileAdded = false,
        tileRequestPending = false,
        onPinWidget = {},
        onAddTile = {},
        onDismiss = {},
        modifier = Modifier.padding(16.dp),
    )
}
