package eu.darken.amply.common.theming

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun AmplyTheme(
    state: ThemeState = ThemeState(),
    content: @Composable () -> Unit,
) {
    val dark = when (state.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val dynamic = state.style == ThemeStyle.MATERIAL_YOU && Build.VERSION.SDK_INT >= 31
    val context = LocalContext.current
    val colors = remember(state, dark, dynamic, context) {
        when {
            dynamic && dark -> dynamicDarkColorScheme(context)
            dynamic -> dynamicLightColorScheme(context)
            else -> brandedScheme(state.color, state.style, dark)
        }
    }

    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(colorScheme = colors, content = content)
}

private fun brandedScheme(color: ThemeColor, style: ThemeStyle, dark: Boolean): ColorScheme {
    val base = when (color) {
        ThemeColor.GREEN -> if (dark) GreenDark else GreenLight
        ThemeColor.BLUE -> if (dark) BlueDark else BlueLight
    }
    return when (style) {
        ThemeStyle.MEDIUM_CONTRAST -> base.copy(
            onSurface = if (dark) Color.White else Color(0xFF101310),
            onSurfaceVariant = if (dark) Color(0xFFD5DDD3) else Color(0xFF303730),
            outline = if (dark) Color(0xFFAAB2A9) else Color(0xFF535B53),
        )
        ThemeStyle.HIGH_CONTRAST -> base.copy(
            // Keep the high-contrast primary within the selected color family; a fixed value here would
            // tint every scheme (e.g. the Blue theme) with the same hue.
            primary = when {
                color == ThemeColor.BLUE -> if (dark) Color(0xFFEAF1FF) else Color(0xFF001A3D)
                else -> if (dark) Color(0xFFE0FFF6) else Color(0xFF00332B)
            },
            onPrimary = if (dark) Color.Black else Color.White,
            onSurface = if (dark) Color.White else Color.Black,
            onSurfaceVariant = if (dark) Color.White else Color.Black,
            outline = if (dark) Color.White else Color.Black,
        )
        else -> base
    }
}

private val GreenLight = lightColorScheme(
    primary = Color(0xFF006B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF79F8DE),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4E6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1E8D5),
    onSecondaryContainer = Color(0xFF0C1F14),
    tertiary = Color(0xFF3C6472),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFE9F9),
    onTertiaryContainer = Color(0xFF001F28),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFDDE5DB),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC1C9BF),
    surfaceContainer = Color(0xFFEDEEE9),
    surfaceContainerHigh = Color(0xFFE7E9E4),
    surfaceContainerHighest = Color(0xFFE1E3DE),
    surfaceContainerLow = Color(0xFFF3F4EF),
    surfaceContainerLowest = Color.White,
)

private val GreenDark = darkColorScheme(
    primary = Color(0xFF5BDBC2),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005143),
    onPrimaryContainer = Color(0xFF79F8DE),
    secondary = Color(0xFFB5CCBA),
    onSecondary = Color(0xFF213528),
    secondaryContainer = Color(0xFF374B3D),
    onSecondaryContainer = Color(0xFFD1E8D5),
    tertiary = Color(0xFFA4CDDD),
    onTertiary = Color(0xFF053542),
    tertiaryContainer = Color(0xFF234C59),
    onTertiaryContainer = Color(0xFFBFE9F9),
    background = Color(0xFF111411),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC1C9C0),
    outline = Color(0xFF8B938B),
    outlineVariant = Color(0xFF414941),
    surfaceContainer = Color(0xFF1D201D),
    surfaceContainerHigh = Color(0xFF282B28),
    surfaceContainerHighest = Color(0xFF333532),
    surfaceContainerLow = Color(0xFF171A17),
    surfaceContainerLowest = Color(0xFF0C0F0C),
)

private val BlueLight = GreenLight.copy(
    primary = Color(0xFF0060B0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3FF),
    onPrimaryContainer = Color(0xFF002347),
    secondary = Color(0xFF486084),
    secondaryContainer = Color(0xFFD4E3FF),
    onSecondaryContainer = Color(0xFF1D3557),
    // surfaceTint defaults to primary at construction; copy() would otherwise keep GreenLight's teal.
    surfaceTint = Color(0xFF0060B0),
)

private val BlueDark = GreenDark.copy(
    primary = Color(0xFF77B0FF),
    onPrimary = Color(0xFF002F5B),
    primaryContainer = Color(0xFF174A73),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFAFC8F1),
    secondaryContainer = Color(0xFF233C5E),
    onSecondaryContainer = Color(0xFFD4E3FF),
    // surfaceTint defaults to primary at construction; copy() would otherwise keep GreenDark's teal.
    surfaceTint = Color(0xFF77B0FF),
)
