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
fun SamsungMultiMode() = SamsungMultiModeContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun SetupGuide() = SetupGuideContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun Settings() = SettingsContent()

@PreviewTest
@PlayStoreLocales
@Composable
fun ReconnectGesture() = ReconnectGestureContent()
