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
    )
}

private fun JsonObject.primitiveOrNull(name: String): JsonPrimitive? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }

private fun JsonObject.stringOrNull(name: String): String? = primitiveOrNull(name)?.takeIf { it.isString }?.content

private fun JsonObject.longOrDefault(name: String, default: Long = 0L): Long =
    primitiveOrNull(name)?.takeUnless { it.isString }?.content?.toLongOrNull() ?: default

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
            )
        }
    }

    suspend fun lastRequestedNow(): ChargePolicy? = ChargePolicy.fromStableId(policyState.value().lastRequested)

    suspend fun lastRequestedAtNow(): Long = policyState.value().lastRequestedAt

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
