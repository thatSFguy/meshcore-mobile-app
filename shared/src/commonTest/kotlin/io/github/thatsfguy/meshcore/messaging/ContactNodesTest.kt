package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.presentation.NodeListModel
import io.github.thatsfguy.meshcore.presentation.NodeTab
import io.github.thatsfguy.meshcore.presentation.Reachability
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactNodesTest {

    private val now = 1_700_000_000L

    private fun contact(
        seed: Int,
        name: String,
        type: Int = Codes.ADV_TYPE_CHAT,
        pathLen: Int = 0xFF,
        advertAt: Long = now - 60,
        lastMod: Long = now - 60,
        flags: Int = 0,
        lat: Double? = null,
        lon: Double? = null,
    ) = Contact(
        publicKey = ByteArray(32) { (seed + it).toByte() },
        type = type, flags = flags, pathLen = pathLen, path = ByteArray(64),
        name = name, timestamp = advertAt, latitude = lat, longitude = lon,
        lastModified = lastMod,
    )

    @Test
    fun hopsAreReadAtTheContactsOwnWidth() {
        // 0x44 is a real captured path_len from a live contact: 2-byte
        // hashes, FOUR hops. Read as a length it is 68 — the "34 hops"
        // bug's shape (CLAUDE.md: the hop-hash width is the recurring
        // defect).
        assertEquals("4 hops", ContactNodes.build(listOf(contact(1, "a", pathLen = 0x44)), null, now)[0].hopsLabel)
        assertEquals("direct", ContactNodes.build(listOf(contact(1, "a", pathLen = 0x40)), null, now)[0].hopsLabel)
        assertEquals("1 hop", ContactNodes.build(listOf(contact(1, "a", pathLen = 0x01)), null, now)[0].hopsLabel)
        assertEquals("flood", ContactNodes.build(listOf(contact(1, "a", pathLen = 0xFF)), null, now)[0].hopsLabel)
    }

    @Test
    fun aDeliveredMessageIsTheProofOfTwoWay() {
        val c = contact(1, "Al")
        var clock = now * 1000
        val book = MessageBook(nowMs = { clock })
        val id = book.addOutgoing("dm|${c.publicKeyHex}", null, "hi", now)
        clock += 5_000
        book.updateStatus(id, MessageStatus.Delivered)
        val node = ContactNodes.build(listOf(c), book, now + 10).single()
        assertEquals(Reachability.State.TwoWay, node.reach.state)
    }

    @Test
    fun anUnansweredSendIsNotTwoWay() {
        val c = contact(1, "Al")
        val book = MessageBook(nowMs = { now * 1000 })
        val id = book.addOutgoing("dm|${c.publicKeyHex}", null, "hi", now)
        book.updateStatus(id, MessageStatus.Failed)
        assertEquals(Reachability.State.HeardOnly, ContactNodes.build(listOf(c), book, now).single().reach.state)
    }

    @Test
    fun aContactAddedByHandWasNeverHeard() {
        // Added from a QR: zero advert timestamp, no route, no traffic.
        val c = contact(1, "Al", advertAt = 0)
        assertEquals(Reachability.State.Unknown, ContactNodes.build(listOf(c), null, now).single().reach.state)
    }

    @Test
    fun aStoredRouteMeansTheyHeardUs() {
        val c = contact(1, "Al", pathLen = 0x42)
        assertEquals(Reachability.State.TheyHeardYou, ContactNodes.build(listOf(c), null, now).single().reach.state)
    }

    @Test
    fun unreadAndLastMessageComeFromTheBook() {
        val c = contact(1, "Al")
        val book = MessageBook(nowMs = { 42_000 })
        book.receive("dm|${c.publicKeyHex}", false, null, "yo", now, "k")
        val node = ContactNodes.build(listOf(c), book, now).single()
        assertEquals(1, node.unread)
        assertEquals(42_000, node.lastMessageAt)
    }

    @Test
    fun tabsSplitByTypeAndTheModelSortsWithin() {
        val nodes = ContactNodes.build(
            listOf(
                contact(1, "zed"),
                contact(2, "Rptr", type = Codes.ADV_TYPE_REPEATER),
                contact(3, "amy"),
                contact(4, "fav", flags = Codes.CONTACT_FLAG_FAVORITE),
            ),
            null, now,
        )
        val chat = ContactNodes.arrange(nodes, NodeTab.Contacts, "", NodeListModel.Sort.Name, emptySet(), now, null)
        assertEquals(listOf("amy", "fav", "zed"), chat.map { it.name })
        val favs = ContactNodes.arrange(nodes, null, "", NodeListModel.Sort.Name, setOf(NodeListModel.Filter.Favorites), now, null)
        assertEquals(listOf("fav"), favs.map { it.name })
        assertEquals(mapOf(NodeTab.Contacts to 3, NodeTab.Repeaters to 1), ContactNodes.counts(nodes))
    }

    @Test
    fun searchMatchesNameOrKeyPrefix() {
        val nodes = ContactNodes.build(listOf(contact(1, "Alpha"), contact(0x40, "Bravo")), null, now)
        val key = ByteArray(32) { (0x40 + it).toByte() }.toHex()
        assertEquals(listOf("Alpha"), ContactNodes.arrange(nodes, null, "alp", NodeListModel.Sort.Name, emptySet(), now, null).map { it.name })
        assertEquals(listOf("Bravo"), ContactNodes.arrange(nodes, null, key.take(6), NodeListModel.Sort.Name, emptySet(), now, null).map { it.name })
    }

    @Test
    fun heardLabelUsesWhatTheRadioHeardNotTheClaim() {
        // Advert claims 2020; the radio heard it a minute ago.
        val c = contact(1, "Al", advertAt = 1_577_836_800L, lastMod = now - 60)
        assertEquals("heard 1 min ago", ContactNodes.build(listOf(c), null, now).single().heardLabel(now))
        assertNull(ContactNodes.build(listOf(contact(1, "x", lastMod = 0)), null, now).single().heardLabel(now))
    }

    @Test
    fun nearestIsMeasuredFromTheRadioAndUnplacedNodesGoLast() {
        // Grand Rapids area, region-level only.
        val me = io.github.thatsfguy.meshcore.presentation.SelfPosition(42.96, -85.67)
        val nodes = ContactNodes.build(
            listOf(
                contact(1, "far", lat = 43.40, lon = -85.67),
                contact(2, "nowhere"),
                contact(3, "near", lat = 42.97, lon = -85.67),
            ),
            null, now,
        )
        val order = ContactNodes.arrange(nodes, null, "", NodeListModel.Sort.Distance, emptySet(), now, me).map { it.name }
        assertEquals(listOf("near", "far", "nowhere"), order)
        val near = nodes.first { it.name == "near" }
        assertEquals("1.1 km", ContactNodes.distanceLabel(near, me, io.github.thatsfguy.meshcore.presentation.UnitSystem.Metric))
        assertEquals("3648 ft", ContactNodes.distanceLabel(near, me, io.github.thatsfguy.meshcore.presentation.UnitSystem.Imperial))
        assertNull(ContactNodes.distanceLabel(nodes.first { it.name == "nowhere" }, me, io.github.thatsfguy.meshcore.presentation.UnitSystem.Metric))
    }

    @Test
    fun newMessageOffersPeopleOnly() {
        val nodes = ContactNodes.build(
            listOf(contact(1, "Amy"), contact(2, "Rptr", type = Codes.ADV_TYPE_REPEATER), contact(3, "Room", type = Codes.ADV_TYPE_ROOM)),
            null, now,
        )
        assertEquals(listOf("Amy"), ContactNodes.messageCandidates(nodes, "").map { it.name })
        assertTrue(ContactNodes.messageCandidates(nodes, "zz").isEmpty())
        assertEquals(io.github.thatsfguy.meshcore.presentation.NewMessage.NO_MATCH,
            io.github.thatsfguy.meshcore.presentation.NewMessage.emptyText("zz"))
    }

    @Test
    fun theOutPathIsSplitAtTheContactsOwnWidthAndAmbiguityIsListed() {
        // path_len 0x42: 2-byte hashes, two hops — the live route b389 c985.
        val path = ByteArray(64).also { byteArrayOf(0xB3.toByte(), 0x89.toByte(), 0xC9.toByte(), 0x85.toByte()).copyInto(it) }
        val c = Contact(
            publicKey = ByteArray(32) { 9 }, type = Codes.ADV_TYPE_CHAT, flags = 0, pathLen = 0x42, path = path,
            name = "Bob", timestamp = now, latitude = null, longitude = null, lastModified = now,
        )
        val names = mapOf("b389" + "00".repeat(30) to "Ridge", "c985" + "11".repeat(30) to "Mill", "c985" + "22".repeat(30) to "Mill too")
        val lines = ContactNodes.outPathLines(c, names)
        assertEquals(2, lines.size, "two hops at two bytes, not four at one")
        assertEquals("1. b389 Ridge", lines[0])
        assertTrue(lines[1].startsWith("2. c985 ") && "Mill or Mill too" in lines[1], lines[1])
        assertEquals("2-bytes per hop", ContactNodes.hashWidthLine(c, 1), "the contact's own width beats the device's")
        val flood = Contact(publicKey = ByteArray(32) { 9 }, type = 1, flags = 0, pathLen = 0xFF, path = ByteArray(64),
            name = "F", timestamp = now, latitude = null, longitude = null, lastModified = now)
        assertTrue(ContactNodes.outPathLines(flood, names).isEmpty())
        assertEquals("1-byte per hop", ContactNodes.hashWidthLine(flood, 1))
    }
}
