package eu.darken.amply.stats.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.value
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StatsPreferencesTest {

    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private fun preferences() = StatsPreferences(
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempDir, "stats-${System.nanoTime()}.preferences_pb")
            },
        ),
    )

    @Test
    fun `forever persists as the sentinel`(): Unit = runBlocking {
        val preferences = preferences()

        preferences.setRetentionDays(StatsRetention.FOREVER)

        preferences.retentionDays.value() shouldBe StatsRetention.FOREVER
        preferences.retentionDaysNow() shouldBe StatsRetention.FOREVER
    }

    @Test
    fun `a legacy value between presets reads back snapped up`(): Unit = runBlocking {
        val preferences = preferences()

        preferences.retentionDays.value(10)

        preferences.retentionDaysNow() shouldBe 14
    }
}
