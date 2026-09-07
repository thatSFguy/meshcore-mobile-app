package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeaterStatusTest {

    private fun statusFrame(
        battery: Int = 4050, queue: Int = 3, noise: Int = -95, rssi: Int = -80,
        recv: Long = 1000, sent: Long = 500, txAir: Long = 60, uptime: Long = 90_000,
        floodTx: Long = 10, directTx: Long = 20, floodRx: Long = 30, directRx: Long = 40,
        errs: Int = 2, snrRaw: Int = 24, dupD: Int = 1, dupF: Int = 4, rxAir: Long = 120,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_STATUS_RESPONSE)
        w.writeByte(0)
        w.writeBytes(ByteArray(6) { (it + 1).toByte() })
        w.writeUInt16LE(battery); w.writeUInt16LE(queue)
        w.writeUInt16LE(noise and 0xFFFF); w.writeUInt16LE(rssi and 0xFFFF)
        w.writeUInt32LE(recv); w.writeUInt32LE(sent); w.writeUInt32LE(txAir); w.writeUInt32LE(uptime)
        w.writeUInt32LE(floodTx); w.writeUInt32LE(directTx)
        w.writeUInt32LE(floodRx); w.writeUInt32LE(directRx)
        w.writeUInt16LE(errs); w.writeUInt16LE(snrRaw and 0xFFFF)
        w.writeUInt16LE(dupD); w.writeUInt16LE(dupF)
        w.writeUInt32LE(rxAir)
        return w.toBytes()
    }

    @Test
    fun parsesFullStatus() {
        val s = StatusCodec.parse(statusFrame())!!
        assertEquals("010203040506", s.senderPrefixHex)
        assertEquals(4050, s.batteryMillivolts)
        assertEquals(4.05, s.batteryVolts, 1e-9)
        assertEquals(3, s.queueLength)
        assertEquals(-95, s.noiseFloor)      // signed
        assertEquals(-80, s.lastRssi)        // signed
        assertEquals(1000L, s.packetsReceived)
        assertEquals(90_000L, s.uptimeSeconds)
        assertEquals(6.0, s.lastSnr, 1e-9)   // raw/4
        assertEquals(4, s.floodDuplicates)
        assertEquals(120L, s.rxAirSeconds)
        // (60 + 120) / 90000 * 100
        assertEquals(0.2, s.channelUtilizationPercent, 1e-9)
    }

    @Test
    fun truncatedStatusIsNull() {
        assertNull(StatusCodec.parse(ByteArray(0)))
        assertNull(StatusCodec.parse(ByteArray(30)))
        val full = statusFrame()
        for (len in 0 until full.size) {
            StatusCodec.parse(full.copyOfRange(0, len)) // must not throw
        }
    }

    @Test
    fun zeroUptimeDoesNotDivideByZero() {
        val s = StatusCodec.parse(statusFrame(uptime = 0))!!
        assertEquals(0.0, s.channelUtilizationPercent, 1e-9)
    }

    @Test
    fun uptimeFormatting() {
        assertEquals("—", StatusCodec.formatUptime(0))
        assertEquals("5m", StatusCodec.formatUptime(300))
        assertEquals("2h 5m", StatusCodec.formatUptime(7500))
        assertEquals("1d 1h 1m", StatusCodec.formatUptime(90_060))
    }

    @Test
    fun cayenneTemperatureHumidityVoltage() {
        // ch1 temp 23.5C, ch2 humidity 55%, ch3 voltage 4.05V
        val payload = byteArrayOf(
            1, 0x67, 0x00, 0xEB.toByte(),      // 235 / 10
            2, 0x68, 110,                       // 110 / 2
            3, 0x74, 0x01, 0x95.toByte(),      // 405 / 100
        )
        val r = CayenneLpp.parse(payload)
        assertEquals(3, r.size)
        assertEquals(23.5, r[0].value, 1e-9); assertEquals("°C", r[0].unit)
        assertEquals(55.0, r[1].value, 1e-9)
        assertEquals(4.05, r[2].value, 1e-9); assertEquals("V", r[2].unit)
    }

    @Test
    fun cayenneNegativeTemperature() {
        // -5.0 C = -50 → 0xFFCE
        val r = CayenneLpp.parse(byteArrayOf(1, 0x67, 0xFF.toByte(), 0xCE.toByte()))
        assertEquals(-5.0, r[0].value, 1e-9)
    }

    @Test
    fun cayenneStopsAtUnknownTypeInsteadOfMisreading() {
        val payload = byteArrayOf(1, 0x67, 0x00, 0xEB.toByte(), 2, 0x5A, 9, 9, 9)
        val r = CayenneLpp.parse(payload)
        assertEquals(1, r.size)   // only the temperature, then stop
    }

    @Test
    fun cayenneTruncatedPayloadDegrades() {
        val r = CayenneLpp.parse(byteArrayOf(1, 0x67, 0x00))  // missing a byte
        assertTrue(r.isEmpty())
        assertTrue(CayenneLpp.parse(ByteArray(0)).isEmpty())
    }

    /** ch, 0x88, lat(3 BE), lon(3 BE), alt(3 BE) — GPS is nine bytes. */
    private fun gps(latRaw: Int, lonRaw: Int, altRaw: Int): ByteArray {
        fun be24(v: Int) = byteArrayOf(
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte(),
        )
        return byteArrayOf(1, 0x88.toByte()) + be24(latRaw) + be24(lonRaw) + be24(altRaw)
    }

    @Test
    fun cayenneGpsReadsARealFix() {
        // The positive control. Everything below asserts that a reading
        // is WITHHELD, and every one of those would pass if the GPS
        // branch had been deleted outright — so pin the case where it
        // must answer. 42.9634, -85.6681 at 1/10000 deg.
        val r = CayenneLpp.parse(gps(429_634, -856_681, 20_000))
        assertEquals(3, r.size)
        assertEquals("Latitude", r[0].label)
        assertEquals(42.9634, r[0].value, 1e-9)
        assertEquals("Longitude", r[1].label)
        assertEquals(-85.6681, r[1].value, 1e-9)
        assertEquals("Altitude", r[2].label)
        assertEquals(200.0, r[2].value, 1e-9)
    }

    @Test
    fun cayenneGpsWithNoFixReportsNoPosition() {
        // A GPS sensor with no fix still reports its channel, at 0, 0.
        // That is MeshCore's "unset", not a point in the Gulf of Guinea,
        // and a repeater's status page is exactly where "Latitude
        // 0.0000 deg" would be read as a measurement.
        val r = CayenneLpp.parse(gps(0, 0, 0))
        assertEquals(1, r.size)
        assertEquals("Altitude", r[0].label)
    }

    @Test
    fun cayenneGpsKeepsAFixThatIsZeroInOnlyOneAxis() {
        // Zero latitude with a real longitude is a place — the equator
        // runs through inhabited land. Only BOTH being zero is "unset",
        // which is the same rule isPlausiblePosition applies everywhere
        // else, and the reason it is a shared function and not a local
        // `== 0.0` at each call site.
        val onlyLat = CayenneLpp.parse(gps(0, -856_681, 0))
        assertEquals(3, onlyLat.size)
        assertEquals(0.0, onlyLat[0].value, 1e-9)

        val onlyLon = CayenneLpp.parse(gps(429_634, 0, 0))
        assertEquals(3, onlyLon.size)
        assertEquals(0.0, onlyLon[1].value, 1e-9)
    }

    @Test
    fun cayenneGpsAltitudeSurvivesTheMissingFix() {
        // Altitude is a separate sensor reading on the same frame and
        // has nothing to do with the fix: withholding it too would lose
        // real data to fix a presentation problem.
        val r = CayenneLpp.parse(gps(0, 0, 25_000))
        assertEquals(1, r.size)
        assertEquals(250.0, r[0].value, 1e-9)
        assertEquals("m", r[0].unit)
    }
}
