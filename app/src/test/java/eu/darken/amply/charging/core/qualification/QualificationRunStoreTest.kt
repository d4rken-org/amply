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

    private fun intent(
        terminal: RunTerminal = RunTerminal.Passed,
        decidedAtWallMillis: Long = 5_000L,
    ) = FinalizationIntent.of(terminal, decidedAtWallMillis)

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

        val claimed = store.claimForFinalization(intent())!!
        claimed.cancelled shouldBe false
        store.requestCancel()

        store.currentRun()!!.cancelled shouldBe false
        store.currentRun()!!.finalizing shouldBe true
    }

    /**
     * The downgrade a committed cancel causes is resolved *inside* the claim transaction and stored
     * with it. Resolving it in the caller instead would let the persisted outcome — the one a replay
     * after an interrupted finalization reads — disagree with the one that was actually applied.
     */
    @Test
    fun `a cancel that wins the claim downgrades the intent that gets persisted`() = runTest {
        store.put(record())

        store.requestCancel()
        val claimed = store.claimForFinalization(intent(RunTerminal.Passed))!!

        claimed.cancelled shouldBe true
        claimed.finalization?.toTerminal() shouldBe RunTerminal.Aborted(AbortReason.USER_CANCELLED)
        store.currentRun()!!.finalization?.toTerminal() shouldBe RunTerminal.Aborted(AbortReason.USER_CANCELLED)
    }

    @Test
    fun `a failed write that wins the claim downgrades the intent that gets persisted`() = runTest {
        store.put(record())

        store.markWriteFailed()
        val claimed = store.claimForFinalization(intent(RunTerminal.Passed))!!

        claimed.finalization?.toTerminal() shouldBe RunTerminal.Aborted(AbortReason.WRITE_FAILED)
    }

    /**
     * The outcome is written down with the claim, so an interrupted finalization has something to
     * replay instead of a boolean that only says one was started.
     */
    @Test
    fun `a fresh claim persists the proposed outcome and the closing measurement`() = runTest {
        store.put(record())

        val claimed = store.claimForFinalization(
            proposed = intent(RunTerminal.Refuted, decidedAtWallMillis = 7_000L),
            merge = { it.copy(observedHoldPercent = 77) },
        )!!

        claimed.observedHoldPercent shouldBe 77
        val stored = store.currentRun()!!
        stored.finalizing shouldBe true
        stored.observedHoldPercent shouldBe 77
        stored.finalization shouldBe FinalizationIntent(
            kind = TerminalKind.REFUTED,
            decidedAtWallMillis = 7_000L,
        )
        stored.finalization?.toTerminal() shouldBe RunTerminal.Refuted
    }

    /**
     * The whole point of the intent: a record that already decided its outcome keeps it. Overwriting
     * it with whatever the recovering caller proposed would tell the user a run was aborted while the
     * evidence its real terminal produced is already on disk — and, the other way round, would let a
     * later tick's readings replace a verdict measured from readings that are gone.
     */
    @Test
    fun `a forced reclaim replays the stored outcome rather than the proposed one`() = runTest {
        store.put(record())
        store.claimForFinalization(intent(RunTerminal.Passed, decidedAtWallMillis = 5_000L)) shouldNotBe null

        val reclaimed = store.claimForFinalization(
            proposed = intent(RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED), 9_000L),
            reclaimRunId = "run-1",
            merge = { it.copy(observedHoldPercent = 99) },
        )!!

        reclaimed.finalization shouldBe FinalizationIntent(
            kind = TerminalKind.PASSED,
            decidedAtWallMillis = 5_000L,
        )
        // The measurement stored with the outcome is kept too: a close-out path has no sample of its
        // own, and the one the verdict was decided from is the only one that describes it.
        reclaimed.observedHoldPercent shouldBe null
    }

    /**
     * A run whose outcome is decided is no longer a run whose outcome can be changed. Both flags would
     * otherwise land on a record that is only waiting for its terminal to be replayed, and be lost with
     * it.
     */
    @Test
    fun `a record carrying an outcome refuses a cancel and a write failure`() = runTest {
        store.put(record())
        store.claimForFinalization(intent(RunTerminal.Passed)) shouldNotBe null
        store.releaseFinalizationClaim("run-1")

        store.requestCancel()
        store.markWriteFailed()

        val stored = store.currentRun()!!
        stored.cancelled shouldBe false
        stored.writeFailed shouldBe false
    }

    @Test
    fun `only one finalization can claim a record`() = runTest {
        store.put(record())

        store.claimForFinalization(intent()) shouldNotBe null
        store.claimForFinalization(intent()) shouldBe null
    }

    @Test
    fun `claiming with no run in flight claims nothing`() = runTest {
        store.claimForFinalization(intent()) shouldBe null
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
        store.claimForFinalization(intent()) shouldNotBe null

        val reclaimed = store.claimForFinalization(intent(), reclaimRunId = "run-1")!!

        reclaimed.runId shouldBe "run-1"
        reclaimed.baseline shouldBe ChargePolicy.FixedLimit(80)
        store.currentRun()!!.finalizing shouldBe true
    }

    @Test
    fun `an unforced claim still refuses an already-claimed record`() = runTest {
        store.put(record())
        store.claimForFinalization(intent()) shouldNotBe null

        store.claimForFinalization(intent()) shouldBe null
    }

    /**
     * The force is scoped to the one record it was asked for: a run that started after the stale one
     * was read is a live run, not an abandoned claim, and finalizing it would abort it.
     */
    @Test
    fun `a forced reclaim refuses a different run`() = runTest {
        store.put(record())

        store.claimForFinalization(intent(), reclaimRunId = "run-other") shouldBe null

        store.currentRun()!!.finalizing shouldBe false
    }

    /**
     * The release says "this attempt did not finish", not "the outcome was never decided", so it keeps
     * the intent — and the claim that follows it is a replay of that outcome, not a fresh decision.
     */
    @Test
    fun `releasing a failed claim lets the record be finalized again, replaying its outcome`() = runTest {
        store.put(record())
        store.claimForFinalization(intent(RunTerminal.Passed, decidedAtWallMillis = 5_000L)) shouldNotBe null

        store.releaseFinalizationClaim("run-1")

        val released = store.currentRun()!!
        released.finalizing shouldBe false
        released.finalization?.toTerminal() shouldBe RunTerminal.Passed

        val reclaimed = store.claimForFinalization(
            intent(RunTerminal.Aborted(AbortReason.FINALIZATION_INTERRUPTED), 9_000L),
        )!!
        reclaimed.finalization?.toTerminal() shouldBe RunTerminal.Passed
        reclaimed.finalization?.decidedAtWallMillis shouldBe 5_000L
    }

    /** A truncated intent produces no terminal at all, so the caller falls back to its own. */
    @Test
    fun `an intent whose reason went missing yields no terminal`() = runTest {
        FinalizationIntent(kind = TerminalKind.ABORTED).toTerminal() shouldBe null
        FinalizationIntent(kind = TerminalKind.INCONCLUSIVE).toTerminal() shouldBe null
        FinalizationIntent(kind = TerminalKind.PASSED).toTerminal() shouldBe RunTerminal.Passed
        FinalizationIntent(kind = TerminalKind.REFUTED).toTerminal() shouldBe RunTerminal.Refuted
    }

    @Test
    fun `releasing a claim does not touch a different run's record`() = runTest {
        store.put(record())
        store.claimForFinalization(intent()) shouldNotBe null

        store.releaseFinalizationClaim("run-other")

        store.currentRun()!!.finalizing shouldBe true
    }

    /** A throw after the record was cleared must not put the finished run back. */
    @Test
    fun `releasing a claim on a cleared record resurrects nothing`() = runTest {
        store.put(record())
        store.claimForFinalization(intent()) shouldNotBe null
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
