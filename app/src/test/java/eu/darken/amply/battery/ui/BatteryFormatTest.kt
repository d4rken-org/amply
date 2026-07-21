package eu.darken.amply.battery.ui

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Locale

class BatteryFormatTest {

    private val locale = Locale.US

    @Test
    fun `null inputs format to null`() {
        formatTemperature(null, locale) shouldBe null
        formatVoltage(null, locale) shouldBe null
        formatCurrent(null, locale) shouldBe null
        formatChargeCounter(null, locale) shouldBe null
    }

    @Test
    fun `temperature is tenths of a degree`() {
        formatTemperature(314, locale) shouldBe "31.4 °C"
    }

    @Test
    fun `negative temperature keeps its sign`() {
        formatTemperature(-52, locale) shouldBe "-5.2 °C"
    }

    @Test
    fun `voltage is millivolts to volts`() {
        formatVoltage(4185, locale) shouldBe "4.19 V"
    }

    @Test
    fun `current is microamps to milliamps, sign preserved`() {
        formatCurrent(1_250_000, locale) shouldBe "1250 mA"
        formatCurrent(-450_000, locale) shouldBe "-450 mA"
    }

    @Test
    fun `current rounds to whole milliamps`() {
        formatCurrent(1_499, locale) shouldBe "1 mA"
        formatCurrent(1_500, locale) shouldBe "2 mA"
    }

    @Test
    fun `charge counter is microamp-hours to milliamp-hours`() {
        formatChargeCounter(3_800_000, locale) shouldBe "3800 mAh"
    }

    @Test
    fun `formatting honours the locale decimal separator`() {
        formatTemperature(314, Locale.GERMANY) shouldBe "31,4 °C"
    }
}
