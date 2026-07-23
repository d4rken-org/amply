package eu.darken.amply.common.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared card design-system layer. Every card surface in the app routes through these primitives so
 * padding, spacing, tone, and navigation affordance stay consistent. The primitives standardize
 * *structure*; per-card colour accents, icon tints, and title sizes stay explicit at the call site.
 */
object AmplyCardDefaults {
    /** Single source of truth for card content inset — flip here to re-tune every card at once. */
    val ContentPadding = 16.dp

    /** Opt-in intra-card vertical rhythm. Not a base default: cards with manual spacers must not
     * combine it with [Arrangement.spacedBy], or gaps double. */
    val ItemSpacing = 8.dp

    /** Extra breathing room reserved below a header title, on top of [ItemSpacing], so the
     * title↔body gap doesn't read as cramped once the trailing control is floated out of the row. */
    val HeaderContentSpacing = 4.dp

    val ContentPaddingValues = PaddingValues(ContentPadding)
}

/**
 * Named visual roles mapping the app's existing Material 3 container tiers. A tone controls the
 * card's container/content colours **only** — never the header icon/title tint, which stays explicit
 * where it carries meaning (status green, primary accents, etc.).
 */
enum class AmplyCardTone {
    /** [CardDefaults.cardColors] — the default filled card (surfaceContainerHighest). */
    Default,
    SurfaceContainer,
    SurfaceLow,
    SurfaceHigh,
    PrimaryContainer,
    SecondaryContainer,
    TertiaryContainer,
}

@Composable
private fun AmplyCardTone.colors(): CardColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        AmplyCardTone.Default -> CardDefaults.cardColors()
        AmplyCardTone.SurfaceContainer -> CardDefaults.cardColors(
            containerColor = scheme.surfaceContainer,
            contentColor = scheme.onSurface,
        )
        AmplyCardTone.SurfaceLow -> CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerLow,
            contentColor = scheme.onSurface,
        )
        AmplyCardTone.SurfaceHigh -> CardDefaults.cardColors(
            containerColor = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        )
        AmplyCardTone.PrimaryContainer -> CardDefaults.cardColors(
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
        )
        AmplyCardTone.SecondaryContainer -> CardDefaults.cardColors(
            containerColor = scheme.secondaryContainer,
            contentColor = scheme.onSecondaryContainer,
        )
        AmplyCardTone.TertiaryContainer -> CardDefaults.cardColors(
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
        )
    }
}

/**
 * Base card surface: full width, toned colours, and the shared content inset. The default vertical
 * arrangement is [Arrangement.Top] (no auto-spacing) so cards that keep manual spacers don't get
 * doubled gaps — opt into [AmplyCardDefaults.ItemSpacing] explicitly.
 */
@Composable
fun AmplyCard(
    modifier: Modifier = Modifier,
    tone: AmplyCardTone = AmplyCardTone.Default,
    contentPadding: PaddingValues = AmplyCardDefaults.ContentPaddingValues,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = tone.colors(),
    ) {
        Column(
            // Alpha on the content, not the card surface, so a disabled card dims its controls while
            // its background stays opaque.
            modifier = Modifier
                .padding(contentPadding)
                .alpha(contentAlpha),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Base card whose whole surface is the click target (ripple, shape-clipping, and enabled-state live
 * on the surface). Carries an explicit localized [onClickLabel] and [Role.Button] so accessibility
 * services announce the action — a bare clickable [Card] is otherwise unlabeled. Callers own the
 * layout; use for compact rows that don't fit the standard header.
 */
@Composable
fun AmplyClickableCard(
    onClick: () -> Unit,
    onClickLabel: String,
    modifier: Modifier = Modifier,
    tone: AmplyCardTone = AmplyCardTone.Default,
    contentPadding: PaddingValues = AmplyCardDefaults.ContentPaddingValues,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = onClickLabel, action = null)
            },
        colors = tone.colors(),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Card whose whole surface toggles a boolean ([Role.Switch] semantics), for a feature the card is
 * primarily a switch for. Inner interactive controls (a slider, a settings button) keep their own
 * gestures — only taps outside them flip the toggle. Pair the header's trailing slot with
 * [AmplyCardToggleIndicator], a read-only switch that mirrors [checked]; the surface owns the toggle,
 * so the indicator must not carry its own `onCheckedChange`.
 */
@Composable
fun AmplyToggleCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: AmplyCardTone = AmplyCardTone.Default,
    contentPadding: PaddingValues = AmplyCardDefaults.ContentPaddingValues,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    contentAlpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Clip to the card shape before toggleable so the tap target and ripple are bounded by the
    // rounded corners rather than the full rectangle (a non-clickable Card clips inside its modifier).
    val shape = CardDefaults.shape
    Card(
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        colors = tone.colors(),
    ) {
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .alpha(contentAlpha),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * Read-only, slightly reduced [Switch] used as the trailing indicator of an [AmplyToggleCard]. It
 * reflects [checked] but does not handle its own clicks — the surrounding card owns the toggle — so
 * it never double-fires. Scaled down since it reads as status, not the primary tap target.
 */
@Composable
fun AmplyCardToggleIndicator(checked: Boolean, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        enabled = enabled,
        modifier = Modifier.scale(0.75f),
    )
}

/**
 * Standard card header: optional leading icon + weighted title + optional trailing slot. [iconTint]
 * and [titleStyle] are explicit (defaults suit the common feature card) so a caller can preserve a
 * meaningful accent; a card whose header can't be expressed here (state-dependent leading content,
 * hero title) should lay out its own header inside [AmplyCard] instead.
 *
 * The [trailing] control is **floated**: the header is only as tall as the title line, and the
 * control is pinned to the end, vertically centered on that line, overflowing symmetrically into the
 * surrounding padding. This keeps the title↔body gap tight — a `Switch`/`IconButton` carries a 48dp
 * minimum touch target that would otherwise inflate the whole header row and push the body down. The
 * control keeps its full touch target (it just overlaps neighbouring empty space invisibly). Pass a
 * single composable; wrap multiple controls in your own `Row`.
 */
@Composable
fun AmplyCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (trailing == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = AmplyCardDefaults.HeaderContentSpacing),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Text(title, style = titleStyle, modifier = Modifier.weight(1f))
        }
        return
    }
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
                Text(title, style = titleStyle)
            }
            // Box keeps the trailing slot to exactly one measurable regardless of what the caller
            // emits (conditional/empty or multiple children), so the layout below can't crash.
            Box { trailing() }
        },
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val bottomInset = AmplyCardDefaults.HeaderContentSpacing.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val trailingPlaceable = measurables[1].measure(loose)
        val titleMaxWidth = (constraints.maxWidth - trailingPlaceable.width - gap).coerceAtLeast(0)
        val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = titleMaxWidth))
        // Header reports the title height plus a little breathing room below; the (taller) trailing
        // control is centered on the title line and overflows symmetrically into the padding.
        val height = titlePlaceable.height + bottomInset
        layout(constraints.maxWidth, height) {
            // placeRelative mirrors for RTL: the title group sits at the logical start and the
            // trailing control at the logical end.
            titlePlaceable.placeRelative(0, 0)
            trailingPlaceable.placeRelative(
                x = constraints.maxWidth - trailingPlaceable.width,
                y = (titlePlaceable.height - trailingPlaceable.height) / 2,
            )
        }
    }
}

/**
 * Navigation card: the whole card opens a destination ([onClick]) with an [onClickLabel] for
 * accessibility, a standard header, and a decorative RTL-aware trailing chevron. Must contain no
 * independent interactive controls — the surface owns the single tap.
 */
@Composable
fun AmplyNavigationCard(
    onClick: () -> Unit,
    onClickLabel: String,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    tone: AmplyCardTone = AmplyCardTone.Default,
    content: @Composable ColumnScope.() -> Unit,
) {
    AmplyClickableCard(
        onClick = onClick,
        onClickLabel = onClickLabel,
        modifier = modifier,
        tone = tone,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        AmplyCardHeader(
            title = title,
            icon = icon,
            iconTint = iconTint,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        content()
    }
}

/**
 * Shared inset block for monospace report/command previews. Selectable text on an inset surface.
 * When [maxHeight] is set the block caps at that height and scrolls (for long reports); when null it
 * wraps to content with no scroll container (for short snippets like a link or a command). Replaces
 * the ad-hoc nested-card/Surface implementations.
 */
@Composable
fun AmplyCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    maxHeight: Dp? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier
                    .then(
                        if (maxHeight != null) {
                            Modifier
                                .heightIn(max = maxHeight)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@AmplyPreview
@Composable
private fun AmplyCardPreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AmplyCard(verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing)) {
            AmplyCardHeader(
                title = "Default card",
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            )
            Text(
                "A neutral feature card with a standard header.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AmplyNavigationCard(
            onClick = {},
            onClickLabel = "Open details",
            title = "Navigation card",
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        ) {
            Text(
                "Whole card is the tap target; chevron is decorative.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AmplyCard(tone = AmplyCardTone.PrimaryContainer) {
            Text("Primary-container tone (hero CTA).", style = MaterialTheme.typography.bodyMedium)
        }
        AmplyCodeBlock(text = "adb shell settings put secure example 1", maxHeight = 120.dp)
    }
}
