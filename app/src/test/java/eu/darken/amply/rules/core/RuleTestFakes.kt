package eu.darken.amply.rules.core

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import eu.darken.amply.charging.core.BackendKind
import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingPreferences
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.serialization.SerializationModule
import eu.darken.amply.fullcharge.core.BootCountProvider
import eu.darken.amply.upgrade.core.UpgradeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.Instant

/**
 * Shared fakes for the rules layer. The stores are real (a temp-file DataStore), because their
 * tolerant decoding is part of what is under test; only the outward-facing collaborators — the
 * charging write path, Bluetooth, billing, the boot counter — are stand-ins.
 */
internal fun testDataStore(scope: CoroutineScope, dir: File) = AppDataStore(
    PreferenceDataStoreFactory.create(scope = scope) {
        File(dir, "rules-${System.nanoTime()}.preferences_pb")
    },
)

internal fun testPreferences(store: AppDataStore) = ChargingPreferences(store, SerializationModule.json())

internal fun testRulesStore(store: AppDataStore) = ChargeRulesStore(store, SerializationModule.json())

/**
 * Records every write in order and lets a test observe the persisted runtime *at the moment of the
 * write* — which is how the write-ahead ordering is asserted rather than assumed.
 */
internal class FakeChargeGateway(
    var configured: ChargeObservation? = null,
    var writeSucceeds: Boolean = true,
    var chargerTypeSupport: Boolean = true,
    var defaultProtective: ChargePolicy = ChargePolicy.FixedLimit(80),
    var supportedIds: Set<String> = emptySet(),
    /** Set to model the repository's own journal write, which every real write performs. */
    var journal: ChargingPreferences? = null,
) : RuleChargeGateway {

    val writes = mutableListOf<ChargePolicy>()
    var onWrite: (suspend (ChargePolicy) -> Unit)? = null

    override suspend fun readConfigured(): ChargeObservation? = configured

    override suspend fun applyTemporary(policy: ChargePolicy): Boolean {
        writes += policy
        onWrite?.invoke(policy)
        // The real repository records every successful write to the shared journal, under
        // NonCancellable, before returning — the rules layer's lastWriteAt stamp copies from it.
        if (writeSucceeds) journal?.recordRequested(policy, persistent = false)
        // Model a sync-readback adapter: what was successfully written becomes what reads back, so a
        // test only sees "external divergence" when it deliberately sets [configured] behind our
        // back. A fake with no readback at all (null) stays that way — that is the async-hardware case.
        if (writeSucceeds && configured != null) {
            configured = ChargeObservation.Verified(policy, BackendKind.SHIZUKU)
        }
        return writeSucceeds
    }

    override fun chargerTypeSupported(): Boolean = chargerTypeSupport

    override fun defaultProtectivePolicy(): ChargePolicy = defaultProtective

    override fun supportedPolicyIds(): Set<String> = supportedIds
}

internal class FakeBluetoothSource(
    var permission: Boolean = true,
    var live: Set<String>? = null,
    var bonded: List<BondedDevice> = emptyList(),
) : BluetoothConnectionSource {
    override fun hasPermission(): Boolean = permission
    override suspend fun connectedAddresses(): Set<String>? = live
    override fun bondedDevices(): List<BondedDevice> = bonded
}

/**
 * Backed by a [MutableStateFlow] rather than a finite flow on purpose: `isProSettled` waits for a
 * Pro emission, and a flow that *completes* without one makes that wait throw — which the gate
 * catches and fails open on, silently turning a non-Pro fake into a Pro one.
 */
internal class FakeUpgradeRepo(pro: Boolean = true) : UpgradeRepo {
    override val storeSite = ""
    override val upgradeSite = ""
    override val betaSite = ""

    private val infoFlow = MutableStateFlow<UpgradeRepo.Info>(FakeInfo(pro))

    var pro: Boolean
        get() = infoFlow.value.isPro
        set(value) {
            infoFlow.value = FakeInfo(value)
        }

    override val upgradeInfo: Flow<UpgradeRepo.Info> = infoFlow

    override suspend fun refresh() = Unit

    private data class FakeInfo(override val isPro: Boolean) : UpgradeRepo.Info {
        override val type = UpgradeRepo.Type.FOSS
        override val isSettled = true
        override val upgradedAt: Instant? = null
        override val error: Throwable? = null
    }
}

internal fun bootCount(value: Int?) = BootCountProvider { value }
