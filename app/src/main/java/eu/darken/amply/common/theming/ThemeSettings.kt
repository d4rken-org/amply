package eu.darken.amply.common.theming

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSettings @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    // One record rather than three keys: the three choices are always consumed together, and a
    // corrupt record falling back to the default theme is purely cosmetic.
    val state = dataStore.createValue(
        key = "core.ui.theme.v2",
        defaultValue = ThemeState(),
        json = json,
        fallbackToDefault = true,
    )

    suspend fun setMode(value: ThemeMode) {
        state.update { it.copy(mode = value) }
    }

    suspend fun setStyle(value: ThemeStyle) {
        state.update { it.copy(style = value) }
    }

    suspend fun setColor(value: ThemeColor) {
        state.update { it.copy(color = value) }
    }
}
