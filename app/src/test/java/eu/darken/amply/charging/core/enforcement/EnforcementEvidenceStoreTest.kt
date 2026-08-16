package eu.darken.amply.charging.core.enforcement

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class EnforcementEvidenceStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = SerializationModule.json()
    private val appDataStore by lazy {
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") },
        )
    }
    private val identity = object : BuildIdentitySource {
        override fun current() = "build-a"
    }
    private val store by lazy { EnforcementEvidenceStore(appDataStore, identity, json) }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private fun evidence(
        verdict: EnforcementVerdict = EnforcementVerdict.CONFIRMED,
        buildIdentity: String = "build-a",
        algorithmVersion: Int = EnforcementVerdictEngine.ALGORITHM_VERSION,
        adapterId: String = "lineageos-chargingcontrol-v1",
    ) = EnforcementEvidence(
        adapterId = adapterId,
        buildIdentity = buildIdentity,
        algorithmVersion = algorithmVersion,
        verdict = verdict,
        capPercent = 80,
        observedPercent = 80,
        observedAtWallMillis = 1_000L,
    )

    private suspend fun writeRaw(value: String) {
        appDataStore.store.edit { it[stringPreferencesKey("enforcement.evidence.v1")] = value }
    }

    @Test
    fun `nothing stored reads absent`() = runTest {
        store.currentState() shouldBe EnforcementEvidenceState.Absent
    }

    @Test
    fun `a recorded verdict round-trips`() = runTest {
        store.record(evidence()) shouldBe true
        store.currentState() shouldBe EnforcementEvidenceState.Present(evidence())
    }

    @Test
    fun `a record from another build reads absent`() = runTest {
        // HAL capability is build-scoped: an update can drop the LIMIT mode on identical hardware.
        store.record(evidence(buildIdentity = "build-b")) shouldBe true
        store.currentState() shouldBe EnforcementEvidenceState.Absent
    }

    @Test
    fun `a record from an older algorithm version reads absent`() = runTest {
        store.record(
            evidence(algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION - 1),
        ) shouldBe true
        store.currentState() shouldBe EnforcementEvidenceState.Absent
    }

    @Test
    fun `a corrupt record reads corrupt, never absent`() = runTest {
        writeRaw("{not json")
        store.currentState() shouldBe EnforcementEvidenceState.Corrupt
    }

    @Test
    fun `a confirmation never replaces a corrupt record`() = runTest {
        // The unreadable record could be a refutation; overwriting it would reopen control.
        writeRaw("{not json")
        store.record(evidence(verdict = EnforcementVerdict.CONFIRMED)) shouldBe false
        store.currentState() shouldBe EnforcementEvidenceState.Corrupt
    }

    @Test
    fun `a refutation replaces a corrupt record`() = runTest {
        writeRaw("{not json")
        store.record(evidence(verdict = EnforcementVerdict.REFUTED)) shouldBe true
        store.currentState() shouldBe
            EnforcementEvidenceState.Present(evidence(verdict = EnforcementVerdict.REFUTED))
    }

    @Test
    fun `a refutation overwrites a confirmation`() = runTest {
        store.record(evidence(verdict = EnforcementVerdict.CONFIRMED)) shouldBe true
        store.record(evidence(verdict = EnforcementVerdict.REFUTED)) shouldBe true
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.verdict shouldBe EnforcementVerdict.REFUTED
    }

    @Test
    fun `a confirmation does not overwrite a refutation for the same scope`() = runTest {
        store.record(evidence(verdict = EnforcementVerdict.REFUTED)) shouldBe true
        store.record(evidence(verdict = EnforcementVerdict.CONFIRMED)) shouldBe false
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.verdict shouldBe EnforcementVerdict.REFUTED
    }

    @Test
    fun `a confirmation replaces a refutation from a different build`() = runTest {
        // A new ROM build is a new question; the old build's refutation says nothing about it.
        store.record(evidence(verdict = EnforcementVerdict.REFUTED, buildIdentity = "build-b")) shouldBe true
        store.record(evidence(verdict = EnforcementVerdict.CONFIRMED)) shouldBe true
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.verdict shouldBe EnforcementVerdict.CONFIRMED
    }
}
