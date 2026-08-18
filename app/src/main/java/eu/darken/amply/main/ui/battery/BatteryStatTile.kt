package eu.darken.amply.main.ui.battery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyClickableCard
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.common.compose.chart.ChartPoint
import eu.darken.amply.common.compose.chart.Sparkline

/**
 * One reading in the hub's tile grid: a small-caps label, a headline value, and — when the recorded
 * series has a shape to show — a sparkline behind the value.
 *
 * [onClick] is what makes the tile navigable: passing null leaves it a plain card with no chevron,
 * no ripple and no click target, which is the honest rendering for a reading that has nothing
 * recorded to open. The chevron is therefore never decorative — it appears exactly where a tap does
 * something.
 *
 * The value area is a floor, not a fixed height: a value too long for one line wraps to a second
 * rather than being clipped ("Plugged in, n…" on a Pixel 7a), with ellipsis left as the last resort.
 * Numbers never wrap, so the ordinary tile is unchanged. [valueStyle] exists for the values that are
 * state labels rather than measurements — they are legitimately secondary to the numbers, and a
 * smaller style buys the wrapped label room.
 *
 * Tiles are laid out by [BatteryStatTileRow], which sizes both tiles in a row to the taller one so a
 * wrapped label or value can't stagger the grid.
 *
 * [valueColor] and [valueDescription] exist for values that stand in for a missing figure: a
 * dimmed placeholder reads as absence rather than as a reading, and a glyph that carries no words
 * needs a spoken form. Both default to the ordinary rendering, so a tile showing a real value is
 * unchanged.
 */
@Composable
fun BatteryStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    valueColor: Color = Color.Unspecified,
    valueDescription: String? = null,
    icon: ImageVector? = null,
    sparkline: List<ChartPoint> = emptyList(),
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    onClickLabel: String = "",
) {
    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VALUE_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Behind the value, and dimmed: the number is the point of the tile, the shape is context.
            Sparkline(
                points = sparkline,
                modifier = Modifier.align(Alignment.BottomStart),
                color = accentColor.copy(alpha = 0.35f),
                height = SPARKLINE_HEIGHT,
            )
            Text(
                value,
                style = valueStyle,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (valueDescription != null) {
                    Modifier.semantics { contentDescription = valueDescription }
                } else {
                    Modifier
                },
            )
        }
    }

    if (onClick == null) {
        AmplyCard(
            modifier = modifier,
            tone = AmplyCardTone.SurfaceContainer,
            contentPadding = TILE_PADDING,
            content = content,
        )
    } else {
        AmplyClickableCard(
            onClick = onClick,
            onClickLabel = onClickLabel,
            modifier = modifier,
            tone = AmplyCardTone.SurfaceContainer,
            contentPadding = TILE_PADDING,
            content = content,
        )
    }
}

/**
 * One row of the tile grid: two equal-width tiles, both stretched to the taller one's height.
 *
 * A plain [Row] of cards would let a tile whose label wraps stand taller than its neighbour;
 * `IntrinsicSize.Max` plus `fillMaxHeight` keeps the grid square at every font scale. A single
 * trailing tile keeps its half-width rather than stretching across the row.
 */
@Composable
fun BatteryStatTileRow(
    modifier: Modifier = Modifier,
    content: @Composable (tileModifier: Modifier) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content(Modifier.weight(1f).fillMaxHeight())
    }
}

private val TILE_PADDING = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
private val VALUE_HEIGHT = 40.dp
private val SPARKLINE_HEIGHT = 26.dp

@AmplyPreview
@Preview(name = "Tiles · large font", fontScale = 1.5f, showBackground = true)
@Composable
private fun BatteryStatTilePreview() = PreviewWrapper {
    val rising = (0..16).map { ChartPoint(it * 60_000f, 42f + it * 2.4f) }
    val falling = (0..16).map { ChartPoint(it * 60_000f, 18_000f - it * 900f) }
    val flat = (0..16).map { ChartPoint(it * 60_000f, 4_185f) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BatteryStatTileRow { tile ->
            // Tappable, with a recorded shape behind the value.
            BatteryStatTile(
                label = "Level",
                value = "82%",
                modifier = tile,
                icon = Icons.Filled.BatteryChargingFull,
                sparkline = rising,
                onClick = {},
                onClickLabel = "Open",
            )
            BatteryStatTile(
                label = "Charge power",
                value = "5.2 W",
                modifier = tile,
                icon = Icons.Filled.Bolt,
                sparkline = falling,
                onClick = {},
                onClickLabel = "Open",
            )
        }
        BatteryStatTileRow { tile ->
            // Recorded but constant: tappable (a flat reading is still a reading), no sparkline.
            BatteryStatTile(
                label = "Voltage",
                value = "4.19 V",
                modifier = tile,
                sparkline = flat,
                onClick = {},
                onClickLabel = "Open",
            )
            // Nothing recorded: a plain card, no chevron, not clickable.
            BatteryStatTile(
                label = "Temperature",
                value = "31.4 °C",
                modifier = tile,
                icon = Icons.Filled.Thermostat,
            )
        }
    }
}
