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

    private fun openSession(startWall: Long = 1_000L) = ChargeSessionEntity(
        startedAtWallMillis = startWall,
        startedElapsedRealtimeMillis = startWall,
        bootId = 7,
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
