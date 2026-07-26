package eu.darken.amply.common.theming

import androidx.annotation.StringRes
import eu.darken.amply.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted as one JSON record, so the three choices are always read as a consistent set.
 *
 * The `@SerialName`s are the stored format — renaming a Kotlin constant must not silently reset a
 * user's theme to the defaults.
 */
@Serializable
data class ThemeState(
    @SerialName("mode") val mode: ThemeMode = ThemeMode.SYSTEM,
    @SerialName("style") val style: ThemeStyle = ThemeStyle.DEFAULT,
    @SerialName("color") val color: ThemeColor = ThemeColor.GREEN,
)

@Serializable
enum class ThemeMode(@get:StringRes val label: Int) {
    @SerialName("SYSTEM")
    SYSTEM(R.string.theme_mode_system),

    @SerialName("DARK")
    DARK(R.string.theme_mode_dark),

    @SerialName("LIGHT")
    LIGHT(R.string.theme_mode_light),
}

@Serializable
enum class ThemeStyle(@get:StringRes val label: Int) {
    @SerialName("DEFAULT")
    DEFAULT(R.string.theme_style_default),

    @SerialName("MATERIAL_YOU")
    MATERIAL_YOU(R.string.theme_style_material_you),

    @SerialName("MEDIUM_CONTRAST")
    MEDIUM_CONTRAST(R.string.theme_style_medium_contrast),

    @SerialName("HIGH_CONTRAST")
    HIGH_CONTRAST(R.string.theme_style_high_contrast),
}

@Serializable
enum class ThemeColor(@get:StringRes val label: Int) {
    @SerialName("GREEN")
    GREEN(R.string.theme_color_green),

    @SerialName("BLUE")
    BLUE(R.string.theme_color_blue),
}
