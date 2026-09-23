package eu.darken.amply.battery.core

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Provider

/**
 * A [BatteryUnitCalibration] for a test device that is not a Samsung, where the current-unit store must
 * never be touched. Both a Samsung test device and any store access fail the test loudly.
 */
fun nonSamsungBatteryUnitCalibration(context: Context): BatteryUnitCalibration {
    check(!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        "nonSamsungBatteryUnitCalibration on a Samsung test device"
    }
    return BatteryUnitCalibration(
        context = context,
        unitStore = Provider { throw AssertionError("BatteryUnitStore accessed on a non-Samsung device") },
        dispatcher = Dispatchers.Unconfined,
    )
}

/**
 * A [DataStore] that never leaves the calling coroutine, so a test dispatcher alone decides when a
 * read or write completes. [failNextWrites] and [failReads] simulate a full or unreadable disk.
 */
class InMemoryPreferencesStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()

    @Volatile var failNextWrites: Int = 0
    @Volatile var failReads: Boolean = false
    @Volatile var writeAttempts: Int = 0
        private set

    override val data: Flow<Preferences> = flow {
        if (failReads) throw IOException("Unreadable")
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock {
            writeAttempts++
            if (failNextWrites > 0) {
                failNextWrites--
                throw IOException("No space left on device")
            }
            transform(state.value).also { state.value = it }
        }
}
