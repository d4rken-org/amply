// Chart screenshot entry points. Each @PreviewTest renders one chart fixture (from ChartFixtures.kt in
// app/src/debug) to a PNG under this class's own ChartScreenshotsKt/ reference dir. These are engineering
// regression shots — they are NOT part of the Play Store screenshot flow (see PlayStoreScreenshots).
package eu.darken.amply.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartCollidingEnds() = ChartCollidingEndsContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartConstant100() = ChartConstant100Content()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartPowerAllNull() = ChartPowerAllNullContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartPowerTrailingNulls() = ChartPowerTrailingNullsContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartNarrow() = ChartNarrowContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartFontScale() = ChartFontScaleContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartRtl() = ChartRtlContent()

@PreviewTest
@Preview(showBackground = true)
@Composable
fun ChartCompact() = ChartCompactContent()
