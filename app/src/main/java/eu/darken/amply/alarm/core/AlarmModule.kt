package eu.darken.amply.alarm.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.amply.monitor.core.ChargeMonitorWatcher

/**
 * Contributes [ChargeAlarmWatcher] into the shared [Set] of [ChargeMonitorWatcher]s the
 * charge-session service fans battery ticks out to. Each future watcher binds itself the same way
 * beside its own implementation — the service never edits a central list.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AlarmModule {
    @Binds
    @IntoSet
    abstract fun bindChargeAlarmWatcher(impl: ChargeAlarmWatcher): ChargeMonitorWatcher

    @Binds
    abstract fun bindChargeAlarmNotifier(impl: AndroidChargeAlarmNotifier): ChargeAlarmNotifier
}
