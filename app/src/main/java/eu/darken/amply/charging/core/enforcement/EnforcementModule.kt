package eu.darken.amply.charging.core.enforcement

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
 * Contributes [EnforcementWatcher] into the shared [Set] of [ChargeMonitorWatcher]s the charge-session
 * service fans battery ticks out to — the entire integration point with the service, matching how the
 * charge alarm and the statistics recorder bind themselves.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EnforcementModule {
    @Binds
    @IntoSet
    abstract fun bindEnforcementWatcher(impl: EnforcementWatcher): ChargeMonitorWatcher

    companion object {
        @Provides
        @EnforcementDispatcher
        fun enforcementDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}

/**
 * The dispatcher [EnforcementRecorder] runs its serialized tick loop on. Injected rather than
 * hardcoded purely so tests can substitute a deterministic scheduler; production is always
 * [Dispatchers.IO]. Qualified so this doesn't become an unqualified app-wide `CoroutineDispatcher`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class EnforcementDispatcher
