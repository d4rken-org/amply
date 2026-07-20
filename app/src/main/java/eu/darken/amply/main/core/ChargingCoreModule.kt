package eu.darken.amply.main.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.charging.core.SettleScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class ChargingCoreModule {
    @Binds
    abstract fun bindSettleScheduler(impl: WorkManagerSettleScheduler): SettleScheduler
}
