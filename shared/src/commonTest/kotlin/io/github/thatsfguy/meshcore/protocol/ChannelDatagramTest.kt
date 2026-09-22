package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Channel data datagrams — `CMD_SEND_CHANNEL_DATA` (0x3E) out,
 * `RESP_CODE_CHANNEL_DATA_RECV` (0x1B) in.
 *
 * Written against the firmware's own `docs/companion_protocol.md`,
 * which gives both frame layouts and the exact refusal conditions, so
 * these assert what the RADIO will accept rather than what our builder
 * happens to emit — the distinction that the trace flags and the
 * neighbours request were both got wrong on.
 */
class ChannelDatagramTest {

    // ---- sending ---------------------------------------------------------

    @Test
    fun theSendFrameMatchesTheDocumentedLayout() {
        // 3E 01 FF FF FF A1 B2 C3 — the doc's own worked example:
        // channel 1, flood, DATA_TYPE_DEV (0xFFFF), payload A1 B2 C3.
        val f = Frames.sendChannelData(1, Codes.DATA_TYPE_DEV, byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()))
        assertNotNull(f)
        assertContentEquals(
            byteArrayOf(0x3E, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()),
            f,
        )
    }

    @Test
    fun theDataTypeIsLittleEndianLikeEveryOtherFieldHere() {
        // 0xFF00 must go out as 00 FF. Big-endian would name a different
        // application entirely — 0x00FF is "reserved for internal use".
        val f = Frames.sendChannelData(0, 0xFF00, byteArrayOf(1))!!
        assertEquals(0x00, f[3].toInt() and 0xFF)
        assertEquals(0xFF, f[4].toInt() and 0xFF)
    }

    @Test
    fun aReservedDataTypeIsRefusedBeforeItReachesTheAir() {
        // The firmware answers ERR_CODE_ILLEGAL_ARG; finding that out
        // from an error push is strictly worse than not sending.
        assertNull(Frames.sendChannelData(0, Codes.DATA_TYPE_RESERVED, byteArrayOf(1)))
    }

    @Test
    fun anOversizedPayloadIsRefusedRatherThanTruncated() {
        // MAX_CHANNEL_DATA_LENGTH = MAX_FRAME_SIZE - 9 = 163. Truncating
        // would hand another application a short read it cannot detect.
        assertEquals(163, Codes.MAX_CHANNEL_DATA_LENGTH)
        assertNotNull(Frames.sendChannelData(0, 0xFF00, ByteArray(163) { 7 }))
        assertNull(Frames.sendChannelData(0, 0xFF00, ByteArray(164) { 7 }))
    }

    @Test
    fun anEmptyPayloadAndABadChannelAreBothRefused() {
        assertNull(Frames.sendChannelData(0, 0xFF00, ByteArray(0)))
        assertNull(Frames.sendChannelData(8, 0xFF00, byteArrayOf(1)))
        assertNull(Frames.sendChannelData(-1, 0xFF00, byteArrayOf(1)))
    }

    // ---- receiving -------------------------------------------------------

    private fun frame(
        snr: Int = 20,
        channel: Int = 1,
        pathLen: Int = 0x42,
        dataType: Int = 0xFF00,
        payload: ByteArray = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
        declaredLen: Int? = null,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CHANNEL_DATA_RECV)
        w.writeByte(snr)
        w.writeByte(0) // reserved
        w.writeByte(0) // reserved
        w.writeByte(channel)
        w.writeByte(pathLen)
        w.writeByte(dataType and 0xFF)
        w.writeByte((dataType shr 8) and 0xFF)
        w.writeByte(declaredLen ?: payload.size)
        w.writeBytes(payload)
        return w.toBytes()
    }

    @Test
    fun aReceivedDatagramParsesEveryField() {
        val e = assertIs<DeviceEvent.ChannelDatagram>(ResponseParser.parse(frame()))
        assertEquals(1, e.channelIndex)
        assertEquals(0xFF00, e.dataType)
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), e.payload)
        assertEquals(5.0, e.snr)
        // 0x42 = width mode 1, 2 hops — a flooded arrival states a count.
        assertEquals(2, e.hops)
    }

    @Test
    fun aRoutedDatagramReportsNoHopCount() {
        // 0xFF on a RECEIVE means it was NOT flooded. Reading it as a
        // hop count is the inversion fixed in 0.9.3.
        val e = assertIs<DeviceEvent.ChannelDatagram>(ResponseParser.parse(frame(pathLen = 0xFF)))
        assertEquals(PathCodec.HOPS_ROUTED, e.hops)
    }

    @Test
    fun aDeclaredLengthLongerThanTheFrameCannotOverRead() {
        // Attacker-influenced: the frame says 200 bytes and carries 2.
        val e = assertIs<DeviceEvent.ChannelDatagram>(
            ResponseParser.parse(frame(declaredLen = 200)),
        )
        assertEquals(2, e.payload.size)
    }

    @Test
    fun aDeclaredLengthShorterThanTheFrameDoesNotSmuggleTrailingBytes() {
        // Says 1, carries 2: the second byte is not part of the payload
        // and must not be handed to an application as though it were.
        val e = assertIs<DeviceEvent.ChannelDatagram>(
            ResponseParser.parse(frame(declaredLen = 1)),
        )
        assertEquals(1, e.payload.size)
        assertEquals(0xDE, e.payload[0].toInt() and 0xFF)
    }

    @Test
    fun aTruncatedFrameDoesNotThrow() {
        for (n in 1..8) {
            val short = frame().copyOfRange(0, n)
            // Anything at all, as long as it is not an exception.
            assertTrue(ResponseParser.parse(short) is DeviceEvent)
        }
    }

    @Test
    fun anEmptyPayloadIsParsedRatherThanRejected() {
        // The radio should not send one, but a zero-length datagram is
        // not a reason to drop the frame on the floor.
        val e = assertIs<DeviceEvent.ChannelDatagram>(
            ResponseParser.parse(frame(payload = ByteArray(0))),
        )
        assertEquals(0, e.payload.size)
    }
}
