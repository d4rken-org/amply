package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.stats.core.ChargeBandSplit
import eu.darken.amply.stats.core.ChargeTimeBasis
import eu.darken.amply.stats.core.ChargeTimeEstimate
import eu.darken.amply.stats.ui.ChargeTimeState
import eu.darken.amply.stats.ui.StatsFormat

/**
 * How long a charge takes on this device, projected from the user's own recorded charges.
 *
 * Every number here is history, never live extrapolation, and every absent number says so rather
 * than being filled in: a null target means Amply has not watched enough charges across that stretch,
 * which is a different statement from "zero minutes".
 *
 * The wording is gated on the battery actually taking charge. A countdown ("Full in about 1h 19m")
 * is a claim that the device is moving toward that target; unplugged, or held at an OEM limit — which
 * reports NOT_CHARGING while the charge session is still open — the same figures are presented as a
 * reference for what a charge from here usually costs.
 */
@Composable
fun ChargeTimeCard(
    state: ChargeTimeState,
    modifier: Modifier = Modifier,
) {
    AmplyCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        AmplyCardHeader(
            title = stringResource(R.string.charge_time_title),
            icon = Icons.Filled.Schedule,
        )
        when (state) {
            ChargeTimeState.Loading -> Body(stringResource(R.string.charge_time_loading))
            ChargeTimeState.Unavailable -> Body(stringResource(R.string.charge_time_unavailable))
            is ChargeTimeState.NotEnoughData -> Body(stringResource(R.string.charge_time_not_enough))
            is ChargeTimeState.Ready -> ReadyBody(state)
        }
    }
}

@Composable
private fun ColumnScope.ReadyBody(state: ChargeTimeState.Ready) {
    val estimate = state.estimate
    Text(
        headline(state),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
    )

    val missing = stringResource(R.string.charge_time_value_missing)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Omitted rather than rendered as "0m": at 80% and above there is nothing left to count.
        if ((state.currentPercent ?: 0) < 80) {
            Figure(
                label = stringResource(R.string.charge_time_to_eighty),
                value = StatsFormat.duration(estimate.toEightyMillis) ?: missing,
            )
        }
        Figure(
            label = stringResource(R.string.charge_time_to_full),
            value = StatsFormat.duration(estimate.toFullMillis) ?: missing,
        )
        Figure(
            label = stringResource(R.string.charge_time_avg_speed),
            value = StatsFormat.power(estimate.avgSpeedMilliwatts) ?: missing,
        )
    }

    if (estimate.split.hasAny) {
        SplitBar(estimate.split)
    }
    if (estimate.split.trickleDominates) {
        Text(
            stringResource(R.string.charge_time_trickle_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        pluralStringResource(
            if (state.basis == ChargeTimeBasis.SAME_TYPE) {
                R.plurals.charge_time_provenance_same_type
            } else {
                R.plurals.charge_time_provenance_pooled
            },
            estimate.basedOnSessions,
            estimate.basedOnSessions,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The headline target: to full where that is known, else to 80%, else nothing to state.
 *
 * The countdown/reference split is the honesty rule — see [ChargeTimeCard].
 */
@Composable
private fun headline(state: ChargeTimeState.Ready): String {
    val toFull = StatsFormat.duration(state.estimate.toFullMillis)
    if (toFull != null) {
        return stringResource(
            if (state.charging) {
                R.string.charge_time_headline_full_countdown
            } else {
                R.string.charge_time_headline_full_reference
            },
            toFull,
        )
    }
    val toEighty = StatsFormat.duration(state.estimate.toEightyMillis)
    if (toEighty != null) {
        return stringResource(
            if (state.charging) {
                R.string.charge_time_headline_eighty_countdown
            } else {
                R.string.charge_time_headline_eighty_reference
            },
            toEighty,
        )
    }
    return stringResource(R.string.charge_time_value_missing)
}

/**
 * The 80-100% stretch is worth calling out only when it actually dominates: the note explains a
 * taper, so it must not appear beside a bar where the taper is unremarkable.
 */
private val ChargeBandSplit.trickleDominates: Boolean
    get() {
        val tail = eightyToHundredMillis ?: return false
        val middle = fiftyToEightyMillis ?: return false
        return tail >= middle * 3 / 2
    }

@Composable
private fun ColumnScope.SplitBar(split: ChargeBandSplit) {
    val segments = listOfNotNull(
        split.toFiftyMillis?.let { Triple(it, MaterialTheme.colorScheme.primary, R.string.charge_time_split_to_fifty) },
        split.fiftyToEightyMillis?.let {
            Triple(it, MaterialTheme.colorScheme.tertiary, R.string.charge_time_split_fifty_eighty)
        },
        split.eightyToHundredMillis?.let {
            Triple(it, MaterialTheme.colorScheme.secondary, R.string.charge_time_split_eighty_hundred)
        },
    )
    if (segments.isEmpty()) return

    Text(
        stringResource(R.string.charge_time_split_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(MaterialTheme.shapes.small),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { (millis, color, _) ->
            Bar(weight = millis.coerceAtLeast(1L).toFloat(), color = color)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEach { (millis, color, labelRes) ->
            Column(modifier = Modifier.weight(millis.coerceAtLeast(1L).toFloat())) {
                Text(
                    stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    StatsFormat.duration(millis) ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RowScope.Bar(weight: Float, color: Color) = Column(
    modifier = Modifier
        .weight(weight)
        .height(10.dp)
        .background(color),
) {}

@Composable
private fun RowScope.Figure(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Body(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

private val previewEstimate = ChargeTimeEstimate(
    toEightyMillis = 2_040_000,
    toFullMillis = 4_740_000,
    avgSpeedMilliwatts = 11_500,
    split = ChargeBandSplit(
        toFiftyMillis = 2_700_000,
        fiftyToEightyMillis = 1_800_000,
        eightyToHundredMillis = 3_600_000,
    ),
    basedOnSessions = 6,
)

@AmplyPreview
@Composable
private fun ChargeTimeCardPreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Charging: a countdown, and the taper note because the last stretch dominates.
        ChargeTimeCard(
            state = ChargeTimeState.Ready(
                estimate = previewEstimate,
                basis = ChargeTimeBasis.SAME_TYPE,
                charging = true,
                currentPercent = 42,
            ),
        )
        // Unplugged: the same figures, stated as a reference rather than a count down.
        ChargeTimeCard(
            state = ChargeTimeState.Ready(
                estimate = previewEstimate,
                basis = ChargeTimeBasis.POOLED,
                charging = false,
                currentPercent = 42,
            ),
        )
    }
}

@AmplyPreview
@Composable
private fun ChargeTimeCardPartialPreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Above 80%: the "To 80%" cell is gone rather than reading "0m", and this user always
        // unplugs early, so the top stretch has never been observed.
        ChargeTimeCard(
            state = ChargeTimeState.Ready(
                estimate = previewEstimate.copy(
                    toEightyMillis = null,
                    toFullMillis = null,
                    split = ChargeBandSplit(toFiftyMillis = 2_700_000, fiftyToEightyMillis = 1_800_000),
                ),
                basis = ChargeTimeBasis.SAME_TYPE,
                charging = true,
                currentPercent = 86,
            ),
        )
        ChargeTimeCard(state = ChargeTimeState.NotEnoughData(sessions = 1))
        ChargeTimeCard(state = ChargeTimeState.Loading)
        ChargeTimeCard(state = ChargeTimeState.Unavailable)
    }
}
