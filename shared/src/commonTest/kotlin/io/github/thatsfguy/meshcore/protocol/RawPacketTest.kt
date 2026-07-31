package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RawPacketTest {

    @Test
    fun parsesDirectPacketWithoutTransportBytes() {
        // route_type=1 (direct, no transport bytes), payload_type=GRP_TXT(5), ver=0
        val header = (0 shl 6) or (Codes.PAYLOAD_TYPE_GRP_TXT shl 2) or 0x01
        val raw = byteArrayOf(
            header.toByte(),
            0x02,             // path: width 1, 2 hops
            0x11, 0x22,
            0x42, 0x43,       // payload
        )
        val p = RawPacket.parse(raw)!!
        assertEquals(Codes.PAYLOAD_TYPE_GRP_TXT, p.payloadType)
        assertEquals(2, p.hopCount)
        assertEquals(1, p.pathHashWidth)
        assertContentEquals(byteArrayOf(0x11, 0x22), p.pathBytes)
        assertContentEquals(byteArrayOf(0x42, 0x43), p.payload)
    }

    @Test
    fun skipsTransportBytesForTransportRoutes() {
        // route_type=0 (transport flood) → 4 transport bytes follow header
        val header = (Codes.PAYLOAD_TYPE_ADVERT shl 2) or 0x00
        val raw = byteArrayOf(
            header.toByte(),
            9, 9, 9, 9,       // transport bytes
            0x00,             // no path
            0x55,             // payload
        )
        val p = RawPacket.parse(raw)!!
        assertEquals(Codes.PAYLOAD_TYPE_ADVERT, p.payloadType)
        assertEquals(0, p.hopCount)
        assertContentEquals(byteArrayOf(0x55), p.payload)
    }

    @Test
    fun multiByteHashWidthPath() {
        val header = (Codes.PAYLOAD_TYPE_TXT_MSG shl 2) or 0x01
        // path byte 0x42: width = ((0x42 & 0xC0) >> 6) + 1 = 2, hops = 2 → 4 path bytes
        val raw = byteArrayOf(header.toByte(), 0x42, 1, 2, 3, 4, 0x7F)
        val p = RawPacket.parse(raw)!!
        assertEquals(2, p.pathHashWidth)
        assertEquals(2, p.hopCount)
        assertEquals(4, p.pathBytes.size)
    }

    @Test
    fun truncatedPacketReturnsNull() {
        val header = (Codes.PAYLOAD_TYPE_GRP_TXT shl 2) or 0x01
        assertNull(RawPacket.parse(byteArrayOf(header.toByte(), 0x05, 0x11))) // claims 5 hops
        assertNull(RawPacket.parse(ByteArray(0)))
    }
}
