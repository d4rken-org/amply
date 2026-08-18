package eu.darken.amply.charging.core.qualification

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
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

class QualificationEvidenceStoreTest {
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
    private val store by lazy { QualificationEvidenceStore(appDataStore, identity, json) }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private fun evidence(
        buildIdentity: String = "build-a",
        protocolVersion: Int = QualificationProtocol.PROTOCOL_VERSION,
        adapterId: String = "lineageos-chargingcontrol-v1",
        exercised: List<String> = listOf("fixed:70", "fixed:85"),
    ) = QualificationEvidence(
        adapterId = adapterId,
        buildIdentity = buildIdentity,
        protocolVersion = protocolVersion,
        shape = RunShape.VARIABLE_CAP,
        signal = FlowSignal.COUNTER,
        capPercent = 70,
        observedHoldPercent = 80,
        exercisedPolicies = exercised,
        completedAtWallMillis = 1_000L,
    )

    private suspend fun writeRaw(value: String) {
        appDataStore.store.edit { it[stringPreferencesKey("qualification.result.v1")] = value }
    }

    @Test
    fun `nothing stored reads as absent`() = runTest {
        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a recorded pass round-trips`() = runTest {
        store.record(evidence()) shouldBe true

        val state = store.currentState()
        state.shouldBeInstanceOf<QualificationEvidenceState.Present>()
        state.evidence.adapterId shouldBe "lineageos-chargingcontrol-v1"
        state.evidence.capPercent shouldBe 70
        state.evidence.exercisedPolicies shouldBe listOf("fixed:70", "fixed:85")
        state.evidence.outcome shouldBe QualificationOutcomeRecord.PASSED
    }

    @Test
    fun `a pass from another ROM build does not apply here`() = runTest {
        store.record(evidence(buildIdentity = "build-b"))

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a pass from another protocol version does not apply here`() = runTest {
        store.record(evidence(protocolVersion = QualificationProtocol.PROTOCOL_VERSION + 1))

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    /**
     * The load-bearing fail-closed property. Unlike the enforcement record — whose only verdict is
     * the restrictive one, so field loss degrades safely — a positive record that lost its fields
     * must not read as a pass. `protocolVersion` defaults to 0 and is what scopes it out.
     */
    @Test
    fun `a record that lost its fields is not a pass`() = runTest {
        writeRaw("{}")

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a record naming only an outcome is still not a pass`() = runTest {
        writeRaw("""{"outcome":"PASSED"}""")

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    /**
     * The protocol-version guard alone is not enough: `outcome` defaults to the only constant it has,
     * which is the positive one. A record carrying nothing but a matching build and protocol version
     * would otherwise decode as a pass with no adapter, no cap, no signal and no exercised policies —
     * none of which a real run ever omits.
     */
    @Test
    fun `a record that matches the scope but carries no measurement is not a pass`() = runTest {
        writeRaw(
            """{"buildIdentity":"build-a","protocolVersion":${QualificationProtocol.PROTOCOL_VERSION}}""",
        )

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a pass missing its exercised policies is not credible`() = runTest {
        store.record(evidence(exercised = emptyList()))

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a pass with no measurement signal is not credible`() = runTest {
        store.record(evidence().copy(signal = FlowSignal.NONE))

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `a pass with a nonsensical cap is not credible`() = runTest {
        store.record(evidence().copy(capPercent = 0))

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }

    @Test
    fun `an undecodable record is not a pass`() = runTest {
        writeRaw("this is not json")

        store.currentState() shouldBe QualificationEvidenceState.Corrupt
    }

    @Test
    fun `a record naming an unknown outcome fails closed`() = runTest {
        writeRaw("""{"outcome":"INVENTED","protocolVersion":${QualificationProtocol.PROTOCOL_VERSION}}""")

        store.currentState() shouldBe QualificationEvidenceState.Corrupt
    }

    @Test
    fun `a later run replaces an earlier pass`() = runTest {
        store.record(evidence(exercised = listOf("fixed:70")))
        store.record(evidence(exercised = listOf("fixed:75", "fixed:90")))

        val state = store.currentState()
        state.shouldBeInstanceOf<QualificationEvidenceState.Present>()
        state.evidence.exercisedPolicies shouldBe listOf("fixed:75", "fixed:90")
    }

    @Test
    fun `clearing removes the pass`() = runTest {
        store.record(evidence())
        store.clear()

        store.currentState() shouldBe QualificationEvidenceState.Absent
    }
}
