package eu.darken.amply.screenshots

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

// Multi-preview annotation: one @Preview per Play Store-supported locale (light mode). Each [name] is
// the fastlane metadata directory name, so fastlane/copy_screenshots.sh can map a rendered file
// straight to metadata/android/<name>/. en-US is the only authored locale today; to add another,
// append a line here (light + the dark twin below) and a matching entry in generate_screenshots.sh.
@Preview(locale = "en", name = "en-US", device = DS)
annotation class PlayStoreLocales

// Same locales with night mode enabled, for the dark-theme screenshots.
@Preview(locale = "en", name = "en-US", device = DS, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class PlayStoreLocalesDark
