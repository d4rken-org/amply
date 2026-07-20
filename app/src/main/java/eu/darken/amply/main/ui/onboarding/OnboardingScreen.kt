package eu.darken.amply.main.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.twotone.BatteryChargingFull
import androidx.compose.material.icons.twotone.Bolt
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
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
        OnboardingCaveatsPage(onBack = { showCaveats = false }, onContinue = onContinue)
    } else {
        OnboardingWelcomePage(onNext = { showCaveats = true })
    }
}

@Composable
private fun OnboardingScaffold(content: @Composable () -> Unit) {
    // Centre and cap the width so the flow doesn't stretch edge-to-edge on tablets, and consume the
    // system-bar insets (this screen has no Scaffold) so the buttons clear the gesture nav bar.
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ONBOARDING_MAX_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = { content() },
        )
    }
}

@Composable
private fun OnboardingWelcomePage(onNext: () -> Unit) = OnboardingScaffold {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Default.BatterySaver,
                    contentDescription = null,
                    modifier = Modifier.padding(14.dp).size(42.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.onboarding_welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
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

    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_next_action))
    }
}

@Composable
private fun OnboardingCaveatsPage(onBack: () -> Unit, onContinue: () -> Unit) = OnboardingScaffold {
    Text(
        text = stringResource(R.string.onboarding_caveats_title),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    CaveatCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        title = stringResource(R.string.onboarding_caveat_support_title),
        body = stringResource(R.string.onboarding_caveat_support_body),
    )

    CaveatCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        title = stringResource(R.string.onboarding_caveat_setup_title),
        body = stringResource(R.string.onboarding_caveat_setup_body),
    )

    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_continue_action))
    }
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onboarding_back_action))
    }
}

private val ONBOARDING_MAX_WIDTH = 560.dp

@Composable
private fun CaveatCard(
    containerColor: Color,
    contentColor: Color,
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
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
    OnboardingCaveatsPage(onBack = {}, onContinue = {})
}
