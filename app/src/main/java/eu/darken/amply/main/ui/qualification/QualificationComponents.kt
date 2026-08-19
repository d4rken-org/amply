package eu.darken.amply.main.ui.qualification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardDefaults
import eu.darken.amply.common.compose.AmplyCardHeader
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

/**
 * The wizard's grouping primitive: the app's shared [AmplyCard] with the standard header, so the
 * qualification steps sit on the same surface tier and inset as every dashboard card rather than
 * inventing a second card style for this one screen.
 */
@Composable
internal fun QualificationCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
    content: @Composable ColumnScope.() -> Unit,
) {
    AmplyCard(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AmplyCardDefaults.ItemSpacing),
    ) {
        if (title != null) AmplyCardHeader(title = title, titleStyle = titleStyle)
        content()
    }
}

/**
 * One labelled figure. This is the unit that replaces the wizard's run-on reading lines: a value
 * with a name beside it, instead of a sentence the reader has to parse to find the number.
 *
 * Both columns are weighted and the value is end-aligned, so a long value (a large font scale, a
 * translated status word) wraps rather than clipping — the same treatment the battery and stats
 * detail rows use.
 */
@Composable
internal fun LabelledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@AmplyPreview
@Composable
private fun QualificationCardPreview() = PreviewWrapper {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QualificationCard(title = "Before starting") {
            LabelledValue(label = "Battery now", value = "64%")
            LabelledValue(label = "Needed to start", value = "73%")
            LabelledValue(label = "Charging", value = "Yes")
        }
        QualificationCard {
            LabelledValue(
                label = "A label long enough to compete for the row",
                value = "A value that has to wrap",
            )
        }
    }
}
