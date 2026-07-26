package eu.darken.amply.common.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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

class DataStoreValueTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore by lazy {
        AppDataStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { File(tempDir, "test.preferences_pb") },
        )
    }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
    }

    @Test
    fun `absent key reads the default`() = runTest {
        dataStore.createValue("flag", false).value() shouldBe false
        dataStore.createValue<Long?>("stamp").value() shouldBe null
    }

    @Test
    fun `value round-trips`() = runTest {
        val flag = dataStore.createValue("flag", false)
        flag.value(true)
        flag.value() shouldBe true
    }

    @Test
    fun `update returns both sides and can clear the key`() = runTest {
        val counter = dataStore.createValue("counter", 0)

        counter.update { it + 5 } shouldBe DataStoreValue.Updated(old = 0, new = 5)
        counter.update { it + 1 } shouldBe DataStoreValue.Updated(old = 5, new = 6)

        // Returning null clears the key, so the next read falls back to the default.
        counter.update { null } shouldBe DataStoreValue.Updated(old = 6, new = 0)
        counter.value() shouldBe 0

        // Assert the key is genuinely gone rather than holding a persisted 0 — reading back the
        // default cannot tell those apart, and "absent" is what session/recovery clearing relies on.
        dataStore.store.data.first().contains(intPreferencesKey("counter")) shouldBe false
    }

    @Test
    fun `clearing a nullable value removes the key`() = runTest {
        val stamp = dataStore.createValue<Long?>("stamp")
        stamp.value(1_000L)
        dataStore.store.data.first().contains(longPreferencesKey("stamp")) shouldBe true

        stamp.value(null)

        stamp.value() shouldBe null
        dataStore.store.data.first().contains(longPreferencesKey("stamp")) shouldBe false
    }

    /**
     * [DataStoreValue.update] has to be a single read-modify-write transaction, not a read followed
     * by a write. Composite records are edited this way — `markConnected` flips one field of the
     * session while another caller may be stamping its provenance — so a lost update would drop
     * safety-critical state. Sequential tests cannot tell the two implementations apart.
     */
    @Test
    fun `concurrent updates do not lose writes`() = runBlocking {
        val counter = dataStore.createValue("counter", 0)

        val workers = 8
        val incrementsEach = 25
        (0 until workers).map {
            launch(Dispatchers.IO) { repeat(incrementsEach) { counter.update { current -> current + 1 } } }
        }.forEach { it.join() }

        counter.value() shouldBe workers * incrementsEach
    }

    /**
     * The defect this whole primitive exists for: Amply keeps one shared DataStore, so an unrelated
     * write hands the entire snapshot to every collector. Before the dedupe moved into the value, the
     * stats recorder's ~20s timestamp write re-emitted `captureEnabled` unchanged and flashed the
     * dashboard's charging card through its loading state.
     *
     * Real time, not [runTest]'s virtual clock: the store and its collector run on Dispatchers.IO, so
     * a virtual clock would race them rather than wait for them.
     */
    @Test
    fun `an unrelated key's write does not re-emit`() = runBlocking {
        val flag = dataStore.createValue("flag", false)
        val unrelated = longPreferencesKey("unrelated")

        val seen = CopyOnWriteArrayList<Boolean>()
        val collector = launch(Dispatchers.IO) { flag.flow.toList(seen) }
        awaitSize(seen, 1)

        // Four writes of genuinely new values, so DataStore really does emit a new snapshot each
        // time — it is the *value* that has to stay quiet, not the store.
        repeat(3) { i -> dataStore.store.edit { it[unrelated] = i.toLong() } }
        dataStore.store.edit { it[unrelated] = 99L }

        // A real change to our own key still gets through.
        flag.value(true)
        awaitSize(seen, 2)

        seen.toList() shouldBe listOf(false, true)
        collector.cancel()
    }

    /**
     * Documents DataStore's own behaviour rather than this class's: an equal snapshot is suppressed
     * by `SingleProcessDataStore` before any of our code runs, so this still passes with the
     * dedupe removed. It is here precisely so nobody mistakes it for a guard — a regression test
     * built on re-writing the *same* value would pass against the very bug it meant to catch.
     */
    @Test
    fun `writing the same value again does not re-emit`() = runBlocking {
        val flag = dataStore.createValue("flag", false)

        val seen = CopyOnWriteArrayList<Boolean>()
        val collector = launch(Dispatchers.IO) { flag.flow.toList(seen) }
        awaitSize(seen, 1)

        flag.value(true)
        awaitSize(seen, 2)
        repeat(3) { flag.value(true) }

        seen.toList() shouldBe listOf(false, true)
        collector.cancel()
    }

    /**
     * A key name reused for a different type is the one way a raw value can be the wrong class.
     * Degrading to the default beats throwing: a ClassCastException inside the reader would kill the
     * collector's flow permanently, taking a working setting offline until the app restarts.
     */
    @Test
    fun `a key stored under the wrong type reads the default instead of throwing`() = runTest {
        dataStore.store.edit { it[stringPreferencesKey("flag")] = "not-a-boolean" }

        dataStore.createValue("flag", true).value() shouldBe true
    }

    /** Waits for exactly [size] emissions, then a beat longer to catch a spurious extra one. */
    private suspend fun awaitSize(seen: List<*>, size: Int) {
        withTimeout(TIMEOUT) {
            while (seen.size < size) delay(5)
        }
        delay(SETTLE)
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val SETTLE = 150L
    }
}
