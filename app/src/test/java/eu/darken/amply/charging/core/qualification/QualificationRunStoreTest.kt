package eu.darken.amply.charging.core.qualification

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

    /**
     * The tick loop reads a record, evaluates it, and merges the result back. A cancel committed in
     * that gap must survive the merge — losing it means the user pressed stop and the run carried on.
     */
    @Test
    fun `a cancel committed between a read and a merge survives`() = runTest {
        store.put(record())
        val read = store.currentRun()!!

        store.requestCancel()
        val merged = store.mergeProgress { it.copy(phase = RunPhase.CUT_1, lowCap = read.lowCap) }!!

        merged.cancelled shouldBe true
        merged.phase shouldBe RunPhase.CUT_1
    }

    /**
     * Finalization is slow — a policy restore, then evidence — so the record has to be claimed for it
     * in one transaction. A cancel either wins that transaction and downgrades the outcome, or finds
     * it claimed and does not commit; what it must never do is commit into a record whose outcome has
     * already been read and is about to be written out.
     */
    @Test
    fun `a cancel that loses the finalization claim does not commit`() = runTest {
        store.put(record())

        val claimed = store.claimForFinalization()!!
        claimed.cancelled shouldBe false
        store.requestCancel()

        store.currentRun()!!.cancelled shouldBe false
        store.currentRun()!!.finalizing shouldBe true
    }

    @Test
    fun `a cancel that wins the claim is what the claim reports`() = runTest {
        store.put(record())

        store.requestCancel()
        val claimed = store.claimForFinalization()!!

        claimed.cancelled shouldBe true
    }

    @Test
    fun `only one finalization can claim a record`() = runTest {
        store.put(record())

        store.claimForFinalization() shouldNotBe null
        store.claimForFinalization() shouldBe null
    }

    @Test
    fun `claiming with no run in flight claims nothing`() = runTest {
        store.claimForFinalization() shouldBe null
    }

    @Test
    fun `an acknowledged write is stamped on the record`() = runTest {
        store.put(record())

        store.markApplied(1_234_000L)

        store.currentRun()!!.commandAckedAtWallMillis shouldBe 1_234_000L
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
