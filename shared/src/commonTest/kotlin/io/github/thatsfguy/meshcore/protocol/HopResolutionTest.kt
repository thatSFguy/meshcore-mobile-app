package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Naming the hops of a stored path.
 *
 * A hop is a truncated key hash — two bytes in the common case, so 16
 * bits. Several nodes can share one honestly, and a hostile node can
 * manufacture a collision cheaply, so resolution must never silently
 * pick a winner.
 */
class HopResolutionTest {

    private val contacts = mapOf(
        "b389548d314a0000000000000000000000000000000000000000000000000000" to "SpartaMI",
        "c985cffd466e0000000000000000000000000000000000000000000000000000" to "KCEST-GRR-BYRONCTR-01",
        "6e4d000000000000000000000000000000000000000000000000000000000000" to "Comstock Plat",
    )

    private fun path(vararg bytes: Int) = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun `a hop with one match is named`() {
        val hops = PathCodec.resolveHops(path(0xb3, 0x89), 2, contacts)
        assertEquals(1, hops.size)
        assertEquals("b389", hops[0].hashHex)
        assertTrue(hops[0].isResolved)
        assertEquals("b389 SpartaMI", hops[0].label)
    }

    @Test
    fun `a hop with no match stays a bare hash`() {
        val hops = PathCodec.resolveHops(path(0xde, 0xad), 2, contacts)
        assertFalse(hops[0].isResolved)
        assertFalse(hops[0].isAmbiguous)
        assertEquals("dead", hops[0].label)
        assertTrue(hops[0].candidates.isEmpty())
    }

    @Test
    fun `a colliding hop is reported as ambiguous, never guessed`() {
        val colliding = contacts + mapOf(
            "b389ffffffff0000000000000000000000000000000000000000000000000000" to "Impostor",
        )
        val hop = PathCodec.resolveHops(path(0xb3, 0x89), 2, colliding).single()
        assertTrue(hop.isAmbiguous)
        assertFalse(hop.isResolved)
        assertEquals(2, hop.candidates.size)
        assertTrue(hop.candidates.contains("SpartaMI"))
        assertTrue(hop.candidates.contains("Impostor"))
        // The label must not assert either identity.
        assertEquals("b389 (2 matches)", hop.label)
        assertFalse(hop.label.contains("SpartaMI"))
    }

    @Test
    fun `resolves a full multi-hop path in order`() {
        val hops = PathCodec.resolveHops(
            path(0xb3, 0x89, 0xc9, 0x85, 0x6e, 0x4d, 0x8e, 0xaa),
            hashWidth = 2,
            contactsByKeyHex = contacts,
        )
        assertEquals(4, hops.size)
        assertEquals(listOf("b389", "c985", "6e4d", "8eaa"), hops.map { it.hashHex })
        assertEquals("SpartaMI", hops[0].candidates.single())
        assertEquals("KCEST-GRR-BYRONCTR-01", hops[1].candidates.single())
        assertEquals("Comstock Plat", hops[2].candidates.single())
        assertTrue(hops[3].candidates.isEmpty())
    }

    @Test
    fun `honours the hash width when splitting`() {
        val oneByte = PathCodec.resolveHops(path(0xb3, 0x89), 1, contacts)
        assertEquals(listOf("b3", "89"), oneByte.map { it.hashHex })

        val fourByte = PathCodec.resolveHops(path(0xb3, 0x89, 0x54, 0x8d), 4, contacts)
        assertEquals(listOf("b389548d"), fourByte.map { it.hashHex })
        assertEquals("SpartaMI", fourByte[0].candidates.single())
    }

    @Test
    fun `matches contact keys case-insensitively`() {
        val upper = mapOf(
            "B389548D314A0000000000000000000000000000000000000000000000000000" to "SpartaMI",
        )
        assertTrue(PathCodec.resolveHops(path(0xb3, 0x89), 2, upper).single().isResolved)
    }

    @Test
    fun `an unnamed contact falls back to its key rather than an empty label`() {
        val unnamed = mapOf(
            "b389548d314a0000000000000000000000000000000000000000000000000000" to "",
        )
        val hop = PathCodec.resolveHops(path(0xb3, 0x89), 2, unnamed).single()
        assertTrue(hop.isResolved)
        assertTrue(hop.label.startsWith("b389 b389548d314a"))
    }

    @Test
    fun `an empty path resolves to no hops`() {
        assertTrue(PathCodec.resolveHops(ByteArray(0), 2, contacts).isEmpty())
    }

    @Test
    fun `a trailing partial hop is still reported`() {
        // 3 bytes at width 2: don't drop the odd byte silently.
        val hops = PathCodec.resolveHops(path(0xb3, 0x89, 0xc9), 2, contacts)
        assertEquals(2, hops.size)
        assertEquals("c9", hops[1].hashHex)
    }

    @Test
    fun `an out-of-range width is clamped instead of throwing`() {
        for (width in listOf(0, -1, 9)) {
            PathCodec.resolveHops(path(0xb3, 0x89), width, contacts)
        }
    }
}
