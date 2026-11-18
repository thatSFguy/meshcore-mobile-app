package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveredBookTest {

    private var clock = 1_000L
    private fun book() = DiscoveredBook({ clock })
    private val bob = "bb".repeat(32)

    @Test
    fun theFewestHopsAndADirectHearingAreKept() {
        val b = book()
        b.heard(bob, "Bob", Codes.ADV_TYPE_CHAT, null, null, 9.5, -80, ByteArray(4) { 1 }, hops = 0)
        clock += 60_000
        b.heard(bob, "Bob", Codes.ADV_TYPE_CHAT, null, null, -3.0, -110, ByteArray(4) { 2 }, hops = 3)
        val n = b.get(bob)!!
        assertEquals(0, n.minHops, "one flood copy via three repeaters doesn't undo hearing it direct")
        assertEquals(9.5, n.directSnr)
        assertEquals(1_000L, n.firstHeardAt)
        assertEquals(61_000L, n.lastHeardAt)
        assertEquals("02020202", n.advertHex, "the latest signed advert is what Add writes")
    }

    @Test
    fun dismissedAndHeldNodesLeave() {
        val b = book()
        b.heard(bob, "Bob", 1, null, null, 1.0, -90, ByteArray(1), null)
        b.heard("cc".repeat(32), "Cy", 2, null, null, 1.0, -90, ByteArray(1), null)
        b.dismiss(bob.uppercase())
        assertNull(b.get(bob))
        b.forgetHeld(listOf("CC".repeat(32)))
        assertTrue(b.nodes.value.isEmpty())
    }

    @Test
    fun theInboxIsCappedAndNewestFirst() {
        val b = book()
        repeat(DiscoveredBook.MAX + 5) {
            clock += 1
            b.heard(it.toString(16).padStart(64, '0'), "n$it", 1, null, null, 0.0, 0, ByteArray(1), null)
        }
        assertEquals(DiscoveredBook.MAX, b.nodes.value.size)
        assertEquals("n${DiscoveredBook.MAX + 4}", b.nodes.value.first().name)
    }

    @Test
    fun itRoundTrips() {
        val b = book()
        b.heard(bob, "Bob", 1, 42.9, -85.6, 1.0, -90, ByteArray(2) { 7 }, 1)
        assertEquals(b.nodes.value, DiscoveredBook.fromJson(b.toJson()) { 0 }.nodes.value)
        assertTrue(DiscoveredBook.fromJson("nope") { 0 }.nodes.value.isEmpty())
    }
}
