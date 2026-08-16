package eu.darken.amply.rules.core

import eu.darken.amply.charging.core.ChargePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The charger classes Android reports through `BatteryManager.EXTRA_PLUGGED`. Modelled as an enum
 * rather than the raw bitmask so a stored rule keeps meaning if the platform adds a class.
 */
@Serializable
enum class PlugKind {
    @SerialName("AC") AC,

    @SerialName("USB") USB,

    @SerialName("WIRELESS") WIRELESS,

    @SerialName("DOCK") DOCK,
}

/**
 * What makes a rule apply. Sealed and polymorphic so the stored JSON carries an explicit subtype
 * discriminator — a condition kind added later must not make an existing record ambiguous.
 */
@Serializable
sealed interface RuleCondition {

    /**
     * Matches while a bonded Bluetooth device is connected, **regardless of plug state**: "when the
     * car is connected" is a context, not a charging event. [address] is normalized uppercase (see
     * [normalizeBtAddress]); [name] is a display convenience only and never participates in matching.
     */
    @Serializable
    @SerialName("bluetooth")
    data class BluetoothDevice(
        @SerialName("address") val address: String,
        @SerialName("name") val name: String? = null,
    ) : RuleCondition

    /**
     * Matches only while plugged into one of [types]. An empty set matches nothing — the editor
     * enforces at least one selection, and a hand-written empty record must not become a wildcard.
     */
    @Serializable
    @SerialName("charger")
    data class ChargerType(
        @SerialName("types") val types: Set<PlugKind> = emptySet(),
    ) : RuleCondition
}

/**
 * One conditional charge rule. [policyId] is the raw [ChargePolicy.stableId] rather than a decoded
 * policy: a rule written on a device whose adapter offers a policy this build cannot read must not
 * take the whole rule set down with it — it decodes to null and is skipped (see [ChargeRule.policy]).
 */
@Serializable
data class ChargeRule(
    @SerialName("id") val id: String,
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("label") val label: String = "",
    @SerialName("condition") val condition: RuleCondition,
    @SerialName("policyId") val policyId: String,
)

@Serializable
data class ChargeRuleSet(
    @SerialName("rules") val rules: List<ChargeRule> = emptyList(),
)

/**
 * A rule's kind is **derived**, never stored: a rule that writes a policy reaching 100% is a charge
 * rule, anything else is a protection rule. Storing it would let the label and the policy disagree.
 */
enum class RuleKind {
    PROTECTION,
    CHARGE,
}

val ChargeRule.policy: ChargePolicy?
    get() = ChargePolicy.fromStableId(policyId)

/** Null when [policyId] cannot be decoded by this build — such a rule never matches. */
val ChargeRule.kind: RuleKind?
    get() = policy?.let { if (it.allowsFullCharge) RuleKind.CHARGE else RuleKind.PROTECTION }

/** Bluetooth addresses are compared case-insensitively; store and match on one canonical form. */
fun normalizeBtAddress(address: String): String = address.trim().uppercase()

/** Where the rules layer is in its crash-safe write-ahead transition. */
@Serializable
enum class RulePhase {
    /** No rule owns the charging policy. */
    @SerialName("IDLE") IDLE,

    /** An activation/switch write was persisted but has not been confirmed successful. */
    @SerialName("APPLY_PENDING") APPLY_PENDING,

    /** A rule owns the charging policy; [RuleRuntimeState.baselinePolicyId] is owed back. */
    @SerialName("ACTIVE") ACTIVE,

    /** A restore write was persisted but has not been confirmed successful. */
    @SerialName("RESTORE_PENDING") RESTORE_PENDING,
}

/**
 * The rules layer's durable runtime bookkeeping.
 *
 * Policies are held as raw stable-id strings for the same reason `ChargingPreferences` does it:
 * these fields degrade *independently*, and the one that matters most — [baselinePolicyId], the
 * user's own policy the rules layer owes back — must survive a neighbouring field being unreadable.
 * Decoding therefore goes through [decodeRuleRuntimeState] field by field.
 */
@Serializable
data class RuleRuntimeState(
    @SerialName("phase") val phase: RulePhase = RulePhase.IDLE,
    /** The policy the in-flight write is aiming at (activation target, or the restore baseline). */
    @SerialName("targetPolicyId") val targetPolicyId: String? = null,
    @SerialName("activeRuleId") val activeRuleId: String? = null,
    /** The user's policy from before the first activation, owed back on deactivation. */
    @SerialName("baselinePolicyId") val baselinePolicyId: String? = null,
    /**
     * Rules that were matching when the user (or something external) overrode the rules layer. No
     * activation happens while any of them still matches; the cohort clears when none of them do.
     */
    @SerialName("suspendedRuleIds") val suspendedRuleIds: Set<String> = emptySet(),
    /** The last rule write failed; the ~30s monitor tick retries and the UI shows a warning. */
    @SerialName("lastApplyFailed") val lastApplyFailed: Boolean = false,
) {
    val targetPolicy: ChargePolicy?
        get() = ChargePolicy.fromStableId(targetPolicyId)

    val baselinePolicy: ChargePolicy?
        get() = ChargePolicy.fromStableId(baselinePolicyId)

    val isPending: Boolean
        get() = phase == RulePhase.APPLY_PENDING || phase == RulePhase.RESTORE_PENDING
}

/**
 * The connected-Bluetooth set as the manifest receiver last saw it.
 *
 * [bootCount] scopes the snapshot to one boot: connections do not survive a reboot, and a stale set
 * would keep a rule active for a device that is no longer there. A mismatch reads as empty.
 */
@Serializable
data class BtConnectionSnapshot(
    @SerialName("addresses") val addresses: Set<String> = emptySet(),
    @SerialName("bootCount") val bootCount: Int? = null,
)
