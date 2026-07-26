package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InterruptionStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appDataStore by lazy {
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(tempDir, "test.preferences_pb")
            },
        )
    }
    private val store by lazy { InterruptionStore(appDataStore, SerializationModule.json()) }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private fun event(
        outcome: InterruptionOutcome = InterruptionOutcome.STILL_PENDING,
        reason: InterruptionReason = InterruptionReason.OTHER,
        workId: String = "tok-1",
        occurredAt: Long = 1_000L,
    ) = InterruptionEvent(occurredAt, reason, outcome, workId)

    @Test
    fun `no event by default`() = runTest {
        store.event.first() shouldBe null
    }

    @Test
    fun `record round-trips`() = runTest {
        val e = event(outcome = InterruptionOutcome.RESTORED_LATE, reason = InterruptionReason.USER_STOPPED)
        store.record(e)
        store.event.first() shouldBe e
    }

    @Test
    fun `a newer event overwrites the old`() = runTest {
        store.record(event(workId = "old"))
        val newer = event(outcome = InterruptionOutcome.UNCONFIRMED, workId = "new", occurredAt = 2_000L)
        store.record(newer)
        store.event.first() shouldBe newer
    }

    @Test
    fun `markRestored upgrades a matching still-pending event`() = runTest {
        store.record(event(outcome = InterruptionOutcome.STILL_PENDING, workId = "tok-1"))
        store.markRestored("tok-1")
        store.event.first()?.outcome shouldBe InterruptionOutcome.RESTORED_LATE
    }

    @Test
    fun `markRestored ignores a non-matching token`() = runTest {
        store.record(event(outcome = InterruptionOutcome.STILL_PENDING, workId = "tok-1"))
        store.markRestored("other")
        store.event.first()?.outcome shouldBe InterruptionOutcome.STILL_PENDING
    }

    @Test
    fun `markRestored on an absent event is a no-op`() = runTest {
        store.markRestored("tok-1")
        store.event.first() shouldBe null
    }

    @Test
    fun `clearPending removes a pending event but keeps a restored-late one`() = runTest {
        store.record(event(outcome = InterruptionOutcome.STILL_PENDING))
        store.clearPending()
        store.event.first() shouldBe null

        val restored = event(outcome = InterruptionOutcome.RESTORED_LATE)
        store.record(restored)
        store.clearPending()
        store.event.first() shouldBe restored
    }

    @Test
    fun `clear removes any event`() = runTest {
        store.record(event(outcome = InterruptionOutcome.RESTORED_LATE))
        store.clear()
        store.event.first() shouldBe null
    }

    @Test
    fun `a malformed stored enum decodes to no event`() = runTest {
        store.record(event())
        writeRawRecord(
            """{"occurredAtMillis":1000,"reason":"OTHER","outcome":"GARBAGE","workId":"tok-1"}""",
        )
        store.event.first() shouldBe null
    }

    @Test
    fun `a malformed stored record decodes to no event`() = runTest {
        store.record(event())
        writeRawRecord("{not json at all")
        store.event.first() shouldBe null
    }

    /**
     * A missing required field is as unreadable as a bad one — this is the all-or-nothing decode the
     * event had when it lived across four keys, where any absent key meant "no event".
     */
    @Test
    fun `a record missing a required field decodes to no event`() = runTest {
        store.record(event())
        writeRawRecord("""{"occurredAtMillis":1000,"reason":"OTHER"}""")
        store.event.first() shouldBe null
    }

    private suspend fun writeRawRecord(json: String) {
        appDataStore.store.edit { it[stringPreferencesKey("interruption.v2")] = json }
    }
}
