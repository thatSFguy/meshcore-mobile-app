package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The path discovery reply, laid out the way the firmware writes it
 * (companion_radio/MyMesh.cpp:793-801, Packet.cpp:20-30) — not the way
 * our own parser reads it.
 */
class PathDiscoveryTest {

    private val prefix = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)

    private fun frame(vararg tail: Int): ByteArray =
        byteArrayOf(0x8D.toByte(), 0) + prefix + ByteArray(tail.size) { tail[it].toByte() }

    @Test
    fun `both routes are read at their own hash widths`() {
        // Out: 0x42 = 2 hops × 2 bytes. In: 0x03 = 3 hops × 1 byte.
        val r = assertNotNull(PathDiscovery.parse(frame(0x42, 0xB3, 0x89, 0xC9, 0x85, 0x03, 1, 2, 3)))
        assertEquals("0a0b0c0d0e0f", r.keyPrefixHex)
        assertContentEquals(byteArrayOf(0xB3.toByte(), 0x89.toByte(), 0xC9.toByte(), 0x85.toByte()), r.outPath)
        assertEquals(2, r.outHashWidth)
        assertEquals(2, r.outHops)
        assertContentEquals(byteArrayOf(1, 2, 3), r.inPath)
        assertEquals(1, r.inHashWidth)
        assertEquals(3, r.inHops)
    }

    @Test
    fun `a direct node has two empty routes`() {
        // A zero-hop path at width 2 is 0x40, not 0 — the hop-hash-width trap.
        val r = assertNotNull(PathDiscovery.parse(frame(0x40, 0x40)))
        assertEquals(0, r.outHops)
        assertEquals(0, r.inHops)
        assertEquals(2, r.outHashWidth)
    }

    @Test
    fun `a three byte width is read as three`() {
        val r = assertNotNull(PathDiscovery.parse(frame(0x81, 1, 2, 3, 0x00)))
        assertEquals(3, r.outHashWidth)
        assertEquals(1, r.outHops)
    }

    @Test
    fun `short frames are refused rather than half read`() {
        assertNull(PathDiscovery.parse(ByteArray(0)))
        assertNull(PathDiscovery.parse(byteArrayOf(0x8D.toByte(), 0, 1, 2)))
        // Out length says 2 bytes; only one follows.
        assertNull(PathDiscovery.parse(frame(0x02, 0x11)))
        // Out route complete, in-route length byte missing.
        assertNull(PathDiscovery.parse(frame(0x01, 0x11)))
        // In length says 3 bytes; two follow.
        assertNull(PathDiscovery.parse(frame(0x00, 0x03, 1, 2)))
    }

    @Test
    fun `lengths the firmware itself refuses are refused`() {
        // Width 4 (top bits 11) is reserved (Packet.cpp:16) — and 0xFF,
        // the flood sentinel, is one of them.
        assertNull(PathDiscovery.parse(frame(0xC1, 1, 2, 3, 4, 0x00)))
        assertNull(PathDiscovery.parse(frame(0xFF, 0x00)))
        // 63 hops × 3 bytes = 189 > MAX_PATH_SIZE 64, even with the bytes present.
        assertNull(PathDiscovery.parse(frame(0xBF, *IntArray(189) { 1 }, 0x00)))
    }

    @Test
    fun `the largest legal route is accepted`() {
        // 32 hops × 2 bytes = exactly 64. Positive control for the cap above.
        val r = assertNotNull(PathDiscovery.parse(frame(0x60, *IntArray(64) { it }, 0x00)))
        assertEquals(32, r.outHops)
    }

    @Test
    fun `another push code is not a path discovery`() {
        val wrong = frame(0x00, 0x00).also { it[0] = Codes.PUSH_CODE_BINARY_RESPONSE.toByte() }
        assertNull(PathDiscovery.parse(wrong))
    }

    @Test
    fun `the response parser turns the push into an event and junk into Unknown`() {
        val ok = ResponseParser.parse(frame(0x01, 0x22, 0x00))
        assertEquals("0a0b0c0d0e0f", (ok as DeviceEvent.PathDiscovered).result.keyPrefixHex)
        val junk = ResponseParser.parse(byteArrayOf(0x8D.toByte(), 0, 1))
        assertEquals(Codes.PUSH_CODE_PATH_DISCOVERY_RESPONSE, (junk as DeviceEvent.Unknown).code)
    }

    @Test
    fun `the request is built the way the firmware reads it`() {
        // [52][0][pubkey x32] — MyMesh.cpp:1651 requires cmd_frame[1] == 0
        // and reads the key from offset 2.
        val key = ByteArray(32) { (it * 3).toByte() }
        val f = Frames.sendPathDiscovery(key)
        assertEquals(34, f.size)
        assertEquals(52, f[0].toInt())
        assertEquals(0, f[1].toInt())
        assertContentEquals(key, f.copyOfRange(2, 34))
        assertFailsWith<IllegalArgumentException> { Frames.sendPathDiscovery(ByteArray(31)) }
    }
}
