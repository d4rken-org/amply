package eu.darken.amply.common.theming

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSettings @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val state: Flow<ThemeState> = dataStore.store.data.map { prefs ->
        ThemeState(
            mode = prefs[MODE].toEnumOrDefault(ThemeMode.SYSTEM),
            style = prefs[STYLE].toEnumOrDefault(ThemeStyle.DEFAULT),
            color = prefs[COLOR].toEnumOrDefault(ThemeColor.GREEN),
        )
    }

    suspend fun setMode(value: ThemeMode) = dataStore.store.edit { it[MODE] = value.name }
    suspend fun setStyle(value: ThemeStyle) = dataStore.store.edit { it[STYLE] = value.name }
    suspend fun setColor(value: ThemeColor) = dataStore.store.edit { it[COLOR] = value.name }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private companion object {
        val MODE = stringPreferencesKey("core.ui.theme.mode")
        val STYLE = stringPreferencesKey("core.ui.theme.style")
        val COLOR = stringPreferencesKey("core.ui.theme.color")
    }
}
