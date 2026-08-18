package eu.darken.amply.charging.core.qualification

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
 * Contributes [QualificationWatcher] into the shared [Set] of [ChargeMonitorWatcher]s — the entire
 * integration point with the charge-session service, the same one the enforcement recorder, the
 * charge alarm and the statistics recorder use.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QualificationModule {
    @Binds
    @IntoSet
    abstract fun bindQualificationWatcher(impl: QualificationWatcher): ChargeMonitorWatcher

    companion object {
        @Provides
        @QualificationDispatcher
        fun qualificationDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}

/**
 * The dispatcher [QualificationRunner] runs its serialized tick loop on. Injected rather than
 * hardcoded so tests can substitute a deterministic scheduler; production is always
 * [Dispatchers.IO].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class QualificationDispatcher
