package eu.darken.amply.rules.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.amply.monitor.core.ChargeMonitorWatcher

@Module
@InstallIn(SingletonComponent::class)
abstract class RulesModule {

    @Binds
    @IntoSet
    abstract fun bindRulesWatcher(impl: RulesWatcher): ChargeMonitorWatcher

    @Binds
    abstract fun bindRuleChargeGateway(impl: ChargingRuleGateway): RuleChargeGateway

    @Binds
    abstract fun bindBluetoothConnectionSource(impl: AndroidBluetoothConnectionSource): BluetoothConnectionSource
}
