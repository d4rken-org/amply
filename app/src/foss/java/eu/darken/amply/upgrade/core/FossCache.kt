package eu.darken.amply.upgrade.core

import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The FOSS unlock record, on the app's single shared [AppDataStore] — Amply keeps exactly one store,
 * so this is a key on it, not a second DataStore.
 *
 * `fallbackToDefault = true`: the decode is all-or-nothing (an unreadable record is no record), and
 * a corrupt value reading as "absent" is recoverable — the user can re-run the sponsor flow, which
 * rewrites it. Throwing instead would kill the collector's flow for good.
 */
@Singleton
class FossCache @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    val upgrade = dataStore.createValue<FossUpgrade?>(
        key = "upgrade.foss.v1",
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )
}
