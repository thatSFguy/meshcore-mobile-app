package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.BatteryLevel
import io.github.thatsfguy.meshcore.presentation.UnitSystem
import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepeaterTelemetryTest {

    @Test
    fun readingsBecomeLabelledRowsWithUnits() {
        val rows = RepeaterAdmin.telemetryRows(
            listOf(TelemetryReading(channel = 2, type = 103, label = "Temperature", value = 21.456, unit = "°C")),
            UnitSystem.Metric,
        )
        assertEquals("Temperature (ch 2)", rows.single().label)
        assertEquals("21.46 °C", rows.single().value)
    }

    @Test
    fun imperialConvertsForDisplayOnly() {
        val rows = RepeaterAdmin.telemetryRows(
            listOf(TelemetryReading(channel = 2, type = 103, label = "Temperature", value = 20.0, unit = "°C")),
            UnitSystem.Imperial,
        )
        assertTrue(rows.single().value.startsWith("68.00"), rows.single().value)
    }

    @Test
    fun theNodesOwnVoltageReadsAsABatteryLevel() {
        val self = TelemetryReading(channel = 1, type = 116, label = "Voltage", value = 4.1, unit = "V")
        val row = RepeaterAdmin.telemetryRows(listOf(self), UnitSystem.Metric).single()
        // Channel 1, LPP voltage (0x74 = 116): the node's own supply.
        assertTrue(BatteryLevel.isSelfBattery(self))
        assertEquals(BatteryLevel.label(4100), row.value)
        // The same type on another channel is just a voltage.
        val other = RepeaterAdmin.telemetryRows(listOf(self.copy(channel = 3)), UnitSystem.Metric).single()
        assertEquals("4.10 V", other.value)
    }
}
