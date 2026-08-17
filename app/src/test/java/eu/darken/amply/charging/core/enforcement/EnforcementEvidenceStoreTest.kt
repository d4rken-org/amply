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
        verdict: EnforcementVerdict = EnforcementVerdict.REFUTED,
        buildIdentity: String = "build-a",
        algorithmVersion: Int = EnforcementVerdictEngine.ALGORITHM_VERSION,
        adapterId: String = "lineageos-chargingcontrol-v1",
        observedPercent: Int = 80,
    ) = EnforcementEvidence(
        adapterId = adapterId,
        buildIdentity = buildIdentity,
        algorithmVersion = algorithmVersion,
        verdict = verdict,
        capPercent = 80,
        observedPercent = observedPercent,
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
    fun `a version 1 refutation survives the version bump and stays terminal`() = runTest {
        // Version 1's heuristic also weighed a hardware signal that turned out to be session-scoped —
        // but only its confirmation arm rested on that signal. Discarding its refutations too would
        // fail OPEN: with the opt-in preference retained, an app update would silently re-enable
        // control on a build already observed charging past its cap. Pinned to the literal 1 rather
        // than ALGORITHM_VERSION - 1: the point is the specific superseded heuristic.
        EnforcementVerdictEngine.ALGORITHM_VERSION shouldBe 2
        writeRaw(
            json.encodeToString(
                EnforcementEvidence.serializer(),
                evidence(algorithmVersion = 1, observedPercent = 84),
            ),
        )

        // Restamped to the current version, so it still applies to this build…
        store.currentState() shouldBe
            EnforcementEvidenceState.Present(evidence(observedPercent = 84))
        // …and is still terminal: a later observation must not record over it.
        store.record(evidence(observedPercent = 91)) shouldBe false
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.observedPercent shouldBe 84
    }

    @Test
    fun `a version 1 confirmation reads absent, not corrupt`() = runTest {
        // The literal wire form a version-1 build wrote: the CONFIRMED constant no longer exists, so
        // typed deserialization would fail and read Corrupt — which the gate treats as a refutation
        // and would lock the device out permanently. It is worth nothing, not less than nothing.
        writeRaw(
            """
            {"adapterId":"lineageos-chargingcontrol-v1","buildIdentity":"build-a","algorithmVersion":1,
            "verdict":"CONFIRMED","capPercent":80,"observedPercent":80,"observedAtWallMillis":1000}
            """.trimIndent(),
        )

        store.currentState() shouldBe EnforcementEvidenceState.Absent
    }

    @Test
    fun `a version 1 record with an unknown verdict reads corrupt`() = runTest {
        writeRaw(
            """{"buildIdentity":"build-a","algorithmVersion":1,"verdict":"SOMETHING_ELSE"}""",
        )

        store.currentState() shouldBe EnforcementEvidenceState.Corrupt
    }

    @Test
    fun `a record from an unknown future algorithm version reads absent`() = runTest {
        // A downgrade after a newer build wrote its own verdict: that heuristic is not this one's, and
        // nothing here can translate it.
        store.record(
            evidence(algorithmVersion = EnforcementVerdictEngine.ALGORITHM_VERSION + 1),
        ) shouldBe true
        store.currentState() shouldBe EnforcementEvidenceState.Absent
    }

    @Test
    fun `a corrupt record reads corrupt, never absent`() = runTest {
        writeRaw("{not json")
        store.currentState() shouldBe EnforcementEvidenceState.Corrupt
    }

    @Test
    fun `a refutation replaces a corrupt record`() = runTest {
        // Both fail closed, and the refutation is the more informative of the two.
        writeRaw("{not json")
        store.record(evidence()) shouldBe true
        store.currentState() shouldBe EnforcementEvidenceState.Present(evidence())
    }

    @Test
    fun `a refutation is terminal and is never overwritten for the same scope`() = runTest {
        store.record(evidence(observedPercent = 84)) shouldBe true
        // Nothing observable can redeem the build, so a later record for the same scope is not news.
        store.record(evidence(observedPercent = 91)) shouldBe false
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.observedPercent shouldBe 84
    }

    @Test
    fun `a refutation from a different build does not block this one`() = runTest {
        // A new ROM build is a new question; the old build's refutation says nothing about it.
        store.record(evidence(buildIdentity = "build-b")) shouldBe true
        store.record(evidence(observedPercent = 91)) shouldBe true
        val state = store.currentState().shouldBeInstanceOf<EnforcementEvidenceState.Present>()
        state.evidence.observedPercent shouldBe 91
    }
}
