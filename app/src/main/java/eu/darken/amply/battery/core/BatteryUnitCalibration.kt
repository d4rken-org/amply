package eu.darken.amply.battery.core

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.abs

/** How [BatteryReader] must treat a `CURRENT_NOW` reading. */
enum class CurrentScale {
    /** The unit has not been loaded yet; the reading must not be shown uncorrected. */
    NOT_READY,
    AS_REPORTED,
    MILLI_SCALED,
}

/**
 * Whether this device's ROM is known to report battery telemetry in **milli**-units where
 * [android.os.BatteryManager] documents micro-units, making `CURRENT_NOW` and `CHARGE_COUNTER` 1000× too
 * small. Confirmed on HONOR MagicOS 10 (issue #66): a 7100 mAh cell reported a charge counter of `6978`
 * (rendered "7 mAh") and `Current now` of 0 mA while visibly discharging.
 *
 * **Why a ROM gate rather than pure inference.** Detecting this from the numbers alone was tried twice and
 * abandoned both times, because the states that look like the defect are states Amply itself creates. An
 * impossibly small charge counter proves nothing about current — they are independent HAL fields — so
 * corroboration has to come from current, and "charging while drawing almost nothing" is exactly what a
 * device does *at a charge-limit hold* (see `StatsLimitHitDetector`, which uses that as its hold signal).
 * A healthy phone holding at 80% with a broken counter would have satisfied any such rule and had its real
 * readings multiplied by a thousand, turning ~50 mA into ~200 W: under the plausibility ceiling, and so
 * recorded as a believable lie. Being wrong about a ROM's units shows wrong numbers; being wrong about a
 * healthy device corrupts good ones. This gate can only ever affect the former.
 *
 * The ROM check is **necessary but not sufficient**: [BatteryReadoutFactory] additionally requires the
 * anomaly to be visible in the reading, so a MagicOS build that reports correctly is left alone.
 *
 * Generalizing "all MagicOS" from one device is a deliberate, bounded bet, and a much cheaper one than the
 * equivalent for charge control: the failure mode is a wrong battery figure, not a false claim that a
 * battery is protected. Other affected ROMs stay uncorrected until one is confirmed and added here.
 *
 * Separately, on Samsung only, [observeCurrent] learns from the readings themselves whether `CURRENT_NOW`
 * alone arrives in milliamps (see [CurrentUnitInference]) and persists the verdict per ROM build.
 */
@Singleton
class BatteryUnitCalibration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unitStore: Provider<BatteryUnitStore>,
    @BatteryUnitDispatcher dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val isSamsung: Boolean = Build.MANUFACTURER?.equals(SAMSUNG, ignoreCase = true) == true

    // Guarded by `this`.
    private var unitState = CurrentUnitState.UNKNOWN
    private var hydrated = false
    private var persistedUnit = CurrentUnit.UNKNOWN
    private var writeInFlight = false

    init {
        if (isSamsung) scope.launch { hydrate() }
    }

    /**
     * Resolved once: ROM identity cannot change while the process lives. System features need no
     * `<queries>` entry and no permission, and are not subject to package-visibility filtering.
     * Fails closed, so an unreadable package manager means "correct the nothing".
     */
    val romMisreportsUnits: Boolean by lazy {
        val detected = MAGICOS_FEATURES.any { feature ->
            runCatching { context.packageManager.hasSystemFeature(feature) }.getOrDefault(false)
        }
        if (detected) {
            log(TAG, Logging.Priority.INFO) { "MagicOS detected; battery telemetry units will be corrected" }
        }
        detected
    }

    /**
     * Feeds one `CURRENT_NOW` reading to the unit inference and says how to scale it. Non-blocking: the
     * learned unit is loaded and persisted in the background. Only Samsung devices are inferred at all.
     */
    @Synchronized
    fun observeCurrent(
        rawCurrent: Int?,
        plugged: Int?,
        interactive: Boolean,
        nowElapsedMillis: Long,
    ): CurrentScale {
        if (!isSamsung) return CurrentScale.AS_REPORTED

        val before = unitState.unit
        unitState = CurrentUnitInference.observe(unitState, rawCurrent, plugged, interactive, nowElapsedMillis)
        if (unitState.unit != before) {
            log(TAG, Logging.Priority.INFO) {
                "CURRENT_NOW unit $before -> ${unitState.unit} " +
                    "(|raw|=${rawCurrent?.let { abs(it.toLong()) }}, plugged=$plugged, interactive=$interactive)"
            }
        }

        if (!hydrated) return CurrentScale.NOT_READY
        persistIfNeeded()
        return if (unitState.unit == CurrentUnit.MILLI) CurrentScale.MILLI_SCALED else CurrentScale.AS_REPORTED
    }

    private suspend fun hydrate() {
        val stored = try {
            unitStore.get().read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, Logging.Priority.WARN) { "Loading the learned CURRENT_NOW unit failed: $e" }
            CurrentUnit.UNKNOWN
        }
        synchronized(this) {
            if (stored != CurrentUnit.UNKNOWN) {
                log(TAG, Logging.Priority.INFO) { "Loaded learned CURRENT_NOW unit $stored" }
            }
            foldIn(stored)
            persistedUnit = stored
            hydrated = true
        }
    }

    /** Called with the lock held. Launches at most one write; a failed one is retried by a later reading. */
    private fun persistIfNeeded() {
        val unit = unitState.unit
        if (unit == CurrentUnit.UNKNOWN || unit == persistedUnit || writeInFlight) return
        writeInFlight = true
        scope.launch {
            try {
                val stored = unitStore.get().learn(unit)
                synchronized(this@BatteryUnitCalibration) {
                    persistedUnit = stored
                    foldIn(stored)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, Logging.Priority.WARN) { "Persisting CURRENT_NOW unit $unit failed: $e" }
            } finally {
                synchronized(this@BatteryUnitCalibration) { writeInFlight = false }
            }
        }
    }

    /** Called with the lock held: a stored verdict is merged, never replaces a stronger in-memory one. */
    private fun foldIn(stored: CurrentUnit) {
        val merged = CurrentUnitInference.merge(unitState.unit, stored)
        if (merged != unitState.unit) {
            log(TAG, Logging.Priority.INFO) { "CURRENT_NOW unit ${unitState.unit} -> $merged (stored)" }
        }
        unitState = if (merged == CurrentUnit.UNKNOWN) {
            unitState
        } else {
            unitState.copy(unit = merged, streakStartElapsedMillis = null, streakCount = 0)
        }
    }

    companion object {
        private val TAG = logTag("Battery", "UnitCalibration")

        private const val SAMSUNG = "samsung"

        /**
         * Any one match is enough, so a slimmed or renamed component cannot break detection. Reported from
         * a Magic8 Pro `HNBKQ` on MagicOS 10.0.0.193 via `pm list features`.
         */
        private val MAGICOS_FEATURES = listOf(
            "com.hihonor.software.features.honor",
            "com.hihonor.system.feature",
        )
    }
}
