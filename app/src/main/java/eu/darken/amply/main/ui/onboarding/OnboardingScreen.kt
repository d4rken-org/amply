package eu.darken.amply.main.ui.onboarding

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.darken.amply.R
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargingState
import eu.darken.amply.charging.core.DeviceInfo
import eu.darken.amply.common.compose.AmplyPreview
import eu.darken.amply.common.compose.PreviewWrapper
import eu.darken.amply.main.ui.dashboard.DashboardUiState
import eu.darken.amply.main.ui.setup.AccessSetupGuide

@Composable
fun OnboardingScreen(
    state: DashboardUiState,
    adbCommand: String,
    onOpenShizuku: () -> Unit,
    onAllowShizuku: () -> Unit,
    onGrantWss: () -> Unit,
    onCopyAdb: () -> Unit,
    onContinue: () -> Unit,
) {
    val canControl = state.charging.controlEnabled && state.charging.access?.canControl == true

    // Centre and cap the width so the flow doesn't stretch edge-to-edge on tablets, and consume the
    // system-bar insets (this screen has no Scaffold) so the Continue button clears the gesture nav bar.
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
        ) {
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
                        text = "Welcome to Amply",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Protect your battery every day. Charge fully only when you choose.",
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
                        title = "Keep the 80% protection",
                        body = "Switch policies without digging through Android Settings.",
                    )
                    FeatureRow(
                        icon = Icons.TwoTone.BatteryChargingFull,
                        title = "Charge fully once",
                        body = "Protection returns automatically at full or when unplugged.",
                    )
                    FeatureRow(
                        icon = Icons.TwoTone.Bolt,
                        title = "Optional cable shortcut",
                        body = "Reconnect at the 80% hold to request one full charge.",
                    )
                }
            }

            if (state.charging.observation is ChargeObservation.Unsupported &&
                state.charging.contributionWanted
            ) {
                // Keep onboarding generic and reassuring — don't single out this device as
                // incompatible. The specific device details and the request/email actions live on
                // the dashboard.
                Text(
                    text = stringResource(R.string.setup_unsupported_onboarding_caveat),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    Text("This device", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "${state.charging.device.manufacturer} ${state.charging.device.model} · ${state.charging.adapterDetail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.charging.observation !is ChargeObservation.Unsupported) {
                    AccessSetupGuide(
                        state = state,
                        adbCommand = adbCommand,
                        onOpenShizuku = onOpenShizuku,
                        onAllowShizuku = onAllowShizuku,
                        onGrantWss = onGrantWss,
                        onCopyAdb = onCopyAdb,
                    )
                }
            }

            if (canControl) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue to Amply")
                }
            } else {
                OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (state.charging.observation is ChargeObservation.Unsupported) "Continue"
                        else "Set up later",
                    )
                }
            }
        }
    }
}

private val ONBOARDING_MAX_WIDTH = 560.dp

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
private fun OnboardingScreenPreview() = PreviewWrapper {
    OnboardingScreen(
        state = DashboardUiState(
            onboardingComplete = false,
            charging = ChargingState(
                device = DeviceInfo("Google", "Pixel 8", 36, "preview"),
                adapterName = "Pixel Charge Control",
                adapterDetail = "Supported Pixel capability",
            ),
        ),
        adbCommand = "adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS",
        onOpenShizuku = {},
        onAllowShizuku = {},
        onGrantWss = {},
        onCopyAdb = {},
        onContinue = {},
    )
}
