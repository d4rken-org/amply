package eu.darken.amply.common.theming

data class ThemeState(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val style: ThemeStyle = ThemeStyle.DEFAULT,
    val color: ThemeColor = ThemeColor.GREEN,
)

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    DARK("Dark"),
    LIGHT("Light"),
}

enum class ThemeStyle(val label: String) {
    DEFAULT("Default"),
    MATERIAL_YOU("Material You"),
    MEDIUM_CONTRAST("Medium contrast"),
    HIGH_CONTRAST("High contrast"),
}

enum class ThemeColor(val label: String) {
    GREEN("Amply green"),
    BLUE("Blue"),
}
