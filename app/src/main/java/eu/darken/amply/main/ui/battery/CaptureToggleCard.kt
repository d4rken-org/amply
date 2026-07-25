package eu.darken.amply.main.ui.battery

import android.text.format.DateUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyCardToggleIndicator
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.AmplyToggleCard
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * The charge-recording switch, at the top of the battery hub.
 *
 * It lives one level in from the dashboard on purpose. Enabling starts an always-on service with a
 * permanent notification, which is closer to a consent than a preference — not something a stray tap
 * on the dashboard should flip. The dashboard's charging card advertises the feature instead and
 * leads here, which is also why this is a toggle card rather than a switch bolted onto a navigation
 * card.
 */
@Composable
fun CaptureToggleCard(
    enabled: Boolean,
    lastCaptureWallMillis: Long?,
    onCaptureEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AmplyToggleCard(checked = enabled, onCheckedChange = onCaptureEnabledChange, modifier = modifier) {
        AmplyCardHeader(
            title = stringResource(R.string.stats_capture_title),
            trailing = { AmplyCardToggleIndicator(enabled) },
        )
        Text(
            stringResource(R.string.stats_capture_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled) {
            val lastText = lastCaptureWallMillis?.let {
                DateUtils.getRelativeTimeSpanString(it).toString()
            } ?: stringResource(R.string.stats_capture_never)
            Text(
                stringResource(R.string.stats_capture_last_recorded, lastText),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun CaptureToggleCardOnPreview() = PreviewWrapper {
    CaptureToggleCard(
        enabled = true,
        lastCaptureWallMillis = 0L,
        onCaptureEnabledChange = {},
    )
}

@AmplyPreview
@Composable
private fun CaptureToggleCardOffPreview() = PreviewWrapper {
    CaptureToggleCard(
        enabled = false,
        lastCaptureWallMillis = null,
        onCaptureEnabledChange = {},
    )
}
