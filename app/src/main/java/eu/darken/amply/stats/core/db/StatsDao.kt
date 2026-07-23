package eu.darken.amply.stats.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {

    // --- charge_sessions ---

    @Insert
    suspend fun insertSession(session: ChargeSessionEntity): Long

    @Update
    suspend fun updateSession(session: ChargeSessionEntity)

    /**
     * Open sessions, newest first. Normally at most one exists; the recorder reconciles extras
     * (sealing all but the newest) rather than assuming a single row, so a crash that left two open
     * can never wedge capture.
     */
    @Query("SELECT * FROM charge_sessions WHERE endedAtWallMillis IS NULL ORDER BY startedElapsedRealtimeMillis DESC")
    suspend fun openSessions(): List<ChargeSessionEntity>

    @Query("SELECT * FROM charge_sessions WHERE id = :id")
    suspend fun sessionById(id: Long): ChargeSessionEntity?

    /** Finished sessions for the history list, newest first. */
    @Query("SELECT * FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL ORDER BY startedAtWallMillis DESC LIMIT :limit")
    fun finishedSessions(limit: Int): Flow<List<ChargeSessionEntity>>

    /** Count of finished sessions, for the dashboard teaser. */
    @Query("SELECT COUNT(*) FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL")
    fun finishedSessionCount(): Flow<Int>

    @Query("SELECT * FROM charge_sessions WHERE id = :id")
    fun sessionFlow(id: Long): Flow<ChargeSessionEntity?>

    @Query("DELETE FROM charge_sessions")
    suspend fun deleteAllSessions()

    // --- battery_samples ---

    @Insert
    suspend fun insertSample(sample: BatterySampleEntity): Long

    @Query("SELECT * FROM battery_samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMillis ASC")
    fun samplesForSession(sessionId: Long): Flow<List<BatterySampleEntity>>

    /** Raw samples for a session as a plain list (curve rendering decimates in memory). */
    @Query("SELECT * FROM battery_samples WHERE sessionId = :sessionId ORDER BY elapsedRealtimeMillis ASC")
    suspend fun samplesForSessionNow(sessionId: Long): List<BatterySampleEntity>

    /** Retention: drop raw samples older than a cutoff whose session is already closed. */
    @Query(
        """
        DELETE FROM battery_samples
        WHERE wallMillis < :cutoffWallMillis
        AND sessionId IN (SELECT id FROM charge_sessions WHERE endedAtWallMillis IS NOT NULL)
        """,
    )
    suspend fun deleteSamplesOlderThan(cutoffWallMillis: Long): Int

    @Query("DELETE FROM battery_samples")
    suspend fun deleteAllSamples()
}
