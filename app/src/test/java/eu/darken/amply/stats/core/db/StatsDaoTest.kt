package eu.darken.amply.stats.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO round-trip + FK/retention behavior on an in-memory Room instance. Robolectric (JUnit 4) per the
 * project's convention for anything needing the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsDaoTest {

    private lateinit var database: StatsDatabase
    private lateinit var dao: StatsDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, StatsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.statsDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun openSession(startWall: Long = 1_000L, bootId: Long = 7) = ChargeSessionEntity(
        startedAtWallMillis = startWall,
        startedElapsedRealtimeMillis = startWall,
        bootId = bootId,
        startPercent = 40,
    )

    @Test
    fun `open session is listed as open and excluded from finished`() = runTest {
        val id = dao.insertSession(openSession())
        dao.openSessions().map { it.id } shouldBe listOf(id)
        dao.finishedSessions(10).first() shouldBe emptyList()
    }

    @Test
    fun `sealed session appears in finished, not open`() = runTest {
        val id = dao.insertSession(openSession())
        val sealed = dao.sessionById(id)!!.copy(endedAtWallMillis = 5_000L, endedElapsedRealtimeMillis = 5_000L)
        dao.updateSession(sealed)
        dao.openSessions() shouldBe emptyList()
        dao.finishedSessions(10).first().map { it.id } shouldBe listOf(id)
    }

    @Test
    fun `deleting a session cascades to its samples`() = runTest {
        val id = dao.insertSession(openSession())
        dao.insertSample(sample(id, wall = 1_100L))
        dao.insertSample(sample(id, wall = 1_200L))
        dao.samplesForSessionNow(id).size shouldBe 2

        dao.deleteAllSessions()
        dao.samplesForSessionNow(id) shouldBe emptyList()
    }

    @Test
    fun `deleteSession removes only that session and cascades its samples`() = runTest {
        val keep = dao.insertSession(openSession(startWall = 1_000L))
        val drop = dao.insertSession(openSession(startWall = 2_000L))
        dao.insertSample(sample(keep, wall = 1_100L))
        dao.insertSample(sample(drop, wall = 2_100L))
        dao.insertSample(sample(drop, wall = 2_200L))

        dao.deleteSession(drop)

        dao.sessionById(drop) shouldBe null
        dao.sessionById(keep)!!.id shouldBe keep
        dao.samplesForSessionNow(drop) shouldBe emptyList()
        dao.samplesForSessionNow(keep).size shouldBe 1
    }

    @Test
    fun `openSessionFlow emits the open row then null once sealed`() = runTest {
        dao.openSessionFlow(bootId = 7).first() shouldBe null
        val id = dao.insertSession(openSession())
        dao.openSessionFlow(bootId = 7).first()!!.id shouldBe id
        dao.updateSession(dao.sessionById(id)!!.copy(endedAtWallMillis = 9_000L, endedElapsedRealtimeMillis = 9_000L))
        dao.openSessionFlow(bootId = 7).first() shouldBe null
    }

    @Test
    fun `openSessionFlow ignores a dangling open row from a previous boot`() = runTest {
        // A crash before a reboot can leave an open row with the old bootId; it must not surface as the
        // current charge until startup repair seals it.
        dao.insertSession(openSession(startWall = 10_000L, bootId = 6))
        val current = dao.insertSession(openSession(startWall = 1_000L, bootId = 7))
        dao.openSessionFlow(bootId = 7).first()!!.id shouldBe current
    }

    @Test
    fun `openSessionFlow picks the newest by id even when elapsed is smaller`() = runTest {
        // Same boot: a later session with a smaller elapsed start must still win on id order.
        val older = dao.insertSession(openSession(startWall = 10_000L))
        val newer = dao.insertSession(openSession(startWall = 5_000L))
        newer shouldBe (older + 1) // autoincrement is monotonic
        val picked = dao.openSessionFlow(bootId = 7).first()!!
        picked.id shouldBe newer
        picked.startedElapsedRealtimeMillis shouldBe 5_000L
    }

    @Test
    fun `recentSamplesForSession returns the last N ascending`() = runTest {
        val id = dao.insertSession(openSession())
        (1..5).forEach { dao.insertSample(sample(id, wall = it * 1_000L)) }
        dao.recentSamplesForSession(id, limit = 3).first().map { it.wallMillis } shouldBe
            listOf(3_000L, 4_000L, 5_000L)
    }

    @Test
    fun `retention only purges old samples of closed sessions`() = runTest {
        val closedId = dao.insertSession(openSession(startWall = 1_000L))
        dao.updateSession(dao.sessionById(closedId)!!.copy(endedAtWallMillis = 2_000L, endedElapsedRealtimeMillis = 2_000L))
        dao.insertSample(sample(closedId, wall = 1_000L)) // old
        dao.insertSample(sample(closedId, wall = 9_000L)) // recent

        val openId = dao.insertSession(openSession(startWall = 1_000L))
        dao.insertSample(sample(openId, wall = 1_000L)) // old but session still open

        val deleted = dao.deleteSamplesOlderThan(cutoffWallMillis = 5_000L)
        deleted shouldBe 1
        dao.samplesForSessionNow(closedId).map { it.wallMillis } shouldBe listOf(9_000L)
        // The open session's old sample is retained (its summary isn't finalized yet).
        dao.samplesForSessionNow(openId).map { it.wallMillis } shouldBe listOf(1_000L)
    }

    private fun sample(sessionId: Long, wall: Long) = BatterySampleEntity(
        sessionId = sessionId,
        wallMillis = wall,
        elapsedRealtimeMillis = wall,
        bootId = 7,
        percent = 50,
        powerMilliwatts = 10_000,
        temperatureTenthsC = 300,
    )
}
