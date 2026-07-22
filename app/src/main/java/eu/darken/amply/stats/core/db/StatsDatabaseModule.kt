package eu.darken.amply.stats.core.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the statistics [StatsDatabase]. Consumers inject it via `dagger.Lazy` so `build()` (and
 * therefore any on-disk open) is deferred until stats capture is actually used — a corrupt/locked
 * stats DB must never prevent the safety-critical charge-session service or the boot receiver from
 * being constructed. No destructive-migration fallback: history is long-lived and must migrate.
 */
@Module
@InstallIn(SingletonComponent::class)
object StatsDatabaseModule {

    @Provides
    @Singleton
    fun provideStatsDatabase(@ApplicationContext context: Context): StatsDatabase =
        Room.databaseBuilder(context, StatsDatabase::class.java, StatsDatabase.NAME).build()

    @Provides
    fun provideStatsDao(database: StatsDatabase): StatsDao = database.statsDao()
}
