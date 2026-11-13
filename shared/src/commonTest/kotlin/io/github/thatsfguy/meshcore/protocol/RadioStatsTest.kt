package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * CMD_GET_STATS / RESP_CODE_STATS for the connected radio (issue #6).
 *
 * Frames are written byte by byte from the firmware's WRITER
 * (`examples/companion_radio/MyMesh.cpp`, the CMD_GET_STATS branch) and the
 * builder is checked against its READER — never against our own parser.
 */
class RadioStatsTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xFF).toByte() }

    /** memcpy noise_floor(2) · last_rssi(1) · last_snr(1) · tx(4) · rx(4). */
    private fun radioFrame(noise: Int, rssi: Int, snrQuarters: Int, tx: Long, rx: Long) =
        byteArrayOf(Codes.RESP_CODE_STATS.toByte(), Codes.STATS_TYPE_RADIO.toByte()) +
            le16(noise) + byteArrayOf(rssi.toByte(), snrQuarters.toByte()) + le32(tx) + le32(rx)

    private fun stats(frame: ByteArray): DeviceEvent.Stats =
        assertIs<DeviceEvent.Stats>(ResponseParser.parse(frame))

    @Test
    fun theRequestIsTheCommandAndTheSubtypeByte() {
        // Firmware reader: `cmd_frame[0] == CMD_GET_STATS && len >= 2`,
        // `stats_type = cmd_frame[1]`.
        assertContentEquals(byteArrayOf(56, 1), Frames.getStats(Codes.STATS_TYPE_RADIO))
        assertContentEquals(byteArrayOf(56, 2), Frames.getStats(Codes.STATS_TYPE_PACKETS))
    }

    @Test
    fun aRadioFrameDecodesWithSignedFields() {
        // Every field that can be negative is: a real noise floor, RSSI and
        // a weak SNR. Read unsigned, -112 dBm would be 65424.
        val e = stats(radioFrame(noise = -112, rssi = -87, snrQuarters = -22, tx = 3_600, rx = 7_200))
        val r = RadioStats.parseRadio(e.statsType, e.payload)!!
        assertEquals(-112, r.noiseFloorDbm)
        assertEquals(-87, r.lastRssiDbm)
        assertEquals(-5.5, r.lastSnrDb)
        assertEquals(3_600, r.txAirSecs)
        assertEquals(7_200, r.rxAirSecs)
    }

    @Test
    fun airtimeIsUnsigned() {
        val e = stats(radioFrame(-110, -90, 20, tx = 0xFFFFFFFFL, rx = 0x80000000L))
        val r = RadioStats.parseRadio(e.statsType, e.payload)!!
        assertEquals(4_294_967_295L, r.txAirSecs)
        assertEquals(2_147_483_648L, r.rxAirSecs)
    }

    @Test
    fun aPacketsFrameDecodesAllSevenCounters() {
        val counters = listOf(1204L, 37L, 30L, 7L, 1100L, 104L, 3L)
        val frame = byteArrayOf(Codes.RESP_CODE_STATS.toByte(), Codes.STATS_TYPE_PACKETS.toByte()) +
            counters.fold(ByteArray(0)) { acc, v -> acc + le32(v) }
        val e = stats(frame)
        val p = RadioStats.parsePackets(e.statsType, e.payload)!!
        assertEquals(RadioStats.Packets(1204, 37, 30, 7, 1100, 104, 3), p)
    }

    @Test
    fun theWrongSubtypeIsNotReadAsThisOne() {
        val e = stats(radioFrame(-112, -87, 26, 1, 1))
        assertNull(RadioStats.parsePackets(e.statsType, e.payload))
        // A CORE frame (11 bytes) offered as RADIO.
        assertNull(RadioStats.parseRadio(Codes.STATS_TYPE_CORE, e.payload))
    }

    @Test
    fun truncatedPayloadsAreRefused() {
        val full = stats(radioFrame(-112, -87, 26, 1, 1)).payload
        for (n in 0 until RadioStats.RADIO_PAYLOAD_SIZE) {
            assertNull(RadioStats.parseRadio(Codes.STATS_TYPE_RADIO, full.copyOf(n)), "length $n")
        }
        assertNull(RadioStats.parsePackets(Codes.STATS_TYPE_PACKETS, ByteArray(27)))
    }
}
