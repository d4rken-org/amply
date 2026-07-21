package eu.darken.amply.main.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.charging.core.SettleScheduler
import eu.darken.amply.charging.core.access.SettingsSnapshotSource
import eu.darken.amply.charging.core.access.ShizukuSettingsBackend
import eu.darken.amply.diagnostics.core.ContributionRepository
import eu.darken.amply.diagnostics.core.DefaultContributionRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class ChargingCoreModule {
    @Binds
    abstract fun bindSettleScheduler(impl: WorkManagerSettleScheduler): SettleScheduler

    /** The contribution wizard depends on the read-only snapshot view, never the full backend that exposes write(). */
    @Binds
    abstract fun bindSettingsSnapshotSource(impl: ShizukuSettingsBackend): SettingsSnapshotSource

    @Binds
    abstract fun bindContributionRepository(impl: DefaultContributionRepository): ContributionRepository
}
