package eu.darken.amply.charging.core.qualification

import androidx.annotation.StringRes
import eu.darken.amply.R
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

    /**
     * [RunShape.FIXED_CAP] only: the cap is lifted and the battery charges up to just under it, so a
     * hold is observable at all. **Positioning, never measurement** — no rate and no update count is
     * taken here, because a window that may legitimately run for hours averages away exactly the
     * granularity the later phases are judged on.
     */
    CHARGE_UP,

    /**
     * The **within-run control**, and the reason this protocol can conclude anything. The cap is
     * lifted and the run measures how fast charge actually goes into *this* battery from *this*
     * charger right now. Every later phase is judged against that number, never against an absolute
     * threshold.
     *
     * Without it the measurement is unsound in both directions. Any fixed "charging has stopped" bar
     * is too high for a weak supply — a phone charging steadily at 100 mA sits under it and reads as
     * held indefinitely, which is a false pass — and it is meaningless on a battery whose capacity or
     * reporting units are unknown.
     *
     * It is a **bounded** [QualificationProtocol.BASELINE_WINDOW_MILLIS] window in both shapes. That
     * bound is what makes [QualificationProtocol.MIN_BASELINE_UPDATES] mean something: three observed
     * changes inside ten minutes put the device's reporting period under five, so a twelve-minute cut
     * window that sees nothing is evidence rather than an artifact of when the gauge happens to tick.
     */
    BASELINE,

    /** The cap is written; waiting for the accumulation rate to collapse against the baseline. */
    CUT_1,

    /** The cap is raised (or removed); waiting for the rate to recover. This is the discriminator. */
    RESUME,

    /** The cap is written again; waiting for the rate to collapse a second time. */
    CUT_2,
    ;

    /**
     * A sentence describing this phase, taking the run's cap as its one format argument. Lives here
     * rather than in the UI because both the screen and the foreground-service notification render
     * it, and a run the user was told to walk away from must say the same thing in both places.
     * Every phase takes the argument so the caller never has to know which ones use it.
     */
    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            PREFLIGHT -> R.string.qualification_phase_preflight
            CHARGE_UP -> R.string.qualification_phase_charge_up
            BASELINE -> R.string.qualification_phase_baseline
            CUT_1 -> R.string.qualification_phase_cut_1
            RESUME -> R.string.qualification_phase_resume
            CUT_2 -> R.string.qualification_phase_cut_2
        }
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
     * The battery reached [QualificationProtocol.NEAR_FULL_PERCENT] during a cut phase, so a stopped
     * battery no longer distinguishes a working cap from a full one.
     */
    @SerialName("NEAR_FULL")
    NEAR_FULL,

    /**
     * The device held, but not at the cap that was commanded. Only reachable on a candidate adapter,
     * where the OEM's value semantics are a guess — a One UI 6/7 device holding at 85 under a
     * commanded 80 means the mapping differs, not that the hardware failed.
     */
    @SerialName("CAP_MISMATCH")
    CAP_MISMATCH,

    @SerialName("CHARGE_UP_TIMEOUT")
    CHARGE_UP_TIMEOUT,

    /**
     * The device reports charge too rarely for the protocol to read anything into a quiet window.
     *
     * A device whose level or batched charge counter updates once every ~20 minutes produces a
     * textbook cut → resume → cut out of nothing but *when* it happened to report: one update inside
     * the baseline, none inside the first cut, one inside the resume, none inside the second — with
     * the cap doing nothing at all. So the control window has to observe
     * [QualificationProtocol.MIN_BASELINE_UPDATES] real changes before any later silence is allowed
     * to mean something.
     */
    @SerialName("SIGNAL_TOO_COARSE")
    SIGNAL_TOO_COARSE,

    /**
     * The control phase never measured charge going in fast enough to judge anything against — a
     * weak or dead charger, a battery that was already full, or a charge counter that does not move.
     * Without a baseline every later phase would be comparing to nothing.
     */
    @SerialName("NO_BASELINE")
    NO_BASELINE,

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

    /**
     * The foreground service that feeds the run its battery ticks could not be started, so nothing
     * would ever have observed it. The run is closed out immediately rather than left as a record
     * nothing advances.
     */
    @SerialName("SERVICE_UNAVAILABLE")
    SERVICE_UNAVAILABLE,
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
     *
     * Version 2 is what the algorithm below now means, and version-1 passes are deliberately dropped
     * rather than migrated: a pass then could be produced without the within-run baseline control, on
     * a device whose gauge reports too coarsely for a quiet cut window to mean anything, and by a run
     * that licensed nothing. Those are exactly the false passes this protocol exists to prevent, so a
     * record from the superseded algorithm is no evidence at all.
     */
    const val PROTOCOL_VERSION = 2

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
     * At or above this level a stopped battery is not evidence of anything: a full battery stops
     * charging on its own, so the cap and "nothing left to put in" become indistinguishable.
     *
     * This closes a real false-pass path. A variable-cap run starting at 99% would cap at 95, see
     * charging stop (because the battery was nearly full), release the cap, see the last percent go
     * in as a genuine "resume", re-cap, and then see the now-full battery sit still — a textbook
     * cut → resume → cut on a device whose cap does nothing. Both the eligibility check and the hold
     * confirmation refuse to operate up here.
     */
    const val NEAR_FULL_PERCENT = 97

    /**
     * On [RunShape.VARIABLE_CAP], how far below the current level the low cap is placed. Enough that
     * a device sitting exactly at the boundary is unambiguously above it.
     */
    const val VARIABLE_CAP_UNDERSHOOT = 3

    /** On [RunShape.VARIABLE_CAP], how far above the current level the release cap is placed. */
    const val VARIABLE_CAP_HEADROOM = 5

    /** How long the [RunPhase.BASELINE] control must run before its rate is trusted. */
    const val BASELINE_WINDOW_MILLIS = 10 * 60 * 1000L

    /**
     * How many times the accumulation signal must be seen *changing* inside that window before the
     * run will read anything into a later window where it does not change.
     *
     * Three inside [BASELINE_WINDOW_MILLIS] puts the device's reporting period under five minutes, so
     * a [HOLD_CONFIRM_MILLIS] window observing nothing is the device holding rather than the device
     * not having got round to reporting. Without this a coarsely-reporting device can stage the whole
     * cut → resume → cut sequence out of the timing of its own updates.
     */
    const val MIN_BASELINE_UPDATES = 3

    /** Shortest window any rate is computed over; below it the arithmetic is noise. */
    const val MIN_MEASURE_WINDOW_MILLIS = 5 * 60 * 1000L

    /**
     * How far the accumulation rate must fall below the baseline before a cut counts as observed.
     *
     * A ratio against the run's own control, not an absolute current, which is what makes this work
     * on a weak charger: a supply that was only ever delivering 100 mA has to drop to 10 mA to pass,
     * instead of being under a fixed bar from the start. Ten-fold is far outside anything ordinary
     * charge-rate variation produces, and a real cap goes to essentially zero.
     */
    const val CUT_RATE_DROP_FACTOR = 10

    /**
     * How much of the baseline rate must come back for a resume to count. Deliberately lenient
     * compared to the cut: charging genuinely tapers as the battery fills, so demanding the full
     * baseline back would fail honest devices. A third of the original rate is still an order of
     * magnitude above the cut bar, so the two can never both be satisfied.
     */
    const val RESUME_RATE_RECOVERY_DIVISOR = 3

    /**
     * The slowest baseline the run will accept as a usable control. Below this nothing is going into
     * the battery fast enough to tell a cap from a stall, whatever the cause — a dead charger, a full
     * battery, a frozen charge counter. Expressed against the battery's implied full capacity, so it
     * is independent of cell size and of the milli-versus-micro unit defect: one percent of capacity
     * per hour, which even a 5 W charger beats by more than an order of magnitude.
     */
    const val MIN_BASELINE_RATE_CAPACITY_FRACTION_PER_HOUR_DENOMINATOR = 100

    /**
     * How long **after the host acknowledged the write** the run waits before believing what it sees.
     * A settings write and the charging hardware acting on it are not simultaneous, and a tick
     * captured in between describes the *previous* configuration — which is how an unremarkable
     * percent tick can look like charging past a cap that was not in force yet.
     *
     * Measured from the acknowledgement, never from the moment the engine emitted the command: the
     * two are separated by a settings write that can take seconds (a cold Shizuku bind), and a window
     * opened on the earlier of them would count charging that happened under the old configuration.
     */
    const val WRITE_SETTLE_MILLIS = 90_000L

    /**
     * Below this implied full capacity the counter is not believable as microamp-hours for any phone
     * battery, so the run falls back to [FlowSignal.LEVEL] rather than trusting it.
     */
    const val MIN_PLAUSIBLE_FULL_MICROAMP_HOURS = 100_000L
}
