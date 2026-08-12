package eu.darken.amply.upgrade

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.amply.upgrade.core.UpgradeRepo
import eu.darken.amply.upgrade.core.UpgradeRepoFoss
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
abstract class UpgradeModule {
    @Binds
    @Singleton
    abstract fun repo(foss: UpgradeRepoFoss): UpgradeRepo
}
