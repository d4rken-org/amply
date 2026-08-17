package eu.darken.amply.fullcharge.core

import eu.darken.amply.charging.core.ChargePolicy
import eu.darken.amply.common.AppDataStore
import eu.darken.amply.common.datastore.createValue
import eu.darken.amply.common.datastore.value
import eu.darken.amply.common.serialization.ChargePolicySerializer
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provenance of persisted work: which process created it and during which boot. Used to spot work
 * that survived a process death ([token] mismatch) within the same boot ([bootCount] match).
 *
 * **Every field carries a default.** Provenance is diagnostic metadata hanging off a record whose
 * real payload is a charge policy Amply still owes the user. Making any of it required would let a
 * missing `pid` take the whole record down with it — and a lost record means the restore never
 * happens and the battery keeps charging unrestricted. A [token]-less record is treated as un-owned
 * (see [normalizedProvenance]), which is exactly what a missing owner meant before.
 */
@Serializable
data class WorkProvenance(
    @SerialName("token") val token: String = "",
    @SerialName("pid") val pid: Int = -1,
    @SerialName("bootCount") val bootCount: Int? = null,
    @SerialName("createdAtMillis") val createdAtMillis: Long = 0L,
)

@Serializable
data class ChargeSessionRecord(
    // The one genuinely fatal field: a policy this build cannot read must not be guessed at, so an
    // unreadable one collapses the record to "no session" — the pre-refactor behaviour.
    @SerialName("restorePolicy")
    @Serializable(with = ChargePolicySerializer::class)
    val restorePolicy: ChargePolicy,
    @SerialName("startedAtMillis") val startedAtMillis: Long = 0L,
    @SerialName("connectedSeen") val connectedSeen: Boolean = false,
    @SerialName("provenance") val provenance: WorkProvenance? = null,
    /**
     * Stable correlation id for the owed work, generated once at creation and preserved across
     * process-adoption (unlike [WorkProvenance.token], which is re-stamped to the current owner). An
     * interruption event ties back to this so a later restore can resolve it.
     */
    @SerialName("workId") val workId: String? = null,
    /**
     * Wall clock of the first disconnect observed inside a plug-latched adapter's replug grace
     * window; null = not in grace. Persisted so the window survives process death (the expiry
     * decision compares wall clocks, not elapsedRealtime).
     */
    @SerialName("disconnectedAtMillis") val disconnectedAtMillis: Long? = null,
    /**
     * Plug-latched adapters: the session override was written while external power was present, so
     * the charging hardware has not picked it up and won't until an unplug→replug. Cleared by the
     * replug ([FullChargeStore.markReplugged]); drives the "unplug and replug" session hint.
     */
    @SerialName("overrideAwaitingReplug") val overrideAwaitingReplug: Boolean = false,
)

/**
 * Why a recovery target is owed, which decides how it may be written back later.
 *
 * The distinction is a safety boundary, not bookkeeping: a [SESSION_RESTORE] repays a protective
 * policy the user ALREADY had and therefore takes the ungated
 * `ChargingRepository.restorePersistent()`, while a [USER_REQUEST] is a fresh choice — possibly
 * `Unrestricted` — and must go through the gated `reapplyPersistent()`. Between the write being
 * persisted and it landing, the build can become a candidate (an OTA changes the composite build
 * identity) or be refuted, and a fresh user write must not slip past the enforcement gate just
 * because a process death turned it into recovery work.
 */
@Serializable
enum class RecoveryOrigin {
    /** Repaying the protective policy of a session or an earlier boot recovery: ungated. */
    @SerialName("SESSION_RESTORE")
    SESSION_RESTORE,

    /** A policy the user explicitly asked for (widget/tile persistent choice): gated. */
    @SerialName("USER_REQUEST")
    USER_REQUEST,
}

/**
 * The restore Amply still owes after a boot or an interrupted session, held as one record so its
 * target, correlation id and provenance can never be read as a mismatched set.
 *
 * [origin] defaults to the **safe** [RecoveryOrigin.USER_REQUEST]: a record written by a build that
 * did not know the field decodes to the gated path, so an older record can never bypass the
 * enforcement gate. The cost of the wrong default in the other direction is a refused restore, which
 * the boot recovery notification surfaces; the cost here would be a silent ungated write.
 */
@Serializable
data class RecoveryRecord(
    @SerialName("target")
    @Serializable(with = ChargePolicySerializer::class)
    val target: ChargePolicy,
    @SerialName("workId") val workId: String? = null,
    @SerialName("provenance") val provenance: WorkProvenance? = null,
    @SerialName("origin") val origin: RecoveryOrigin = RecoveryOrigin.USER_REQUEST,
)

/**
 * Persistence for the temporary full-charge session and the restore it owes.
 *
 * The session and the recovery target are each **one** record under one key, so a half-written
 * restore cannot exist. An unreadable record — including one carrying an unknown policy id — decodes
 * to null ("no session"), exactly as the previous multi-key decode did when its policy failed to parse.
 */
@Singleton
class FullChargeStore @Inject constructor(
    dataStore: AppDataStore,
    json: Json,
) {
    private val sessionValue = dataStore.createValue<ChargeSessionRecord?>(
        key = "session.v2",
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )

    private val recoveryValue = dataStore.createValue<RecoveryRecord?>(
        key = "recovery.v2",
        defaultValue = null,
        json = json,
        fallbackToDefault = true,
    )

    private val lastSeenBootCountValue = dataStore.createValue<Int?>("recovery.last_seen_boot_count.v2")

    val session = sessionValue.flow.map { it?.normalized() }
    val quickFullChargeEnabled = dataStore.createValue("fullcharge.quick_replug_enabled.v2", false)
    val quickFullChargeAnyLevel = dataStore.createValue("fullcharge.quick_replug_any_level.v2", false)

    suspend fun currentSession(): ChargeSessionRecord? = sessionValue.value()?.normalized()

    suspend fun startSession(
        restorePolicy: ChargePolicy,
        startedAtMillis: Long,
        workId: String? = null,
        provenance: WorkProvenance? = null,
        overrideAwaitingReplug: Boolean = false,
    ) {
        sessionValue.update {
            ChargeSessionRecord(
                restorePolicy = restorePolicy,
                startedAtMillis = startedAtMillis,
                connectedSeen = false,
                provenance = provenance,
                workId = workId,
                overrideAwaitingReplug = overrideAwaitingReplug,
            )
        }
    }

    suspend fun markConnected() {
        sessionValue.update { it?.copy(connectedSeen = true) }
    }

    /** Open the replug grace window: record the first disconnect a plug-latched session observed. */
    suspend fun markDisconnected(nowMillis: Long) {
        sessionValue.update { it?.copy(disconnectedAtMillis = nowMillis) }
    }

    /**
     * A replug inside the grace window: the plug transition latched the session override, so both
     * the window and the awaiting-replug hint end in one atomic edit.
     */
    suspend fun markReplugged() {
        sessionValue.update { it?.copy(disconnectedAtMillis = null, overrideAwaitingReplug = false) }
    }

    /** Post-write reconciliation of the awaiting-replug hint (see ChargeSessionManager.begin). */
    suspend fun setOverrideAwaitingReplug(awaiting: Boolean) {
        sessionValue.update { it?.copy(overrideAwaitingReplug = awaiting) }
    }

    /** Adopt the current process as the session's owner and flag CONNECTED in a single atomic edit. */
    suspend fun markConnectedAndAdopt(provenance: WorkProvenance) {
        sessionValue.update { it?.copy(connectedSeen = true, provenance = provenance) }
    }

    /** Re-stamp the active session's provenance to the current process, if a session is present. */
    suspend fun adoptSessionOwner(provenance: WorkProvenance) {
        sessionValue.update { it?.copy(provenance = provenance) }
    }

    suspend fun clearSession() {
        sessionValue.update { null }
    }

    /**
     * The whole owed restore in one read. Callers needing more than one of its fields must use this
     * rather than the single-field accessors below: reading target, work id and provenance
     * separately can pair fields from either side of a concurrent adopt or clear.
     */
    suspend fun currentRecovery(): RecoveryRecord? = recoveryValue.value()?.normalized()

    suspend fun pendingRecoveryTarget(): ChargePolicy? = currentRecovery()?.target

    suspend fun pendingRecoveryProvenance(): WorkProvenance? = currentRecovery()?.provenance

    suspend fun pendingRecoveryWorkId(): String? = currentRecovery()?.workId

    /**
     * [origin] has no default: it decides whether the target may later be written through the ungated
     * restore path, so every caller must state which kind of work it is persisting.
     */
    suspend fun setPendingRecoveryTarget(
        policy: ChargePolicy,
        workId: String? = null,
        provenance: WorkProvenance? = null,
        origin: RecoveryOrigin,
    ) {
        recoveryValue.update {
            RecoveryRecord(target = policy, workId = workId, provenance = provenance, origin = origin)
        }
    }

    /** Re-stamp the pending recovery target's provenance to the current process, if one is present. */
    suspend fun adoptRecoveryOwner(provenance: WorkProvenance) {
        recoveryValue.update { it?.copy(provenance = provenance) }
    }

    suspend fun clearPendingRecoveryTarget() {
        recoveryValue.update { null }
    }

    /** The boot count during which Amply last ran — used to spot re-delivered BOOT_COMPLETED broadcasts. */
    suspend fun lastSeenBootCount(): Int? = lastSeenBootCountValue.value()

    suspend fun setLastSeenBootCount(count: Int) {
        lastSeenBootCountValue.value(count)
    }

    suspend fun isQuickFullChargeEnabled(): Boolean = quickFullChargeEnabled.value()

    suspend fun setQuickFullChargeEnabled(enabled: Boolean) {
        quickFullChargeEnabled.value(enabled)
    }

    suspend fun isQuickFullChargeAnyLevel(): Boolean = quickFullChargeAnyLevel.value()

    suspend fun setQuickFullChargeAnyLevel(enabled: Boolean) {
        quickFullChargeAnyLevel.value(enabled)
    }
}

/**
 * An owner without a token identifies nobody, so it reads as no owner at all — the same answer a
 * record with no provenance gave before, which the assessor treats as un-owned and adopts silently.
 */
private fun WorkProvenance?.normalizedProvenance(): WorkProvenance? = this?.takeIf { it.token.isNotBlank() }

private fun ChargeSessionRecord.normalized() = copy(provenance = provenance.normalizedProvenance())

private fun RecoveryRecord.normalized() = copy(provenance = provenance.normalizedProvenance())
