package eu.darken.amply.charging.core.qualification

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class QualificationRunStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = SerializationModule.json()
    private val appDataStore by lazy {
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") },
        )
    }
    private val store by lazy { QualificationRunStore(appDataStore, json) }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private fun record() = QualificationRunRecord(
        baseline = ChargePolicy.FixedLimit(80),
        runId = "run-1",
        runToken = "token-1",
        adapterId = "lineageos-chargingcontrol-v1",
        buildIdentity = "build-a",
        protocolVersion = QualificationProtocol.PROTOCOL_VERSION,
        shape = RunShape.VARIABLE_CAP,
        lowCap = 70,
        releasePolicy = ChargePolicy.FixedLimit(85),
        signal = FlowSignal.COUNTER,
    )

    private suspend fun writeRaw(value: String) {
        appDataStore.store.edit { it[stringPreferencesKey("qualification.run.v1")] = value }
    }

    @Test
    fun `no run is the default`() = runTest {
        store.currentRun() shouldBe null
    }

    @Test
    fun `a run round-trips including both policies`() = runTest {
        store.put(record())

        val stored = store.currentRun()!!
        stored.runToken shouldBe "token-1"
        stored.baseline shouldBe ChargePolicy.FixedLimit(80)
        stored.releasePolicy shouldBe ChargePolicy.FixedLimit(85)
        stored.shape shouldBe RunShape.VARIABLE_CAP
        stored.signal shouldBe FlowSignal.COUNTER
    }

    @Test
    fun `cancelling flags the record rather than deleting it, so the runner can still restore`() = runTest {
        store.put(record())

        store.requestCancel()

        val stored = store.currentRun()!!
        stored.cancelled shouldBe true
        // The baseline must survive, or there would be nothing left to put back.
        stored.baseline shouldBe ChargePolicy.FixedLimit(80)
    }

    @Test
    fun `a failed write is flagged the same way`() = runTest {
        store.put(record())

        store.markWriteFailed()

        store.currentRun()!!.writeFailed shouldBe true
    }

    @Test
    fun `cancelling with no run in flight does nothing`() = runTest {
        store.requestCancel()

        store.currentRun() shouldBe null
    }

    @Test
    fun `clearing removes the run`() = runTest {
        store.put(record())
        store.clear()

        store.currentRun() shouldBe null
    }

    /**
     * An unreadable record is no run — the same all-or-nothing decode the full-charge session uses.
     * The restore is not lost with it: it was registered as a `FullChargeStore` recovery target before
     * the first write, which is exactly why that belt-and-braces registration exists.
     */
    @Test
    fun `an undecodable record reads as no run`() = runTest {
        writeRaw("not json at all")

        store.currentRun() shouldBe null
    }

    @Test
    fun `a record without its baseline policy cannot be half-read`() = runTest {
        writeRaw("""{"runId":"run-1","runToken":"token-1"}""")

        store.currentRun() shouldBe null
    }
}
