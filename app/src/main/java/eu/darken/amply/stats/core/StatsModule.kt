package eu.darken.amply.stats.core

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.amply.monitor.core.ChargeMonitorWatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

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

    companion object {
        @Provides
        @StatsDispatcher
        fun statsDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}

/**
 * The dispatcher [ChargeStatsRecorder] runs its serialized command loop on. Injected rather than
 * hardcoded purely so tests can substitute a deterministic scheduler; production is always
 * [Dispatchers.IO]. Qualified (and scoped to the stats feature) so this doesn't become an
 * unqualified app-wide `CoroutineDispatcher` binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StatsDispatcher
