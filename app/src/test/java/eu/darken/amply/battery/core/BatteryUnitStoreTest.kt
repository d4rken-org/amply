package eu.darken.amply.battery.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.enforcement.BuildIdentitySource
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
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

class BatteryUnitStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = SerializationModule.json()
    private val appDataStore by lazy {
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") },
        )
    }
    private var build = "build-a"
    private val identity = object : BuildIdentitySource {
        override fun current() = build
    }
    private val store by lazy { BatteryUnitStore(appDataStore, identity, json) }
    private val key = stringPreferencesKey("battery.current_unit.v1")

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    private suspend fun raw(): String? = appDataStore.store.data.first()[key]

    private suspend fun writeRaw(value: String) {
        appDataStore.store.edit { it[key] = value }
    }

    @Test
    fun `nothing stored reads unknown`() = runTest {
        store.read() shouldBe CurrentUnit.UNKNOWN
    }

    @Test
    fun `a learned milliamp unit round-trips`() = runTest {
        store.learn(CurrentUnit.MILLI) shouldBe CurrentUnit.MILLI
        store.read() shouldBe CurrentUnit.MILLI
    }

    @Test
    fun `a learned microamp unit round-trips`() = runTest {
        store.learn(CurrentUnit.MICRO) shouldBe CurrentUnit.MICRO
        store.read() shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `the record is a stable wire format`() = runTest {
        store.learn(CurrentUnit.MILLI)
        raw() shouldBe """{"build":"build-a","unit":"milli"}"""
    }

    @Test
    fun `another build's record reads unknown`() = runTest {
        store.learn(CurrentUnit.MILLI)
        build = "build-b"
        store.read() shouldBe CurrentUnit.UNKNOWN
    }

    @Test
    fun `a corrupt record reads unknown`() = runTest {
        writeRaw("""{"build":"build-a","unit":"nano"}""")
        store.read() shouldBe CurrentUnit.UNKNOWN

        writeRaw("not json")
        store.read() shouldBe CurrentUnit.UNKNOWN
    }

    @Test
    fun `a stored microamp unit is never regressed to milliamps`() = runTest {
        store.learn(CurrentUnit.MICRO)
        store.learn(CurrentUnit.MILLI) shouldBe CurrentUnit.MICRO
        store.read() shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `a stored milliamp unit is overturned by microamp proof`() = runTest {
        store.learn(CurrentUnit.MILLI)
        store.learn(CurrentUnit.MICRO) shouldBe CurrentUnit.MICRO
        store.read() shouldBe CurrentUnit.MICRO
    }

    @Test
    fun `learning unknown writes nothing`() = runTest {
        store.learn(CurrentUnit.UNKNOWN) shouldBe CurrentUnit.UNKNOWN
        raw() shouldBe null

        store.learn(CurrentUnit.MILLI)
        val before = raw()
        store.learn(CurrentUnit.UNKNOWN) shouldBe CurrentUnit.MILLI
        raw() shouldBe before
    }

    @Test
    fun `a new build overwrites another build's record`() = runTest {
        store.learn(CurrentUnit.MICRO)
        build = "build-b"

        // Not merged with the old build's MICRO: that verdict says nothing about this build.
        store.learn(CurrentUnit.MILLI) shouldBe CurrentUnit.MILLI
        store.read() shouldBe CurrentUnit.MILLI
        raw() shouldBe """{"build":"build-b","unit":"milli"}"""
    }
}
