package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CayenneLpp
import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Battery percentage from volts.
 *
 * Expected values are worked from the firmware's own formula
 * (`examples/companion_radio/ui-new/UITask.cpp`, `renderBatteryIndicator`):
 * `((mv - 3000) * 100) / (4200 - 3000)`, C integer division, clamped to
 * 0..100. They are what the node's screen shows for the same voltage.
 */
class BatteryLevelTest {

    @Test
    fun endpointsMatchTheFirmwareDefaults() {
        assertEquals(0, BatteryLevel.percent(3000))
        assertEquals(100, BatteryLevel.percent(4200))
        assertEquals(50, BatteryLevel.percent(3600))
    }

    @Test
    fun truncatesLikeTheFirmwareRatherThanRounding() {
        // 920 * 100 / 1200 = 76.67 → 76 on the device, so 76 here.
        assertEquals(76, BatteryLevel.percent(3920))
        // 1199 * 100 / 1200 = 99.92 → 99, not a rounded-up "full".
        assertEquals(99, BatteryLevel.percent(4199))
    }

    @Test
    fun clampsInsideThePlausibleWindow() {
        assertEquals(0, BatteryLevel.percent(2800))
        assertEquals(100, BatteryLevel.percent(4350))
    }

    @Test
    fun noSenseLineGivesNoPercentage() {
        assertNull(BatteryLevel.percent(0))
        assertNull(BatteryLevel.percent(2499))
        assertNull(BatteryLevel.percent(-1))
    }

    @Test
    fun twoCellAndUsbRailGiveNoPercentage() {
        // tbeam_1w is 2S (6000–8400 in its platformio.ini); 7.4 V nominal
        // would read 100% on a 1S line until the pack died.
        assertNull(BatteryLevel.percent(7400))
        assertNull(BatteryLevel.percent(5000))
        assertNull(BatteryLevel.percent(4501))
        // u16 maximum off the wire.
        assertNull(BatteryLevel.percent(65_535))
    }

    @Test
    fun labelKeepsTheVoltageAndMarksTheEstimate() {
        assertEquals("3.92 V · ~76%", BatteryLevel.label(3920))
        assertEquals("4.20 V · ~100%", BatteryLevel.label(4200))
        assertEquals("7.40 V", BatteryLevel.label(7400))
        assertEquals("0.00 V", BatteryLevel.label(0))
    }

    @Test
    fun aRepeatersSelfVoltageOffTheWireIsItsBattery() {
        // [ch 1][type 0x74][0x01 0x88] = 392 × 0.01 V, as
        // telemetry.addVoltage(TELEM_CHANNEL_SELF, …) encodes it.
        val readings = CayenneLpp.parse(byteArrayOf(0x01, 0x74, 0x01, 0x88.toByte()))
        val r = readings.single()
        assertTrue(BatteryLevel.isSelfBattery(r))
        assertEquals(3920, BatteryLevel.millivolts(r))
        assertEquals(76, BatteryLevel.percent(BatteryLevel.millivolts(r)))
    }

    @Test
    fun otherVoltagesAreNotBatteries() {
        // An INA219 bus voltage on a sensor channel.
        assertFalse(BatteryLevel.isSelfBattery(TelemetryReading(2, 0x74, "Voltage", 3.9, "V")))
        // Channel 1, but not a voltage.
        assertFalse(BatteryLevel.isSelfBattery(TelemetryReading(1, 0x67, "Temperature", 3.9, "°C")))
    }
}
