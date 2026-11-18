package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.model.BatteryAndStorage
import io.github.thatsfguy.meshcore.model.DeviceInfo
import io.github.thatsfguy.meshcore.model.SelfInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadioFactsTest {

    private fun self(freqKhz: Long = 910_525, bwHz: Long = 250_000, sf: Int = 10, cr: Int = 5) = SelfInfo(
        advType = 1, txPowerDbm = 22, maxTxPowerDbm = 30, publicKey = ByteArray(32),
        latitude = 0.0, longitude = 0.0, multiAcks = 0, advertLocPolicy = 0, telemetryModes = 0,
        manualAddContacts = false, freqKhz = freqKhz, bwHz = bwHz, sf = sf, cr = cr, name = "Blue Base",
    )

    private fun device(width: Int = 2, version: String? = "v1.16.0") = DeviceInfo(
        firmwareVerCode = 10, maxContacts = 350, maxChannels = 40, clientRepeat = false,
        pathHashByteWidth = width, blePin = null, firmwareBuildDate = null,
        boardName = "RAK 4631", firmwareVersion = version,
    )

    private fun facts(s: SelfInfo? = self(), d: DeviceInfo? = device(), b: BatteryAndStorage? = null) =
        RadioFacts.of(s, d, b).associate { it.label to it.value }

    @Test
    fun theRadioReadsAsItDoesOnHardware() {
        val f = facts(b = BatteryAndStorage(3920, null, null))
        // 910.525 MHz is the reading confirmed against a real radio.
        assertEquals("910.525 MHz", f["Frequency"])
        assertEquals("250 kHz", f["Bandwidth"])
        assertEquals("SF10", f["Spreading factor"])
        assertEquals("4/5", f["Coding rate"])
        assertEquals("22 dBm (max 30)", f["TX power"])
        assertEquals("3.92 V · ~76%", f["Battery"])
        assertEquals("RAK 4631 · v1.16.0", f["Device"])
        assertEquals("2 bytes", f["Hop hash"])
        assertEquals("350", f["Contact slots"])
    }

    @Test
    fun aNarrowBandwidthKeepsItsFraction() {
        assertEquals("62.5 kHz", facts(s = self(bwHz = 62_500))["Bandwidth"])
    }

    @Test
    fun whatTheRadioDidNotSayIsLeftOut() {
        val f = facts(s = self(freqKhz = 0, bwHz = 0, sf = 0, cr = 0), d = device(width = 0), b = BatteryAndStorage(0, null, null))
        for (absent in listOf("Frequency", "Bandwidth", "Spreading factor", "Coding rate", "Battery", "Hop hash")) {
            assertTrue(absent !in f, absent)
        }
    }

    @Test
    fun oldFirmwareWithNoVersionStringStillSaysSomething() {
        val f = facts(d = DeviceInfo(9, 100, 8, null, 1, null, null, null, null))
        assertEquals("protocol v9", f["Device"])
        assertEquals("1 byte", f["Hop hash"])
    }

    @Test
    fun nothingConnectedIsNoRows() {
        assertTrue(RadioFacts.of(null, null, null).isEmpty())
    }
}
