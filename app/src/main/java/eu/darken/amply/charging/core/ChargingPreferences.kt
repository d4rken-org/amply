package eu.darken.amply.charging.core

import androidx.datastore.preferences.core.stringPreferencesKey
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The stored form of Amply's policy bookkeeping.
 *
 * Policies are held as **raw [ChargePolicy.stableId] strings**, not as decoded `ChargePolicy`s,
 * because these four facts degrade *independently*: an unreadable `lastRequested` must still leave a
 * perfectly good `protective` baseline intact. Losing that baseline is not cosmetic — it is the
 * limit Amply restores the battery to, so a wholesale fallback would quietly downgrade a user's
 * Adaptive or 90 % choice to the 80 % default, and the next write would persist the downgrade.
 *
 * Which is why decoding goes through [decodePolicyState] field by field instead of
 * `decodeFromString`: a typed whole-record decode fails on the *first* bad field and takes the other
 * three with it, so `{"lastRequestedAt":"bad", "protective":"fixed:90"}` would lose a valid 90 %.
 */
@Serializable
internal data class PolicyState(
    @SerialName("lastRequested") val lastRequested: String? = null,
    @SerialName("lastRequestedAt") val lastRequestedAt: Long = 0L,
    @SerialName("protective") val protective: String? = null,
    @SerialName("lastPersistent") val lastPersistent: String? = null,
    /**
     * External-power state at the moment of the last request, recorded only by plug-latched adapters
     * (null elsewhere). True means the write could not take effect yet and stays pending-until-replug.
     */
    @SerialName("lastRequestedPlugged") val lastRequestedPlugged: Boolean? = null,
    /**
     * Wall clock of the last observed unpowered moment while a plug-latched request was unresolved.
     * A value after [lastRequestedAt] proves a plug transition happened since the write — the next
     * plug session latches the configured value, so the request is resolved. 0 = never observed.
     */
    @SerialName("unpluggedSeenAt") val unpluggedSeenAt: Long = 0L,
)

/**
 * Reads each field on its own terms. A field that is absent, the wrong JSON type, or an unreadable
 * policy id yields only *that* field's default; only unparseable JSON loses the whole record.
 */
internal fun decodePolicyState(raw: String?, json: Json): PolicyState {
    if (raw == null) return PolicyState()
    val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return PolicyState()
    return PolicyState(
        lastRequested = obj.stringOrNull("lastRequested"),
        lastRequestedAt = obj.longOrDefault("lastRequestedAt"),
        protective = obj.stringOrNull("protective"),
        lastPersistent = obj.stringOrNull("lastPersistent"),
        lastRequestedPlugged = obj.booleanOrNull("lastRequestedPlugged"),
        unpluggedSeenAt = obj.longOrDefault("unpluggedSeenAt"),
    )
}

private fun JsonObject.primitiveOrNull(name: String): JsonPrimitive? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.stringOrNull(name: String): String? = primitiveOrNull(name)?.takeIf { it.isString }?.content

private fun JsonObject.longOrDefault(name: String, default: Long = 0L): Long =
    primitiveOrNull(name)?.takeUnless { it.isString }?.content?.toLongOrNull() ?: default

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    primitiveOrNull(name)?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()

@Singleton
class ChargingPreferences @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    private val policyState = dataStore.createValue(
        key = stringPreferencesKey("policy.v2"),
        reader = { raw -> decodePolicyState(raw as? String, json) },
        writer = { state -> json.encodeToString(PolicyState.serializer(), state) },
    )

    /**
     * The build identity (see `charging/core/enforcement/BuildIdentity`) the user explicitly started
     * enforcement verification for, or null. Its own key rather than a [PolicyState] field: it is
     * written once by a deliberate user action and read on every adapter selection, so it must not
     * wake the policy record's collectors — and it degrades independently of the protective baseline.
     *
     * Scoped to a build because that is what the evidence is scoped to: a ROM update re-opens the
     * question, and the opt-in for the old build must not silently carry over to the new one.
     */
    private val verificationStarted = dataStore.createValue(
        key = stringPreferencesKey("enforcement.verification_started_for"),
        reader = { raw -> raw as? String },
        writer = { value -> value },
    )

    val verificationStartedFor: Flow<String?> = verificationStarted.flow

    suspend fun verificationStartedForNow(): String? = verificationStarted.value()

    /** Record the explicit "enable charge limiting anyway" opt-in for [buildIdentity]. */
    suspend fun startVerification(buildIdentity: String) {
        verificationStarted.value(buildIdentity)
    }

    // Each projection dedupes on its own: they all ride one record now, so without this a
    // lastRequestedAt-only write would re-emit every one of them.
    val lastRequested: Flow<ChargePolicy?> = policyState.flow
        .map { ChargePolicy.fromStableId(it.lastRequested) }
        .distinctUntilChanged()

    /** Wall-clock time of the last request; paired atomically with [lastRequested]. 0 = never requested. */
    val lastRequestedAt: Flow<Long> = policyState.flow
        .map { it.lastRequestedAt }
        .distinctUntilChanged()

    val protectivePolicy: Flow<ChargePolicy> = policyState.flow
        .map { it.protectivePolicy() }
        .distinctUntilChanged()

    /**
     * The last policy Amply successfully applied as a *persistent* configuration — including
     * [ChargePolicy.Unrestricted], unlike [protectivePolicy]. Temporary session overrides never
     * update this, so it answers "what did the user configure through Amply" without a session's
     * transient Unrestricted write polluting the answer. Null until Amply's first persistent write.
     */
    val lastPersistentPolicy: Flow<ChargePolicy?> = policyState.flow
        .map { ChargePolicy.fromStableId(it.lastPersistent) }
        .distinctUntilChanged()

    suspend fun recordRequested(
        policy: ChargePolicy,
        persistent: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        plugged: Boolean? = null,
    ) {
        policyState.update { current ->
            current.copy(
                lastRequested = policy.stableId,
                lastRequestedAt = nowMillis,
                lastPersistent = if (persistent) policy.stableId else current.lastPersistent,
                protective = if (persistent && policy != ChargePolicy.Unrestricted) {
                    policy.stableId
                } else {
                    current.protective
                },
                lastRequestedPlugged = plugged,
                // Each request starts unresolved; a watermark from a previous request must not
                // resolve this one (it can only postdate it if the wall clock moved backwards).
                unpluggedSeenAt = 0L,
            )
        }
    }

    /**
     * Record that the device was observed without external power while a plug-latched request was
     * unresolved. Monotonic and one-shot per request: once a watermark after the request exists,
     * further observations change nothing (and cause no store write).
     */
    suspend fun recordUnpluggedSeen(nowMillis: Long = System.currentTimeMillis()) {
        policyState.update { current ->
            if (current.unpluggedSeenAt >= nowMillis) current else current.copy(unpluggedSeenAt = nowMillis)
        }
    }

    suspend fun lastRequestedNow(): ChargePolicy? = ChargePolicy.fromStableId(policyState.value().lastRequested)

    suspend fun lastRequestedAtNow(): Long = policyState.value().lastRequestedAt

    /** Plug state at the last request (plug-latched adapters only); null = not recorded. */
    suspend fun lastRequestedPluggedNow(): Boolean? = policyState.value().lastRequestedPlugged

    suspend fun unpluggedSeenAtNow(): Long = policyState.value().unpluggedSeenAt

    suspend fun protectivePolicyNow(): ChargePolicy = policyState.value().protectivePolicy()

    suspend fun lastPersistentPolicyNow(): ChargePolicy? =
        ChargePolicy.fromStableId(policyState.value().lastPersistent)
}

/**
 * Unrestricted is never a protective baseline, and neither is an unreadable value — both fall back to
 * the 80 % limit rather than leaving the battery uncapped.
 */
private fun PolicyState.protectivePolicy(): ChargePolicy =
    ChargePolicy.fromStableId(protective)?.takeUnless { it == ChargePolicy.Unrestricted }
        ?: ChargePolicy.FixedLimit(80)
