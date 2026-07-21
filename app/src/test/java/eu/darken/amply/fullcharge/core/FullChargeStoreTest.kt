package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.common.AppDataStore
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

class FullChargeStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy {
        FullChargeStore(
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
    fun `any-level option defaults to off`() = runTest {
        store.isQuickFullChargeAnyLevel() shouldBe false
        store.quickFullChargeAnyLevel.first() shouldBe false
    }

    @Test
    fun `any-level option round-trips`() = runTest {
        store.setQuickFullChargeAnyLevel(true)
        store.isQuickFullChargeAnyLevel() shouldBe true
        store.quickFullChargeAnyLevel.first() shouldBe true

        store.setQuickFullChargeAnyLevel(false)
        store.isQuickFullChargeAnyLevel() shouldBe false
    }

    @Test
    fun `any-level option is independent of the master toggle`() = runTest {
        store.setQuickFullChargeAnyLevel(true)
        store.isQuickFullChargeEnabled() shouldBe false

        store.setQuickFullChargeEnabled(true)
        store.setQuickFullChargeEnabled(false)
        store.isQuickFullChargeAnyLevel() shouldBe true
    }
}
