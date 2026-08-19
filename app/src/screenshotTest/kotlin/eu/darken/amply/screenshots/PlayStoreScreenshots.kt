// Play Store screenshot entry points. Each @PreviewTest renders once per locale in the multi-preview
// annotation. The rendered PNG is named "<FunctionName>_<localeName>_<hash>_<index>.png"; the function
// name maps to an ordered store filename in fastlane/copy_screenshots.sh, so renaming a function here
// means updating that script's SCREEN_MAP. The mock content lives in app/src/debug ScreenshotContent.kt.
package eu.darken.amply.screenshots

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@PlayStoreLocales
@Composable
fun DashboardReady() = DashboardReadyContent()

@PreviewTest
@PlayStoreLocalesDark
@Composable
fun FullChargeActive() = DashboardActiveContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun ChargeConditions() = ChargeConditionsContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun SetupGuide() = SetupGuideContent()

// Shares HubFixtures' tile-grid content with the engineering hub shots — one fixture, two capture
// dirs, so the store shot can never drift from the regression one.
@PreviewTest
@PlayStoreLocales
@Composable
fun BatteryHub() = HubTileGridContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun ReconnectGesture() = ReconnectGestureContent()
