package eu.darken.amply.charging.core.qualification

import eu.darken.amply.charging.core.ChargePolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The guided qualification run: an *active* experiment that proves whether a device's charging
 * hardware obeys a configured cap, as opposed to the passive observation in
 * `charging/core/enforcement/`, which can only ever refute one.
 *
 * The distinction is the whole point of this package. A cap hold and a thermal or weak-supply pause
 * are indistinguishable in the public battery broadcast (see `EnforcementVerdictEngine`'s KDoc), so
 * observing a plateau proves nothing. What no thermal pause can imitate is charging **resuming on
 * command** within minutes of a cap being raised, and then stopping again when it is lowered. That
 * cut → resume → cut sequence is what this protocol drives.
 */
enum class RunShape {
    /**
     * The adapter offers two or more distinct [ChargePolicy.FixedLimit] ticks (LineageOS 70..95,
     * Samsung One UI 8 80/85/90/95). The release step *raises the cap* rather than removing it, so
     * the device is never left uncapped and the run works at almost any battery level.
     */
    VARIABLE_CAP,

    /**
     * The adapter offers exactly one cap (Xiaomi HyperOS 3, Oplus, Samsung legacy). The release step
     * must write [ChargePolicy.Unrestricted], and the run only works with the battery near the cap,
     * so it may have to charge up first.
     */
    FIXED_CAP,
}

enum class RunPhase {
    /** Waiting for preconditions; nothing has been written yet. */
    PREFLIGHT,

    /** [RunShape.FIXED_CAP] only: charging up to just under the cap so a hold is observable. */
    CHARGE_UP,

    /** The cap is written; waiting for charging to stop. */
    CUT_1,

    /** The cap is raised (or removed); waiting for charging to resume. This is the discriminator. */
    RESUME,

    /** The cap is written again; waiting for charging to stop a second time. */
    CUT_2,
}

/** What the engine asks its host to do. The engine never writes anything itself. */
sealed interface RunCommand {
    /** Apply [policy] through `ChargingRepository.applyForQualification`. */
    data class Apply(val policy: ChargePolicy) : RunCommand
}

/** Why a run ended without a verdict either way. Nothing is persisted for these. */
@Serializable
enum class InconclusiveReason {
    @SerialName("NO_CUT")
    NO_CUT,

    @SerialName("NO_RESUME")
    NO_RESUME,

    @SerialName("NO_RECUT")
    NO_RECUT,

    /** Neither the charge counter nor the level could be trusted, so nothing was measurable. */
    @SerialName("NO_SIGNAL")
    NO_SIGNAL,

    /**
     * The device held, but not at the cap that was commanded. Only reachable on a candidate adapter,
     * where the OEM's value semantics are a guess — a One UI 6/7 device holding at 85 under a
     * commanded 80 means the mapping differs, not that the hardware failed.
     */
    @SerialName("CAP_MISMATCH")
    CAP_MISMATCH,

    @SerialName("CHARGE_UP_TIMEOUT")
    CHARGE_UP_TIMEOUT,

    @SerialName("PRECONDITION_TIMEOUT")
    PRECONDITION_TIMEOUT,
}

/** Why a run stopped before it could measure anything. */
@Serializable
enum class AbortReason {
    @SerialName("UNPLUGGED")
    UNPLUGGED,

    @SerialName("WRITE_FAILED")
    WRITE_FAILED,

    /** The configured state stopped matching what the run commanded: the user changed it natively. */
    @SerialName("CONFIGURATION_DRIFT")
    CONFIGURATION_DRIFT,

    @SerialName("SESSION_STARTED")
    SESSION_STARTED,

    @SerialName("USER_CANCELLED")
    USER_CANCELLED,

    @SerialName("RUN_CEILING")
    RUN_CEILING,

    @SerialName("PROCESS_DEATH")
    PROCESS_DEATH,
}

/** How the run ended. */
sealed interface RunTerminal {
    /** The full cut → resume → cut sequence was observed. The only outcome that unlocks control. */
    data object Passed : RunTerminal

    /**
     * The battery climbed past the commanded cap. Recorded in the **existing**
     * `EnforcementEvidenceStore` — the same claim the passive engine makes, with the same terminality.
     */
    data object Refuted : RunTerminal

    data class Inconclusive(val reason: InconclusiveReason) : RunTerminal

    data class Aborted(val reason: AbortReason) : RunTerminal
}

/** Which measurement the run is using to tell "current is flowing" from "charging has stopped". */
@Serializable
enum class FlowSignal {
    /**
     * `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` deltas. Preferred: a ROM can report a
     * synthetic level (MagicOS reports 100% with ~278 mAh of real headroom) and on a fixed-cap run
     * the level barely moves anyway.
     */
    @SerialName("COUNTER")
    COUNTER,

    /** Battery percent. Coarse, and only used when the counter is absent or implausible. */
    @SerialName("LEVEL")
    LEVEL,

    /** Neither is usable. A run in this state can only ever end [InconclusiveReason.NO_SIGNAL]. */
    @SerialName("NONE")
    NONE,
}

object QualificationProtocol {

    /**
     * Bumped whenever the protocol materially changes. Stored on every result and checked on read:
     * unlike the passive evidence record there is no safe default for a *positive* verdict, so this
     * field — not an enum default — is what makes a partially-decoded record fail closed.
     */
    const val PROTOCOL_VERSION = 1

    /** How long accumulation must stay stopped before a cut counts as observed. */
    const val HOLD_CONFIRM_MILLIS = 12 * 60 * 1000L

    /** Budget for each of the three measured phases. */
    const val PHASE_BUDGET_MILLIS = 25 * 60 * 1000L

    /** Budget for reaching the preconditions before anything is written. */
    const val PREFLIGHT_BUDGET_MILLIS = 30 * 60 * 1000L

    /** Budget for charging up to the cap on a fixed-cap adapter. */
    const val CHARGE_UP_BUDGET_MILLIS = 4 * 60 * 60 * 1000L

    /** Absolute ceiling for a whole run, whatever phase it is in. */
    const val RUN_CEILING_MILLIS = 6 * 60 * 60 * 1000L

    /**
     * How far above the commanded cap the level must climb before the run refutes. Shared with the
     * passive engine deliberately: a refutation from either path is the same claim, so they must not
     * disagree about what counts as one.
     */
    const val OVERSHOOT_ALLOWANCE = 3

    /** How far below the cap [RunShape.FIXED_CAP] wants the battery before it starts measuring. */
    const val FIXED_CAP_ENTRY_MARGIN = 2

    /**
     * On [RunShape.VARIABLE_CAP], how far below the current level the low cap is placed. Enough that
     * a device sitting exactly at the boundary is unambiguously above it.
     */
    const val VARIABLE_CAP_UNDERSHOOT = 3

    /** On [RunShape.VARIABLE_CAP], how far above the current level the release cap is placed. */
    const val VARIABLE_CAP_HEADROOM = 5

    /**
     * How much charge must accumulate before the run calls it "charging", as a fraction of the
     * battery's implied full capacity rather than an absolute charge. A ROM that reports milli-units
     * where Android documents micro-units (the MagicOS defect) scales the counter and the implied
     * capacity by the same factor, so the ratio is unaffected — which is what keeps this from reading
     * a 1000×-too-small delta as a hold.
     *
     * The measurement is deliberately binary: a reading either accumulated this much since the phase
     * anchor or it did not, and the anchor resets whenever it did. There is no separate "flat"
     * threshold, because the hold is established by *time without a rise*, not by a second bound.
     * Half a percent over the 12-minute [HOLD_CONFIRM_MILLIS] window needs roughly 100 mA average,
     * which sits an order of magnitude below any real charge current and above a capped device's ~0.
     */
    const val RISE_FRACTION_DENOMINATOR = 200

    /**
     * Below this implied full capacity the counter is not believable as microamp-hours for any phone
     * battery, so the run falls back to [FlowSignal.LEVEL] rather than trusting it.
     */
    const val MIN_PLAUSIBLE_FULL_MICROAMP_HOURS = 100_000L
}
