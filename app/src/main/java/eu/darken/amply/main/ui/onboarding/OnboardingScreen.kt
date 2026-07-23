package eu.darken.amply.main.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.BatteryChargingFull
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import eu.darken.amply.R
import eu.darken.amply.common.compose.AmplyCard
import eu.darken.amply.common.compose.AmplyCardTone
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper

@Composable
fun OnboardingScreen(onContinue: () -> Unit) {
    var showCaveats by rememberSaveable { mutableStateOf(false) }

    // The caveats page acts as a shallow back stack. MainActivity's own BackHandler is disabled
    // while onboarding is incomplete, so this handler owns system back here.
    BackHandler(enabled = showCaveats) { showCaveats = false }

    // Each page composes its own scaffold (and thus its own scroll state), so opening the caveats
    // page never inherits a scroll offset the user left on the welcome page.
    if (showCaveats) {
        OnboardingCaveatsPage(onContinue = onContinue)
    } else {
        OnboardingWelcomePage(onNext = { showCaveats = true })
    }
}

@Composable
private fun OnboardingScaffold(
    currentPage: Int,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Centre and cap the width so the flow doesn't stretch edge-to-edge on tablets, and consume the
    // safe-drawing insets (this screen has no Scaffold) so the pinned actions clear the gesture nav
    // bar and display cutouts. Only the content scrolls; the page indicator and primary action stay
    // at the bottom.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ONBOARDING_MAX_WIDTH)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = { content() },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PageIndicator(pageCount = ONBOARDING_PAGE_COUNT, currentPage = currentPage)
                actions()
            }
        }
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    val progressDescription = stringResource(R.string.onboarding_page_progress, currentPage + 1, pageCount)
    Row(
        modifier = Modifier.semantics { contentDescription = progressDescription },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            Box(
                Modifier
                    .size(width = if (active) 24.dp else 8.dp, height = 8.dp)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun OnboardingWelcomePage(onNext: () -> Unit) = OnboardingScaffold(
    currentPage = 0,
    actions = {
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_next_action))
        }
    },
) {
    // The brand mark carries the colour here: a self-contained icon badge on the plain surface, not a
    // large filled hero card. ic_launcher_full is pre-composed on its navy background, so clipping it to
    // a circle keeps the gradient legible on both light and dark surfaces without a coloured slab.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_full),
            contentDescription = null,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Exception to the uniform card inset: the rows own their own horizontal padding, so this card
    // keeps only the vertical inset and passes 0 horizontal.
    AmplyCard(
        tone = AmplyCardTone.SurfaceLow,
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        FeatureRow(
            icon = Icons.TwoTone.Restore,
            title = stringResource(R.string.onboarding_feature_protection_title),
            body = stringResource(R.string.onboarding_feature_protection_body),
        )
        FeatureRow(
            icon = Icons.TwoTone.BatteryChargingFull,
            title = stringResource(R.string.onboarding_feature_fullcharge_title),
            body = stringResource(R.string.onboarding_feature_fullcharge_body),
        )
        FeatureRow(
            icon = Icons.TwoTone.Bolt,
            title = stringResource(R.string.onboarding_feature_shortcut_title),
            body = stringResource(R.string.onboarding_feature_shortcut_body),
        )
    }
}

@Composable
private fun OnboardingCaveatsPage(onContinue: () -> Unit) = OnboardingScaffold(
    currentPage = 1,
    actions = {
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_continue_action))
        }
    },
) {
    Text(
        text = stringResource(R.string.onboarding_caveats_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    CaveatCard(
        tone = AmplyCardTone.TertiaryContainer,
        title = stringResource(R.string.onboarding_caveat_support_title),
        body = stringResource(R.string.onboarding_caveat_support_body),
    )

    CaveatCard(
        tone = AmplyCardTone.SurfaceHigh,
        title = stringResource(R.string.onboarding_caveat_setup_title),
        body = stringResource(R.string.onboarding_caveat_setup_body),
    )

    CaveatCard(
        tone = AmplyCardTone.SurfaceHigh,
        title = stringResource(R.string.onboarding_caveat_restore_title),
        body = stringResource(R.string.onboarding_caveat_restore_body),
    )
}

private val ONBOARDING_MAX_WIDTH = 560.dp
private const val ONBOARDING_PAGE_COUNT = 2

@Composable
private fun CaveatCard(
    tone: AmplyCardTone,
    title: String,
    body: String,
) {
    AmplyCard(tone = tone) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@AmplyPreview
@Composable
private fun OnboardingWelcomePagePreview() = PreviewWrapper {
    OnboardingWelcomePage(onNext = {})
}

@AmplyPreview
@Composable
private fun OnboardingCaveatsPagePreview() = PreviewWrapper {
    OnboardingCaveatsPage(onContinue = {})
}
