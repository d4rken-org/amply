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

    /**
     * The claim is durable, so a process that dies mid-finalization leaves one behind. Without a way
     * to take it back the record could never be finalized again — and a record that never finalizes
     * is a run that never ends: rules stay suspended, sessions are refused, the baseline is never
     * restored. Only the startup repair asks for this, and only for another process's record.
     */
    @Test
    fun `a forced reclaim takes back a claim left behind by a dead process`() = runTest {
        store.put(record())
        store.claimForFinalization() shouldNotBe null

        val reclaimed = store.claimForFinalization(reclaimRunId = "run-1")!!

        reclaimed.runId shouldBe "run-1"
        reclaimed.baseline shouldBe ChargePolicy.FixedLimit(80)
        store.currentRun()!!.finalizing shouldBe true
    }

    @Test
    fun `an unforced claim still refuses an already-claimed record`() = runTest {
        store.put(record())
        store.claimForFinalization() shouldNotBe null

        store.claimForFinalization() shouldBe null
    }

    /**
     * The force is scoped to the one record it was asked for: a run that started after the stale one
     * was read is a live run, not an abandoned claim, and finalizing it would abort it.
     */
    @Test
    fun `a forced reclaim refuses a different run`() = runTest {
        store.put(record())

        store.claimForFinalization(reclaimRunId = "run-other") shouldBe null

        store.currentRun()!!.finalizing shouldBe false
    }

    @Test
    fun `releasing a failed claim lets the record be finalized again`() = runTest {
        store.put(record())
        store.claimForFinalization() shouldNotBe null

        store.releaseFinalizationClaim("run-1")

        store.currentRun()!!.finalizing shouldBe false
        store.claimForFinalization() shouldNotBe null
    }

    @Test
    fun `releasing a claim does not touch a different run's record`() = runTest {
        store.put(record())
        store.claimForFinalization() shouldNotBe null

        store.releaseFinalizationClaim("run-other")

        store.currentRun()!!.finalizing shouldBe true
    }

    /** A throw after the record was cleared must not put the finished run back. */
    @Test
    fun `releasing a claim on a cleared record resurrects nothing`() = runTest {
        store.put(record())
        store.claimForFinalization() shouldNotBe null
        store.clear()

        store.releaseFinalizationClaim("run-1")

        store.currentRun() shouldBe null
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
