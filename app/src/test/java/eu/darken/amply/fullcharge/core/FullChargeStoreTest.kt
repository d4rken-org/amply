package eu.darken.amply.fullcharge.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FullChargeStoreTest {
    @TempDir
    lateinit var tempDir: File

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val storeFile by lazy { File(tempDir, "test.preferences_pb") }
    private val appDataStore by lazy {
        AppDataStore(PreferenceDataStoreFactory.create(scope = storeScope) { storeFile })
    }

    // The production Json, so these tests exercise the encoding the app actually ships.
    private val store by lazy { FullChargeStore(appDataStore, SerializationModule.json()) }

    private val restartScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * A second store over the same file, standing in for the app relaunching after process death.
     * The first store must be fully released first — DataStore allows only one active instance per
     * file, which is exactly the constraint a real process death satisfies.
     */
    private suspend fun newStoreOverSameFile(): FullChargeStore {
        storeScope.coroutineContext.job.cancelAndJoin()
        return FullChargeStore(
            AppDataStore(PreferenceDataStoreFactory.create(scope = restartScope) { storeFile }),
            SerializationModule.json(),
        )
    }

    @AfterEach
    fun teardown() {
        storeScope.cancel()
        restartScope.cancel()
    }

    @Test
    fun `any-level option defaults to off`() = runTest {
        store.isQuickFullChargeAnyLevel() shouldBe false
        store.quickFullChargeAnyLevel.flow.first() shouldBe false
    }

    @Test
    fun `any-level option round-trips`() = runTest {
        store.setQuickFullChargeAnyLevel(true)
        store.isQuickFullChargeAnyLevel() shouldBe true
        store.quickFullChargeAnyLevel.flow.first() shouldBe true

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

    private val fixedLimit = ChargePolicy.FixedLimit(80)

    @Test
    fun `session provenance round-trips`() = runTest {
        val provenance = WorkProvenance(token = "tok-a", pid = 42, bootCount = 7, createdAtMillis = 1_000L)
        store.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = provenance)

        store.currentSession()?.provenance shouldBe provenance
    }

    @Test
    fun `a legacy session record without provenance decodes to null`() = runTest {
        store.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = null)

        store.currentSession()?.provenance shouldBe null
    }

    @Test
    fun `clearing a session removes its provenance`() = runTest {
        store.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            provenance = WorkProvenance("tok-a", 42, 7, 1_000L),
        )
        store.clearSession()

        store.currentSession() shouldBe null
    }

    @Test
    fun `recovery provenance round-trips including a null boot count`() = runTest {
        val provenance = WorkProvenance(token = "tok-r", pid = 99, bootCount = null, createdAtMillis = 2_500L)
        store.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            provenance = provenance,
            origin = RecoveryOrigin.SESSION_RESTORE,
        )

        store.pendingRecoveryProvenance() shouldBe provenance
    }

    @Test
    fun `clearing the recovery target removes its provenance`() = runTest {
        store.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            provenance = WorkProvenance("tok-r", 99, 3, 2_500L),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        store.clearPendingRecoveryTarget()

        store.pendingRecoveryTarget() shouldBe null
        store.pendingRecoveryProvenance() shouldBe null
    }

    @Test
    fun `adoptSessionOwner only stamps when a session is active`() = runTest {
        // No session: adoption is a no-op and doesn't fabricate one.
        store.adoptSessionOwner(WorkProvenance("tok-x", 1, 1, 1L))
        store.currentSession() shouldBe null

        store.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = WorkProvenance("old", 1, 1, 1_000L))
        val adopted = WorkProvenance(token = "new", pid = 2, bootCount = 1, createdAtMillis = 1_000L)
        store.adoptSessionOwner(adopted)

        store.currentSession()?.provenance shouldBe adopted
    }

    @Test
    fun `adoptRecoveryOwner only stamps when a target is present`() = runTest {
        store.adoptRecoveryOwner(WorkProvenance("tok-x", 1, 1, 1L))
        store.pendingRecoveryProvenance() shouldBe null

        store.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            provenance = WorkProvenance("old", 1, 1, 5L),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        val adopted = WorkProvenance(token = "new", pid = 2, bootCount = 4, createdAtMillis = 9L)
        store.adoptRecoveryOwner(adopted)

        store.pendingRecoveryProvenance() shouldBe adopted
    }

    @Test
    fun `markConnectedAndAdopt sets connected and owner in one edit`() = runTest {
        store.startSession(fixedLimit, startedAtMillis = 1_000L, provenance = WorkProvenance("old", 1, 1, 1_000L))
        val adopted = WorkProvenance(token = "new", pid = 2, bootCount = 1, createdAtMillis = 1_000L)

        store.markConnectedAndAdopt(adopted)

        val session = store.currentSession()
        session?.connectedSeen shouldBe true
        session?.provenance shouldBe adopted
    }

    @Test
    fun `session work id is written at creation and cleared with the record`() = runTest {
        store.startSession(fixedLimit, startedAtMillis = 1_000L, workId = "wid-1")
        store.currentSession()?.workId shouldBe "wid-1"

        store.clearSession()
        store.currentSession() shouldBe null
    }

    @Test
    fun `session work id survives owner adoption`() = runTest {
        store.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            workId = "wid-1",
            provenance = WorkProvenance("old", 1, 1, 1_000L),
        )
        store.adoptSessionOwner(WorkProvenance("new", 2, 1, 1_000L))

        val session = store.currentSession()!!
        session.workId shouldBe "wid-1"
        session.provenance!!.token shouldBe "new"
    }

    @Test
    fun `adoption stamps a fresh owner timestamp, not the session start`() = runTest {
        store.startSession(
            fixedLimit,
            startedAtMillis = 1_000L,
            provenance = WorkProvenance("old", 1, 1, 1_000L),
        )
        // Adopt with a creation time distinct from the session start; it must round-trip exactly.
        val adopted = WorkProvenance(token = "new", pid = 2, bootCount = 1, createdAtMillis = 9_999L)
        store.adoptSessionOwner(adopted)

        store.currentSession()!!.provenance!!.createdAtMillis shouldBe 9_999L
    }

    @Test
    fun `recovery work id is written at creation and cleared with the target`() = runTest {
        store.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            workId = "wid-r",
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        store.pendingRecoveryWorkId() shouldBe "wid-r"

        store.clearPendingRecoveryTarget()
        store.pendingRecoveryWorkId() shouldBe null
    }

    /**
     * The property the whole session mechanism rests on: a restore that is owed must still be owed
     * after the process dies. Rebuilds the store from the same file — a fresh [FullChargeStore] over
     * a fresh DataStore, as a relaunched app would see it — rather than trusting the in-memory cache.
     */
    @Test
    fun `an owed session and recovery survive a process restart`() = runTest {
        store.startSession(
            ChargePolicy.Adaptive,
            startedAtMillis = 1_234L,
            workId = "wid-1",
            provenance = WorkProvenance("tok-a", 42, 7, 1_000L),
        )
        store.markConnected()
        store.setPendingRecoveryTarget(
            ChargePolicy.FixedLimit(90),
            workId = "wid-r",
            provenance = WorkProvenance("tok-r", 43, 7, 1_100L),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        store.setLastSeenBootCount(7)

        val restarted = newStoreOverSameFile()

        restarted.currentSession() shouldBe ChargeSessionRecord(
            restorePolicy = ChargePolicy.Adaptive,
            startedAtMillis = 1_234L,
            connectedSeen = true,
            provenance = WorkProvenance("tok-a", 42, 7, 1_000L),
            workId = "wid-1",
        )
        restarted.currentRecovery() shouldBe RecoveryRecord(
            target = ChargePolicy.FixedLimit(90),
            workId = "wid-r",
            provenance = WorkProvenance("tok-r", 43, 7, 1_100L),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        restarted.lastSeenBootCount() shouldBe 7
    }

    /**
     * Metadata is not worth losing a restore over. A record missing `pid` (or any other auxiliary
     * field) must still yield the policy Amply owes the user — dropping the record would leave the
     * battery charging unrestricted with nothing left to trigger the restore.
     */
    @Test
    fun `a record missing auxiliary metadata still yields its policy`() = runTest {
        appDataStore.store.edit {
            it[stringPreferencesKey("recovery.v2")] =
                """{"target":"fixed:80","workId":"wid-r","provenance":{"token":"old","bootCount":7}}"""
            it[stringPreferencesKey("session.v2")] = """{"restorePolicy":"adaptive"}"""
        }

        store.pendingRecoveryTarget() shouldBe ChargePolicy.FixedLimit(80)
        store.pendingRecoveryWorkId() shouldBe "wid-r"
        store.pendingRecoveryProvenance()?.pid shouldBe -1
        store.currentSession()?.restorePolicy shouldBe ChargePolicy.Adaptive
        store.currentSession()?.connectedSeen shouldBe false
    }

    /** An owner with no token identifies nobody, so it reads as un-owned rather than as a stranger. */
    @Test
    fun `a tokenless owner decodes to no provenance`() = runTest {
        appDataStore.store.edit {
            it[stringPreferencesKey("session.v2")] =
                """{"restorePolicy":"adaptive","provenance":{"pid":9,"bootCount":7}}"""
        }

        val session = store.currentSession()
        session?.restorePolicy shouldBe ChargePolicy.Adaptive
        session?.provenance shouldBe null
    }

    /** A stored record whose policy this build cannot read must not resurrect as a wrong policy. */
    @Test
    fun `a session record with an unreadable policy decodes to no session`() = runTest {
        store.startSession(ChargePolicy.Adaptive, startedAtMillis = 1L)
        appDataStore.store.edit {
            it[stringPreferencesKey("session.v2")] =
                """{"restorePolicy":"fixed:not-a-number","startedAtMillis":1,"connectedSeen":false}"""
        }

        store.currentSession() shouldBe null
    }

    @Test
    fun `recovery work id survives owner adoption`() = runTest {
        store.setPendingRecoveryTarget(
            ChargePolicy.Unrestricted,
            workId = "wid-r",
            provenance = WorkProvenance("old", 1, 1, 5L),
            origin = RecoveryOrigin.SESSION_RESTORE,
        )
        store.adoptRecoveryOwner(WorkProvenance("new", 2, 1, 9L))

        store.pendingRecoveryWorkId() shouldBe "wid-r"
        store.pendingRecoveryProvenance()!!.token shouldBe "new"
    }
}
