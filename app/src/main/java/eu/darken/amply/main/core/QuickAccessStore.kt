package eu.darken.amply.main.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class QuickAccessState(
    @SerialName("dismissed") val dismissed: Boolean = false,
    @SerialName("widgetAdded") val widgetAdded: Boolean = false,
    @SerialName("tileAdded") val tileAdded: Boolean = false,
)

/**
 * Sticky discovery flags for the dashboard's quick-access promotion (home-screen widget + QS tile).
 * Flags only ever move to true — this is a discovery prompt, not a placement monitor, so removing a
 * shortcut later must not resurrect the promotion.
 */
@Singleton
class QuickAccessStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    val state = dataStore.createValue(
        key = "quickaccess.v2",
        defaultValue = QuickAccessState(),
        json = json,
        fallbackToDefault = true,
    )

    suspend fun dismiss() {
        state.update { it.copy(dismissed = true) }
    }

    suspend fun markWidgetAdded() {
        state.update { it.copy(widgetAdded = true) }
    }

    suspend fun markTileAdded() {
        state.update { it.copy(tileAdded = true) }
    }
}
