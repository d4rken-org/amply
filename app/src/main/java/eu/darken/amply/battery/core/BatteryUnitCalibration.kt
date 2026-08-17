package eu.darken.amply.battery.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.amply.common.debug.logging.Logging
import eu.darken.amply.common.debug.logging.log
import eu.darken.amply.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

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
 */
@Singleton
class BatteryUnitCalibration @Inject constructor(
    @ApplicationContext private val context: Context,
) {

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

    companion object {
        private val TAG = logTag("Battery", "UnitCalibration")

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
