package eu.darken.amply.battery.core

/** The unit a device reports `CURRENT_NOW` in, as far as its readings have shown. */
enum class CurrentUnit { UNKNOWN, MILLI, MICRO }

/**
 * What [CurrentUnitInference] has learned so far. The streak fields are only populated while [unit] is
 * [CurrentUnit.UNKNOWN] and at least one consecutive milliamp-evidence reading has been seen.
 */
data class CurrentUnitState(
    val unit: CurrentUnit,
    val streakStartElapsedMillis: Long?,
    val streakCount: Int,
    val lastObservedElapsedMillis: Long?,
) {
    companion object {
        val UNKNOWN = CurrentUnitState(
            unit = CurrentUnit.UNKNOWN,
            streakStartElapsedMillis = null,
            streakCount = 0,
            lastObservedElapsedMillis = null,
        )
    }
}

/**
 * Pure inference of whether `CURRENT_NOW` arrives in microamps (as documented) or milliamps.
 *
 * The two outcomes are asymmetric on purpose:
 * - **MICRO** needs a single reading that is impossible in milliamps, and is terminal.
 * - **MILLI** needs a sustained streak of small readings taken only while the device is unplugged and
 *   interactive, where a real microamp current cannot plausibly stay that low. Plugged readings never
 *   count: a charge-limit hold draws next to nothing in either unit. MILLI is overturned by any later
 *   microamp proof.
 *
 * `interactive` does not guarantee a lit, power-drawing panel, so a microamp device holding under 10 mA for a
 * sustained minute while unplugged and interactive would learn MILLI falsely. That draw is implausible with the
 * screen on, and the next reading of 20 mA or more, in any state, overturns it.
 *
 * Signs are OEM-defined, so only the magnitude is evaluated.
 *
 * ```
 * unplugged, interactive: -254 @ 0s, -313 @ 30s, -438 @ 60s  -> MILLI
 * any state:              -215_000                            -> MICRO
 * ```
 */
object CurrentUnitInference {

    /** Exclusive: a screen-on, unplugged phone draws well under 10 A, so only this band can be milliamps. */
    const val MILLI_EVIDENCE_MAX_ABS = 10_000L

    /** Inclusive: no phone moves >= 20 A, so a reading this large cannot be milliamps. */
    const val MICRO_PROOF_MIN_ABS = 20_000L

    /** Inclusive: 100 A is impossible in either unit; neutral, so a wrapped/sentinel value can never latch MICRO. */
    const val GARBAGE_MIN_ABS = 100_000_000L

    /** Several readings, so one momentary low sample cannot latch MILLI. */
    const val MILLI_STREAK_MIN_READINGS = 3

    /** A minute of sustained low draw, so a brief idle dip on a microamp device cannot latch MILLI. */
    const val MILLI_STREAK_MIN_SPAN_MILLIS = 60_000L

    fun observe(
        state: CurrentUnitState,
        rawCurrent: Int?,
        plugged: Int?,
        interactive: Boolean,
        nowElapsedMillis: Long,
    ): CurrentUnitState {
        val clockWentBackwards = state.lastObservedElapsedMillis != null &&
            nowElapsedMillis < state.lastObservedElapsedMillis
        val base = if (clockWentBackwards) state.clearStreak() else state
        val observed = base.copy(lastObservedElapsedMillis = nowElapsedMillis)

        if (observed.unit == CurrentUnit.MICRO) return observed

        val magnitude = classify(rawCurrent)
        if (magnitude == Magnitude.MICRO_PROOF) {
            return observed.copy(unit = CurrentUnit.MICRO).clearStreak()
        }

        if (observed.unit == CurrentUnit.MILLI) return observed

        val isMilliEvidence = magnitude == Magnitude.MILLI_CANDIDATE && plugged == 0 && interactive
        if (!isMilliEvidence) return observed.clearStreak()

        val streakStart = observed.streakStartElapsedMillis ?: nowElapsedMillis
        val streakCount = observed.streakCount + 1
        val sustained = streakCount >= MILLI_STREAK_MIN_READINGS &&
            nowElapsedMillis - streakStart >= MILLI_STREAK_MIN_SPAN_MILLIS
        if (sustained) {
            return observed.copy(unit = CurrentUnit.MILLI).clearStreak()
        }
        return observed.copy(streakStartElapsedMillis = streakStart, streakCount = streakCount)
    }

    /** Folds two verdicts: a microamp proof outranks a milliamp streak, which outranks no verdict. */
    fun merge(a: CurrentUnit, b: CurrentUnit): CurrentUnit = when {
        a == CurrentUnit.MICRO || b == CurrentUnit.MICRO -> CurrentUnit.MICRO
        a == CurrentUnit.MILLI || b == CurrentUnit.MILLI -> CurrentUnit.MILLI
        else -> CurrentUnit.UNKNOWN
    }

    private enum class Magnitude { ABSENT, MILLI_CANDIDATE, NEUTRAL, MICRO_PROOF, GARBAGE }

    private fun classify(rawCurrent: Int?): Magnitude {
        if (rawCurrent == null || rawCurrent == 0 || rawCurrent == Int.MIN_VALUE) return Magnitude.ABSENT
        // Long before abs(), so no Int value can overflow.
        val abs = kotlin.math.abs(rawCurrent.toLong())
        return when {
            abs >= GARBAGE_MIN_ABS -> Magnitude.GARBAGE
            abs >= MICRO_PROOF_MIN_ABS -> Magnitude.MICRO_PROOF
            abs >= MILLI_EVIDENCE_MAX_ABS -> Magnitude.NEUTRAL
            else -> Magnitude.MILLI_CANDIDATE
        }
    }

    private fun CurrentUnitState.clearStreak() = copy(streakStartElapsedMillis = null, streakCount = 0)
}
