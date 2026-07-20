package eu.darken.amply.common.theming

import androidx.annotation.StringRes
import eu.darken.amply.R

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
    val color: ThemeColor = ThemeColor.GREEN,
)

enum class ThemeMode(@get:StringRes val label: Int) {
    SYSTEM(R.string.theme_mode_system),
    DARK(R.string.theme_mode_dark),
    LIGHT(R.string.theme_mode_light),
}

enum class ThemeStyle(@get:StringRes val label: Int) {
    DEFAULT(R.string.theme_style_default),
    MATERIAL_YOU(R.string.theme_style_material_you),
    MEDIUM_CONTRAST(R.string.theme_style_medium_contrast),
    HIGH_CONTRAST(R.string.theme_style_high_contrast),
}

enum class ThemeColor(@get:StringRes val label: Int) {
    GREEN(R.string.theme_color_green),
    BLUE(R.string.theme_color_blue),
}
