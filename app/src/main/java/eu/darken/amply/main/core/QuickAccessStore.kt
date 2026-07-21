package eu.darken.amply.main.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import eu.darken.amply.common.AppDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class QuickAccessState(
    val dismissed: Boolean = false,
    val widgetAdded: Boolean = false,
    val tileAdded: Boolean = false,
)

/**
 * Sticky discovery flags for the dashboard's quick-access promotion (home-screen widget + QS tile).
 * Flags only ever move to true — this is a discovery prompt, not a placement monitor, so removing a
 * shortcut later must not resurrect the promotion.
 */
@Singleton
class QuickAccessStore @Inject constructor(
    private val dataStore: AppDataStore,
) {
    val state: Flow<QuickAccessState> = dataStore.store.data.map {
        QuickAccessState(
            dismissed = it[DISMISSED] ?: false,
            widgetAdded = it[WIDGET_ADDED] ?: false,
            tileAdded = it[TILE_ADDED] ?: false,
        )
    }

    suspend fun dismiss() {
        dataStore.store.edit { it[DISMISSED] = true }
    }

    suspend fun markWidgetAdded() {
        dataStore.store.edit { it[WIDGET_ADDED] = true }
    }

    suspend fun markTileAdded() {
        dataStore.store.edit { it[TILE_ADDED] = true }
    }

    private companion object {
        val DISMISSED = booleanPreferencesKey("quickaccess.v1.dismissed")
        val WIDGET_ADDED = booleanPreferencesKey("quickaccess.v1.widget_added")
        val TILE_ADDED = booleanPreferencesKey("quickaccess.v1.tile_added")
    }
}
