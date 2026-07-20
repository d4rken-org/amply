package eu.darken.amply.common.compose

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.amply.common.theming.AmplyTheme

/**
 * Light + dark multipreview for Amply screens and reusable components. Pair with [PreviewWrapper]
 * so the content renders against the real branded theme in both schemes. The dark entry sets the
 * night ui-mode, which [AmplyTheme]'s default `ThemeMode.SYSTEM` resolves to the dark scheme.
 */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class AmplyPreview

@Composable
fun PreviewWrapper(content: @Composable () -> Unit) {
    AmplyTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
