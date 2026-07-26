package eu.darken.amply.charging.core

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class ChargingPreferencesTest {
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
    private val preferences by lazy { ChargingPreferences(appDataStore, SerializationModule.json()) }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    @Test
    fun `last persistent policy is null until the first persistent write`() = runTest {
        preferences.lastPersistentPolicyNow() shouldBe null

        preferences.recordRequested(ChargePolicy.Unrestricted, persistent = false, nowMillis = 1L)

        preferences.lastPersistentPolicyNow() shouldBe null
        preferences.lastRequestedNow() shouldBe ChargePolicy.Unrestricted
    }

    @Test
    fun `temporary session writes never touch the persistent policy`() = runTest {
        preferences.recordRequested(ChargePolicy.FixedLimit(80), persistent = true, nowMillis = 1L)
        // A full-charge session temporarily lifts the limit.
        preferences.recordRequested(ChargePolicy.Unrestricted, persistent = false, nowMillis = 2L)

        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.FixedLimit(80)
        preferences.lastRequestedNow() shouldBe ChargePolicy.Unrestricted
    }

    @Test
    fun `persistent unrestricted is recorded but keeps the protective baseline`() = runTest {
        preferences.recordRequested(ChargePolicy.FixedLimit(80), persistent = true, nowMillis = 1L)
        preferences.recordRequested(ChargePolicy.Unrestricted, persistent = true, nowMillis = 2L)

        // The any-level gesture must see the explicit Unrestricted choice…
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Unrestricted
        // …while the restore fallback keeps the last protective choice.
        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(80)
    }

    @Test
    fun `persistent protective writes update both signals`() = runTest {
        preferences.recordRequested(ChargePolicy.Adaptive, persistent = true, nowMillis = 1L)

        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Adaptive
        preferences.protectivePolicyNow() shouldBe ChargePolicy.Adaptive
    }

    // --- Per-field corruption -------------------------------------------------------------------
    // These four facts share one stored record but must degrade INDEPENDENTLY. Falling back
    // wholesale on any single bad field would quietly swap a user's real protective baseline for the
    // 80% default, so each field is validated on its own.

    @Test
    fun `an unreadable last-requested policy leaves the other fields intact`() = runTest {
        writeRawRecord(
            """{"lastRequested":"fixed:not-a-number","lastRequestedAt":5,""" +
                """"protective":"adaptive","lastPersistent":"unrestricted"}""",
        )

        preferences.lastRequestedNow() shouldBe null
        preferences.lastRequestedAtNow() shouldBe 5L
        preferences.protectivePolicyNow() shouldBe ChargePolicy.Adaptive
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Unrestricted
    }

    @Test
    fun `an unreadable protective policy falls back to the limit without touching the rest`() = runTest {
        writeRawRecord(
            """{"lastRequested":"adaptive","lastRequestedAt":5,""" +
                """"protective":"bogus","lastPersistent":"adaptive"}""",
        )

        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(80)
        preferences.lastRequestedNow() shouldBe ChargePolicy.Adaptive
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.Adaptive
    }

    @Test
    fun `an unreadable persistent policy leaves the protective baseline intact`() = runTest {
        writeRawRecord(
            """{"lastRequested":"adaptive","lastRequestedAt":5,""" +
                """"protective":"fixed:90","lastPersistent":"fixed:oops"}""",
        )

        preferences.lastPersistentPolicyNow() shouldBe null
        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(90)
    }

    /**
     * A wrong-typed field is the case a typed whole-record decode gets wrong: it fails on
     * `lastRequestedAt` and takes the valid 90 % baseline down with it. Losing that baseline is a
     * real downgrade — Amply would restore the battery to 80 % instead of the user's 90 %.
     */
    @Test
    fun `a wrong-typed field does not destroy the other fields`() = runTest {
        writeRawRecord(
            """{"lastRequested":"adaptive","lastRequestedAt":"bad",""" +
                """"protective":"fixed:90","lastPersistent":"fixed:90"}""",
        )

        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(90)
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.FixedLimit(90)
        preferences.lastRequestedNow() shouldBe ChargePolicy.Adaptive
        // Only the unreadable field falls back.
        preferences.lastRequestedAtNow() shouldBe 0L
    }

    /** The follow-on damage: a later write must not persist a fallback over the surviving baseline. */
    @Test
    fun `a later write preserves fields that survived a wrong-typed record`() = runTest {
        writeRawRecord(
            """{"lastRequested":"adaptive","lastRequestedAt":"bad",""" +
                """"protective":"fixed:90","lastPersistent":"fixed:90"}""",
        )

        preferences.recordRequested(ChargePolicy.Unrestricted, persistent = false, nowMillis = 7L)

        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(90)
        preferences.lastPersistentPolicyNow() shouldBe ChargePolicy.FixedLimit(90)
        preferences.lastRequestedNow() shouldBe ChargePolicy.Unrestricted
        preferences.lastRequestedAtNow() shouldBe 7L
    }

    @Test
    fun `a null field reads as absent`() = runTest {
        writeRawRecord("""{"lastRequested":null,"lastRequestedAt":null,"protective":"adaptive"}""")

        preferences.lastRequestedNow() shouldBe null
        preferences.lastRequestedAtNow() shouldBe 0L
        preferences.protectivePolicyNow() shouldBe ChargePolicy.Adaptive
    }

    @Test
    fun `a wholly malformed record falls back to empty rather than throwing`() = runTest {
        writeRawRecord("{not json at all")

        preferences.lastRequestedNow() shouldBe null
        preferences.lastRequestedAtNow() shouldBe 0L
        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(80)
        preferences.lastPersistentPolicyNow() shouldBe null
    }

    @Test
    fun `a record written by a newer build keeps the fields this build understands`() = runTest {
        writeRawRecord(
            """{"lastRequested":"adaptive","lastRequestedAt":5,"protective":"fixed:90",""" +
                """"lastPersistent":"adaptive","somethingNew":{"nested":true}}""",
        )

        preferences.lastRequestedNow() shouldBe ChargePolicy.Adaptive
        preferences.protectivePolicyNow() shouldBe ChargePolicy.FixedLimit(90)
    }

    // --- Projection dedupe ----------------------------------------------------------------------

    /**
     * All four projections ride one record now, so each carries its own `distinctUntilChanged`.
     * Without it, a timestamp-only write would wake every collector — including the widget, which
     * collects [ChargingPreferences.lastRequested] directly.
     */
    @Test
    fun `re-requesting the same policy does not re-emit the policy projections`() = runBlocking {
        preferences.recordRequested(ChargePolicy.FixedLimit(80), persistent = true, nowMillis = 1L)

        val requested = CopyOnWriteArrayList<ChargePolicy?>()
        val protective = CopyOnWriteArrayList<ChargePolicy>()
        val persistent = CopyOnWriteArrayList<ChargePolicy?>()
        val stamps = CopyOnWriteArrayList<Long>()
        val collectors = listOf(
            launch(Dispatchers.IO) { preferences.lastRequested.toList(requested) },
            launch(Dispatchers.IO) { preferences.protectivePolicy.toList(protective) },
            launch(Dispatchers.IO) { preferences.lastPersistentPolicy.toList(persistent) },
            launch(Dispatchers.IO) { preferences.lastRequestedAt.toList(stamps) },
        )
        withTimeout(TIMEOUT) { while (stamps.isEmpty()) delay(5) }

        // Same policy, later clock: only the timestamp genuinely changes.
        preferences.recordRequested(ChargePolicy.FixedLimit(80), persistent = true, nowMillis = 2L)
        withTimeout(TIMEOUT) { while (stamps.size < 2) delay(5) }
        delay(SETTLE)

        requested.toList() shouldBe listOf(ChargePolicy.FixedLimit(80))
        protective.toList() shouldBe listOf(ChargePolicy.FixedLimit(80))
        persistent.toList() shouldBe listOf(ChargePolicy.FixedLimit(80))
        // The timestamp is the one thing that did change.
        stamps.toList() shouldBe listOf(1L, 2L)

        collectors.forEach { it.cancel() }
    }

    /** A temporary (non-persistent) request must leave both persistent projections silent. */
    @Test
    fun `a temporary request does not re-emit the persistent projections`() = runBlocking {
        preferences.recordRequested(ChargePolicy.FixedLimit(80), persistent = true, nowMillis = 1L)

        val protective = CopyOnWriteArrayList<ChargePolicy>()
        val persistent = CopyOnWriteArrayList<ChargePolicy?>()
        val requested = CopyOnWriteArrayList<ChargePolicy?>()
        val collectors = listOf(
            launch(Dispatchers.IO) { preferences.protectivePolicy.toList(protective) },
            launch(Dispatchers.IO) { preferences.lastPersistentPolicy.toList(persistent) },
            launch(Dispatchers.IO) { preferences.lastRequested.toList(requested) },
        )
        withTimeout(TIMEOUT) { while (requested.isEmpty()) delay(5) }

        preferences.recordRequested(ChargePolicy.Unrestricted, persistent = false, nowMillis = 2L)
        withTimeout(TIMEOUT) { while (requested.size < 2) delay(5) }
        delay(SETTLE)

        requested.toList() shouldBe listOf(ChargePolicy.FixedLimit(80), ChargePolicy.Unrestricted)
        protective.toList() shouldBe listOf(ChargePolicy.FixedLimit(80))
        persistent.toList() shouldBe listOf(ChargePolicy.FixedLimit(80))

        collectors.forEach { it.cancel() }
    }

    /** Writes the stored JSON directly, standing in for a corrupt or foreign-written record. */
    private suspend fun writeRawRecord(json: String) {
        appDataStore.store.edit { it[stringPreferencesKey("policy.v2")] = json }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val SETTLE = 200L
    }
}
