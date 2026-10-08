package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.TelemetryReading
import io.github.thatsfguy.meshcore.util.fixed
import kotlin.math.roundToInt

/**
 * A battery percentage ESTIMATED from a voltage.
 *
 * **No MeshCore node sends a percentage.** Every source this app reads
 * is volts: `battery_mv` in the status response, the Cayenne LPP
 * `Voltage` (type 116) a node puts on channel 1 (`TELEM_CHANNEL_SELF`,
 * `src/helpers/SensorManager.h:10`; written as
 * `telemetry.addVoltage(TELEM_CHANNEL_SELF, getBattMilliVolts() / 1000.0f)`
 * in `examples/simple_repeater/MyMesh.cpp:244` and its room-server,
 * sensor and companion siblings), and `RESP_CODE_BATT_AND_STORAGE`.
 * Checked against firmware `main` at a366955 (2026-09-30).
 *
 * The number shown here is the one the node draws on its own screen:
 * `renderBatteryIndicator` in `examples/companion_radio/ui-new/UITask.cpp`
 * is a straight line from `BATT_MIN_MILLIVOLTS` 3000 to
 * `BATT_MAX_MILLIVOLTS` 4200, integer-truncated and clamped. Matching it
 * exactly means the phone and the OLED agree, which matters more than a
 * cleverer curve that would disagree with the device in your hand. It is
 * still a single-cell Li-ion assumption, so the UI marks it with `~`.
 *
 * Outside a plausible single-cell window there is no percentage at all.
 * The firmware itself overrides the range to 6000–8400 for a 2S board
 * (`variants/lilygo_tbeam_1w/platformio.ini`), and a 1S line would read
 * that pack as 100% until it died. A board with no battery sense
 * reports 0. Either way the honest answer is the voltage alone.
 */
object BatteryLevel {

    /** Firmware defaults (`UITask.cpp`, `BATT_MIN/MAX_MILLIVOLTS`). */
    const val EMPTY_MV = 3000
    const val FULL_MV = 4200

    /**
     * The window in which a reading is believed to be one Li-ion cell.
     * Below it is "no sense line"; above it is USB rail, 2S, or solar.
     */
    const val PLAUSIBLE_MIN_MV = 2500
    const val PLAUSIBLE_MAX_MV = 4500

    /** LPP channel a node uses for its own supply (`TELEM_CHANNEL_SELF`). */
    const val SELF_CHANNEL = 1

    /** Cayenne LPP voltage (`LPP_VOLTAGE`, 116). */
    const val LPP_VOLTAGE = 0x74

    /** 0–100, or null when the voltage does not look like one cell. */
    fun percent(millivolts: Int): Int? {
        if (millivolts < PLAUSIBLE_MIN_MV || millivolts > PLAUSIBLE_MAX_MV) return null
        // Same integer arithmetic as the firmware, truncation included.
        val p = ((millivolts - EMPTY_MV) * 100) / (FULL_MV - EMPTY_MV)
        return p.coerceIn(0, 100)
    }

    /** "3.92 V · ~76%", or "3.92 V" when no estimate is believable. */
    fun label(millivolts: Int): String {
        val volts = fixed(millivolts / 1000.0, 2) + " V"
        val p = percent(millivolts) ?: return volts
        return "$volts · ~$p%"
    }

    /**
     * True for the node's own supply voltage in a telemetry reply. Only
     * that reading is a battery: a sensor node can publish other
     * voltages (an INA219 bus, a solar panel) on other channels, and a
     * percentage beside those would be invented.
     */
    fun isSelfBattery(reading: TelemetryReading): Boolean =
        reading.channel == SELF_CHANNEL && reading.type == LPP_VOLTAGE

    /** Millivolts from an LPP voltage (0.01 V resolution). */
    fun millivolts(reading: TelemetryReading): Int = (reading.value * 1000).roundToInt()
}
