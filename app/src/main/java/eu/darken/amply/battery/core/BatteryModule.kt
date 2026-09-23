package eu.darken.amply.battery.core

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

@Module
@InstallIn(SingletonComponent::class)
object BatteryModule {
    @Provides
    @BatteryUnitDispatcher
    fun batteryUnitDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

/**
 * The dispatcher [BatteryUnitCalibration] loads and persists the learned current unit on. Injected
 * rather than hardcoded purely so tests can substitute a deterministic scheduler; production is always
 * [Dispatchers.IO]. Qualified (and scoped to the battery feature) so this doesn't become an unqualified
 * app-wide `CoroutineDispatcher` binding.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BatteryUnitDispatcher
