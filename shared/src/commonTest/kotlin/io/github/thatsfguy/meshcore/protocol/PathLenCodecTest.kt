package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.model.Contact
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `path_len` byte is PACKED — low 6 bits hops, top 2 bits the
 * hash-width mode — not a byte count. Reading it as a length is what
 * turned a 4-hop contact into "34 hops away" on the contact sheet.
 */
class PathLenCodecTest {

    // ---- the regression that prompted this ------------------------------

    @Test
    fun `a real 4-hop 2-byte contact decodes as 4 hops not 34`() {
        // 0x44 = 0b01_000100: mode 1 (2-byte hashes), 4 hops.
        // Captured from a live contact the mainstream app showed as
        // "4 hops" with out path b389,c985,6e4d,8eaa (8 bytes).
        val info = PathCodec.decodePathLen(0x44)
        assertEquals(4, info.hops)
        assertEquals(2, info.hashWidth)
        assertEquals(8, info.byteLength)
        assertFalse(info.isFlood)
        // The old reading: 68 bytes / 2 per hop = 34.
        assertTrue(info.hops != 34)
    }

    @Test
    fun `a direct contact is zero hops with no path bytes`() {
        val info = PathCodec.decodePathLen(0x00)
        assertEquals(0, info.hops)
        assertEquals(0, info.byteLength)
        assertFalse(info.isFlood)
    }

    // ---- decode ---------------------------------------------------------

    @Test
    fun `flood is recognised as 0xFF and as a signed -1`() {
        for (raw in listOf(0xFF, -1)) {
            val info = PathCodec.decodePathLen(raw)
            assertTrue(info.isFlood, "raw $raw not flood")
            assertEquals(-1, info.hops)
            assertEquals(0, info.byteLength)
        }
    }

    @Test
    fun `every hash-width mode decodes to its width`() {
        for (mode in 0..3) {
            val raw = (mode shl 6) or 3
            val info = PathCodec.decodePathLen(raw)
            assertEquals(mode + 1, info.hashWidth, "mode $mode")
            assertEquals(3, info.hops)
            assertEquals(3 * (mode + 1), info.byteLength)
        }
    }

    @Test
    fun `hop count uses only the low six bits`() {
        // 0x3F is the largest representable hop count; the width bits
        // must not leak into it.
        assertEquals(0x3F, PathCodec.decodePathLen(0x3F).hops)
        assertEquals(0x3F, PathCodec.decodePathLen(0xBF).hops)   // mode 2
        assertEquals(3, PathCodec.decodePathLen(0xC0 or 3).hops) // mode 3
    }

    // ---- encode ---------------------------------------------------------

    @Test
    fun `encode packs hops and width the way the firmware reads them`() {
        assertEquals(0x44, PathCodec.encodePathLen(hops = 4, hashWidth = 2))
        assertEquals(0x00, PathCodec.encodePathLen(hops = 0, hashWidth = 1))
        assertEquals(0xC1, PathCodec.encodePathLen(hops = 1, hashWidth = 4))
    }

    @Test
    fun `encode maps a negative hop count to flood`() {
        assertEquals(PathCodec.PATH_LEN_FLOOD, PathCodec.encodePathLen(-1, 2))
    }

    @Test
    fun `encode clamps an out-of-range width instead of corrupting hops`() {
        // Width 0 or 9 is nonsense; it must not spill into the hop bits.
        assertEquals(4, PathCodec.decodePathLen(PathCodec.encodePathLen(4, 0)).hops)
        assertEquals(4, PathCodec.decodePathLen(PathCodec.encodePathLen(4, 9)).hops)
    }

    @Test
    fun `encode and decode round trip across every representable path`() {
        for (width in 1..4) {
            for (hops in 0..PathCodec.maxHopsFor(width)) {
                val info = PathCodec.decodePathLen(PathCodec.encodePathLen(hops, width))
                assertEquals(hops, info.hops, "hops $hops width $width")
                assertEquals(width, info.hashWidth, "hops $hops width $width")
            }
        }
    }

    @Test
    fun `encoding never accidentally produces the flood sentinel`() {
        // 63 hops at 4-byte hashes packs to 0xFF, which the firmware
        // reads as "no path" — a pinned route would silently become a
        // flood. The encoder caps instead.
        for (width in 1..4) {
            for (hops in 0..0x3F) {
                val raw = PathCodec.encodePathLen(hops, width)
                assertTrue(
                    raw != PathCodec.PATH_LEN_FLOOD,
                    "hops $hops width $width encoded to the flood sentinel",
                )
            }
        }
    }

    @Test
    fun `hop limits keep the path inside the record's 64-byte buffer`() {
        for (width in 1..4) {
            assertTrue(PathCodec.maxHopsFor(width) * width <= 64, "width $width")
        }
    }

    // ---- Contact accessors ----------------------------------------------

    private fun contact(pathLen: Int, path: ByteArray) = Contact(
        publicKey = ByteArray(32) { 1 },
        type = Codes.ADV_TYPE_CHAT,
        flags = 0,
        pathLen = pathLen,
        path = path,
        name = "n",
        timestamp = 0,
        latitude = null,
        longitude = null,
        lastModified = 0,
    )

    @Test
    fun `storedPath returns only the real route bytes`() {
        // 64-byte buffer, 4 hops x 2 bytes = the first 8 bytes.
        val buffer = ByteArray(64) { (it + 1).toByte() }
        val c = contact(0x44, buffer)
        assertEquals(8, c.storedPath.size)
        assertContentEquals(buffer.copyOfRange(0, 8), c.storedPath)
    }

    @Test
    fun `storedPath is empty for a flooded or direct contact`() {
        val buffer = ByteArray(64) { 7 }
        assertEquals(0, contact(PathCodec.PATH_LEN_FLOOD, buffer).storedPath.size)
        assertEquals(0, contact(0x00, buffer).storedPath.size)
    }

    @Test
    fun `storedPath cannot read past a short buffer`() {
        // A truncated record must not throw on the render path.
        val c = contact(0x44, ByteArray(3) { 9 })
        assertEquals(3, c.storedPath.size)
    }

    @Test
    fun `formatHops groups bytes by the contact's own hash width`() {
        val path = byteArrayOf(
            0xb3.toByte(), 0x89.toByte(), 0xc9.toByte(), 0x85.toByte(),
            0x6e, 0x4d, 0x8e.toByte(), 0xaa.toByte(),
        )
        assertEquals("b389 c985 6e4d 8eaa", PathCodec.formatHops(path, hashWidth = 2))
        assertEquals("b3 89 c9 85 6e 4d 8e aa", PathCodec.formatHops(path, hashWidth = 1))
    }

    @Test
    fun rawPathLenIsNeverAByteLength() {
        // Regression: path history recorded `copyOfRange(0, pathLen)`,
        // treating the RAW packed byte as a length. A direct contact at
        // 2-byte hashes packs to 0x40 = 64, so the whole zero-padded
        // 64-byte buffer became a "64 hop" route the routing sheet
        // offered you to pin. Seen on a real radio.
        val directAt2Bytes = PathCodec.encodePathLen(0, 2)
        assertEquals(0x40, directAt2Bytes)
        val info = PathCodec.decodePathLen(directAt2Bytes)
        assertEquals(0, info.hops, "a direct contact has no hops")
        assertEquals(0, info.byteLength, "and therefore no path bytes")
        // The raw byte says 64; the decoded length says 0. Anything
        // reading the raw byte as a length is wrong by 64 bytes.
        assertTrue(directAt2Bytes != info.byteLength)
    }
}
