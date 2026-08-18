// Battery-hub screenshot entry points. Each @PreviewTest renders one hub fixture (from HubFixtures.kt
// in app/src/debug) to a PNG under this class's own HubScreenshotsKt/ reference dir. These are
// engineering regression shots — they are NOT part of the Play Store screenshot flow (see
// PlayStoreScreenshots), whose generator counts only PlayStoreScreenshotsKt.
package eu.darken.amply.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(showBackground = true, device = DS)
@Composable
fun HubTileGrid() = HubTileGridContent()

@PreviewTest
@Preview(showBackground = true, device = DS)
@Composable
fun HubTileGridEmpty() = HubTileGridEmptyContent()
