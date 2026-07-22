package eu.darken.amply.stats.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.amply.monitor.core.ChargeMonitorWatcher

/**
 * Contributes [ChargeStatsWatcher] into the shared [Set] of [ChargeMonitorWatcher]s the
 * charge-session service fans battery ticks out to — the entire integration point with the service,
 * matching how the charge alarm binds itself.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StatsModule {
    @Binds
    @IntoSet
    abstract fun bindStatsWatcher(impl: ChargeStatsWatcher): ChargeMonitorWatcher
}
