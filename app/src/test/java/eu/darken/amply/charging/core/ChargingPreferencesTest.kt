package eu.darken.amply.charging.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.common.AppDataStore
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

class ChargingPreferencesTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val preferences by lazy {
        ChargingPreferences(
            AppDataStore(
                PreferenceDataStoreFactory.create(scope = storeScope) {
                    File(tempDir, "test.preferences_pb")
                },
            ),
        )
    }

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
}
