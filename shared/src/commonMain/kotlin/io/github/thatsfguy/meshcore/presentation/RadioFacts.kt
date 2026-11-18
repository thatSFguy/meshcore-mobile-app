package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.firmware.deviceIdentityLine
import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import io.github.thatsfguy.meshcore.protocol.RadioUnits

/**
 * The connected radio, as labelled rows for a read-only summary.
 *
 * Written for iOS's Settings, which shows the radio rather than editing
 * it (yet). Pure so the formatting is pinned by tests, not by eye: the
 * frequency in particular has been rendered wrong before, and 910.525 MHz
 * is the value confirmed against real hardware (CLAUDE.md, 2026-08-05).
 * A row is omitted rather than shown as "0" or "unknown" when the radio
 * did not say — old firmware stops DEVICE_INFO early.
 */
object RadioFacts {

    data class Fact(val label: String, val value: String)

    fun of(selfInfo: SelfInfo?, device: DeviceInfo?, battery: BatteryAndStorage?): List<Fact> {
        val out = ArrayList<Fact>()
        val self = selfInfo
        if (self != null) {
            if (self.name.isNotBlank()) out += Fact("Name", self.name)
            if (self.freqKhz > 0) out += Fact("Frequency", RadioUnits.khzToMhzText(self.freqKhz) + " MHz")
            if (self.bwHz > 0) out += Fact("Bandwidth", RadioUnits.hzToKhzText(self.bwHz) + " kHz")
            if (self.sf in 5..12) out += Fact("Spreading factor", "SF${self.sf}")
            if (self.cr in 5..8) out += Fact("Coding rate", "4/${self.cr}")
            out += Fact(
                "TX power",
                if (self.maxTxPowerDbm > 0) "${self.txPowerDbm} dBm (max ${self.maxTxPowerDbm})" else "${self.txPowerDbm} dBm",
            )
        }
        if (battery != null && battery.batteryMillivolts > 0) {
            out += Fact("Battery", BatteryLevel.label(battery.batteryMillivolts))
        }
        if (device != null) {
            // The same one-line identity the firmware screen uses.
            out += Fact("Device", deviceIdentityLine(device) ?: "protocol v${device.firmwareVerCode}")
            if (device.pathHashByteWidth in 1..4) {
                // The mesh's hop-hash width — the value four separate bugs
                // treated as a constant 1. Worth seeing.
                out += Fact("Hop hash", "${device.pathHashByteWidth} byte${if (device.pathHashByteWidth == 1) "" else "s"}")
            }
            if (device.maxContacts > 0) out += Fact("Contact slots", device.maxContacts.toString())
        }
        return out
    }
}
