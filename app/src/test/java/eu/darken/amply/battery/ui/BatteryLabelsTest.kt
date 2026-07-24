package eu.darken.amply.battery.ui

import android.os.BatteryManager
import eu.darken.amply.R
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatteryLabelsTest {

    @Test
    fun `plugged but not charging gets the explicit plugged wording`() {
        batteryStatusLabel(
            status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
        ) shouldBe R.string.battery_status_plugged_not_charging
    }

    @Test
    fun `unplugged not charging keeps the plain label`() {
        batteryStatusLabel(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING, plugged = 0) shouldBe
            R.string.battery_status_not_charging
        batteryStatusLabel(status = BatteryManager.BATTERY_STATUS_NOT_CHARGING, plugged = null) shouldBe
            R.string.battery_status_not_charging
    }

    @Test
    fun `other statuses pass through regardless of plug state`() {
        batteryStatusLabel(status = BatteryManager.BATTERY_STATUS_CHARGING, plugged = BatteryManager.BATTERY_PLUGGED_AC) shouldBe
            R.string.battery_status_charging
        batteryStatusLabel(status = BatteryManager.BATTERY_STATUS_FULL, plugged = BatteryManager.BATTERY_PLUGGED_AC) shouldBe
            R.string.battery_status_full
        batteryStatusLabel(status = BatteryManager.BATTERY_STATUS_DISCHARGING, plugged = 0) shouldBe
            R.string.battery_status_discharging
        batteryStatusLabel(status = null, plugged = BatteryManager.BATTERY_PLUGGED_AC) shouldBe
            R.string.battery_value_unknown
    }
}
