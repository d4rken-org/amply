package eu.darken.amply.charging.core

import eu.darken.amply.R
import eu.darken.amply.common.ca.CaString
import eu.darken.amply.common.ca.toCaString

sealed interface ChargePolicy {
    val stableId: String
    val label: CaString

    /** Whether the battery reaches 100% under this policy (no cap below full). */
    val allowsFullCharge: Boolean
        get() = this == Unrestricted || this == PauseAtFull || (this is FixedLimit && percent >= 100)

    /**
     * Whether the OEM decides *when* this policy acts, rather than acting whenever it is configured.
     * A conditional policy that is configured and read back proves the mode is selected — never that
     * the battery is being protected right now. Every OEM "adaptive"/"smart"/"intelligent" mode
     * engages only inside a learned window: a HyperOS overnight window on Xiaomi (a 13T with
     * Intelligent charging configured and verified charged 59%→100% with no hold, 2026-08-16), and
     * shortly before the usual unplug on Pixel/ColorOS.
     *
     * Consumed by [ChargeObservation.provesPolicyInEffect] for presentation only.
     */
    val enforcementIsConditional: Boolean
        get() = when (this) {
            Adaptive -> true
            Unrestricted, PauseAtFull -> false
            is FixedLimit -> false
        }

    data object Unrestricted : ChargePolicy {
        override val stableId = "unrestricted"
        override val label = R.string.charging_policy_unrestricted_label.toCaString()
    }

    data object Adaptive : ChargePolicy {
        override val stableId = "adaptive"
        override val label = R.string.charging_policy_adaptive_label.toCaString()
    }

    /** Charges to 100%, then the OEM pauses charging and tops up in a hysteresis band (e.g. Samsung "Standard"). */
    data object PauseAtFull : ChargePolicy {
        override val stableId = "pause_at_full"
        override val label = R.string.charging_policy_pause_at_full_label.toCaString()
    }

    data class FixedLimit(val percent: Int) : ChargePolicy {
        init {
            require(percent in 50..100) { "Charge limit must be between 50 and 100" }
        }

        override val stableId = "fixed:$percent"
        override val label = R.string.charging_policy_fixed_label.toCaString(percent)
    }

    companion object {
        fun fromStableId(value: String?): ChargePolicy? = when {
            value == Unrestricted.stableId -> Unrestricted
            value == Adaptive.stableId -> Adaptive
            value == PauseAtFull.stableId -> PauseAtFull
            value?.startsWith("fixed:") == true -> value.substringAfter(':').toIntOrNull()?.let {
                runCatching { FixedLimit(it) }.getOrNull()
            }
            else -> null
        }
    }
}

sealed interface ChargeObservation {
    data class Verified(val policy: ChargePolicy, val backend: BackendKind) : ChargeObservation
    data class LastRequested(val policy: ChargePolicy) : ChargeObservation

    /** [unrecognizedValue] marks a successfully read but unmapped OEM value (vs. unreadable state). */
    data class Unknown(val reason: CaString, val unrecognizedValue: Boolean = false) : ChargeObservation
    data class NeedsSetup(val reason: CaString) : ChargeObservation
    data class Unsupported(val reason: CaString) : ChargeObservation
}

enum class BackendKind {
    DIRECT_WSS,
    SHIZUKU,
    BATTERY_HARDWARE,
    DEEP_LINK,
}

data class ApplyResult(
    val success: Boolean,
    val observation: ChargeObservation,
    val message: String,
)

/**
 * What became of a qualification run's baseline restore — see
 * `ChargingRepository.restoreQualificationBaselineIfOwned`.
 *
 * Deliberately **not** an [ApplyResult] with a success flag: "no write was made because this run no
 * longer owes the restore" and "a write was made and failed" must not collapse into one answer. The
 * first is the run finishing correctly and leaving a newer choice alone; the second leaves a policy
 * the user is still owed, and the recovery target has to stay behind for it.
 */
internal sealed interface QualificationRestoreOutcome {
    data class Applied(val result: ApplyResult) : QualificationRestoreOutcome
    data object Superseded : QualificationRestoreOutcome
}
