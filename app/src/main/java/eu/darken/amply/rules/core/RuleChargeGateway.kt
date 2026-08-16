package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.ChargeObservation
import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.charging.core.ChargingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The narrow slice of charging control the rules layer needs.
 *
 * It exists so `RuleApplier`'s one safety-critical property — the runtime intent is persisted
 * **before** the policy write, never after — can be asserted on the JVM against a recording fake,
 * instead of only being visible by reading the code.
 */
interface RuleChargeGateway {

    /** Fresh configured readback, or null when this adapter cannot produce one (async-hardware). */
    suspend fun readConfigured(): ChargeObservation?

    /**
     * Rule writes are always **temporary**: they must not touch `lastPersistentPolicy`, which is the
     * user's own choice and the fallback baseline the rules layer restores to.
     */
    suspend fun applyTemporary(policy: ChargePolicy): Boolean

    /** False on plug-latched adapters, where a charger-type condition could never take effect. */
    fun chargerTypeSupported(): Boolean

    /** Last-resort baseline when the current state is unreadable and Amply's journal is empty. */
    fun defaultProtectivePolicy(): ChargePolicy
}

@Singleton
class ChargingRuleGateway @Inject constructor(
    private val repository: ChargingRepository,
) : RuleChargeGateway {

    // Adapter selection is immutable device information, but resolving it is not cheap (DeviceInfo
    // queries activities, providers and UserManager). The session service caches it the same way.
    private val adapterFacts by lazy {
        val adapter = repository.currentAdapter()
        AdapterFacts(
            chargerTypeSupported = adapter?.policyLatchesAtPlug != true,
            defaultProtective = adapter?.defaultProtectivePolicy ?: ChargePolicy.FixedLimit(80),
        )
    }

    override suspend fun readConfigured(): ChargeObservation? = repository.syncReadback()

    override suspend fun applyTemporary(policy: ChargePolicy): Boolean =
        repository.applyTemporary(policy).success

    override fun chargerTypeSupported(): Boolean = adapterFacts.chargerTypeSupported

    override fun defaultProtectivePolicy(): ChargePolicy = adapterFacts.defaultProtective

    private data class AdapterFacts(
        val chargerTypeSupported: Boolean,
        val defaultProtective: ChargePolicy,
    )
}
