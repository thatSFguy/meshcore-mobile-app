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

    @Test
    fun onlyAFarNodeIsWarnedAbout() {
        val me = io.github.thatsfguy.meshcore.presentation.SelfPosition(42.96, -85.67)
        val metric = io.github.thatsfguy.meshcore.presentation.UnitSystem.Metric
        val b = book()
        b.heard(bob, "Bob", 1, 42.97, -85.67, 1.0, -90, ByteArray(1), hops = 1)
        assertNull(DiscoveredBook.addWarning(b.get(bob)!!, me, metric), "near and one hop: just add it")
        b.heard("cc".repeat(32), "Cy", 1, null, null, 1.0, -90, ByteArray(1), hops = 5)
        val hops = DiscoveredBook.addWarning(b.get("cc".repeat(32))!!, me, metric)!!
        assertTrue(hops.startsWith("This node is 5 hops, and nothing shows it can hear you"), hops)
        // ~160 km north: far by distance alone, on the hop count of a near one.
        b.heard("dd".repeat(32), "Di", 1, 44.40, -85.67, 1.0, -90, ByteArray(1), hops = 1)
        val distant = DiscoveredBook.addWarning(b.get("dd".repeat(32))!!, me, metric)!!
        assertTrue(distant.contains("km away"), distant)
    }

    @Test
    fun theAddHintMentionsAdministeringOnlyForInfrastructure() {
        assertTrue("administering" !in DiscoveredBook.addHint(Codes.ADV_TYPE_CHAT))
        assertTrue("administering" in DiscoveredBook.addHint(Codes.ADV_TYPE_REPEATER))
    }

    @Test
    fun theKindLineNamesEachTypeAndAnUnknownOneWhole() {
        assertEquals("Room server · heard, not added", DiscoveredBook.kindLine(Codes.ADV_TYPE_ROOM))
        assertEquals("Node · heard, not added", DiscoveredBook.kindLine(9), "not \"Othe\"")
    }

    private fun held(seed: Int, name: String) = io.github.thatsfguy.meshcore.model.Contact(
        publicKey = ByteArray(32) { (seed + it).toByte() }, type = Codes.ADV_TYPE_REPEATER, flags = 0,
        pathLen = 0, path = ByteArray(64), name = name, timestamp = 1, latitude = null, longitude = null, lastModified = 1,
    )

    @Test
    fun discoveryListsTheHeldRepeatersThatAnsweredByName() {
        val ridge = held(0x10, "Ridge")
        val mill = held(0x40, "Mill")
        val d = DiscoveredBook.discoveryOf(setOf(ridge.publicKeyHex.take(8), mill.publicKeyHex.take(8), "deadbeef"), listOf(ridge, mill))
        assertEquals(listOf("Mill", "Ridge"), d.answered.map { it.name })
        assertEquals("2 answered: Mill, Ridge — and 1 not in your contacts.", d.message)
        assertTrue(DiscoveredBook.discoveryOf(emptySet(), listOf(ridge)).answered.isEmpty())
    }
}
