package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.messaging.BookMessage
import io.github.thatsfguy.meshcore.messaging.MessageStatus
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDetailTest {

    private fun msg(
        outgoing: Boolean = false,
        hops: Int? = null,
        snr: Double? = null,
        path: String? = null,
        width: Int? = null,
        repeats: String? = null,
        repeatWidth: Int? = null,
    ) = BookMessage(
        id = 1, threadKey = "dm|aa", outgoing = outgoing, text = "x", timestamp = 1, receivedAtMs = 1,
        status = MessageStatus.Delivered, snr = snr, hops = hops,
        arrivalPathHex = path, arrivalHashWidth = width, repeatHopsHex = repeats, repeatHashWidth = repeatWidth,
    )

    private fun repeater(keyHex: String, name: String, lat: Double? = null, lon: Double? = null) = Contact(
        publicKey = hexToBytesOrNull(keyHex)!!, type = Codes.ADV_TYPE_REPEATER, flags = 0, pathLen = 0xFF,
        path = ByteArray(64), name = name, timestamp = 0, latitude = lat, longitude = lon, lastModified = 0,
    )

    @Test
    fun routedIsNeverCalledDirect() {
        assertEquals("routed", MessageDetail.hopsLabel(-1))
        assertEquals("direct", MessageDetail.hopsLabel(0))
        assertEquals("1 hop", MessageDetail.hopsLabel(1))
        assertTrue(MessageDetail.arrivalLabel(-1)!!.startsWith("Routed"))
        assertNull(MessageDetail.hopsLabel(null))
        assertNull(MessageDetail.copiesLabel(1))
        assertEquals("Delivered 3 times", MessageDetail.copiesLabel(3))
    }

    @Test
    fun aRealRouteIsListedSenderToUsWithNames() {
        // Positive control: b389c985 at 2-byte width resolves to two named
        // repeaters (the route the heard-via suite pins).
        val contacts = listOf(
            repeater("b389" + "00".repeat(30), "Ridge"),
            repeater("c985" + "11".repeat(30), "13 Mile"),
        )
        val r = MessageDetail.arrival(msg(hops = 2, path = "b389c985", width = 2), contacts, null, null)
        assertEquals("Arrived via 2 repeater(s), listed in the order it travelled.", r.summary)
        assertEquals("↑ sender", r.steps.first())
        assertEquals("↓ this radio", r.steps.last())
        assertTrue(r.steps[1].startsWith("1. ") && "Ridge" in r.steps[1], r.steps.toString())
        assertTrue("13 Mile" in r.steps[2], r.steps.toString())
    }

    @Test
    fun withoutARouteOnlyTheSentenceIsGiven() {
        val r = MessageDetail.arrival(msg(hops = -1), emptyList(), null, null)
        assertTrue(r.steps.isEmpty())
        assertTrue(r.summary.startsWith("Sent along a route the sender already had"))
        // A path with an impossible width is no route at all.
        assertTrue(MessageDetail.arrival(msg(hops = 2, path = "b389c985", width = 7), emptyList(), null, null).steps.isEmpty())
    }

    @Test
    fun theFooterQualifiesSignalAndShowsRepeatsForOurOwn() {
        val inbound = MessageDetail.bubbleFooter(msg(hops = 2, snr = 6.5))!!
        assertTrue(inbound.startsWith("2 hops"), inbound)
        assertEquals(MessageSignal.bubble(6.5, 2), inbound.substringAfter(" · "))
        assertEquals("↻ 2", MessageDetail.bubbleFooter(msg(outgoing = true, repeats = "b389c985", repeatWidth = 2)))
        assertNull(MessageDetail.bubbleFooter(msg(outgoing = true)))
    }

    @Test
    fun repeatsResolveToNamesOrStayAsHashes() {
        val r = MessageDetail.repeats(
            msg(outgoing = true, repeats = "b389c985", repeatWidth = 2),
            listOf(repeater("b389" + "00".repeat(30), "Ridge")),
        )
        assertEquals("2 nodes were heard re-broadcasting this message.", r.summary)
        assertTrue("Ridge" in r.steps[0])
        assertEquals("2. c985", r.steps[1])
    }

    @Test
    fun distanceNeedsBothPositions() {
        val c = listOf(repeater("b389" + "00".repeat(30), "Ridge", 42.9, -85.6))
        assertNull(MessageDetail.metresAwayFrom(null, null, c))
        val f = MessageDetail.metresAwayFrom(42.95, -85.6, c)!!
        assertTrue(f("b389" + "00".repeat(30))!! in 5_000.0..6_000.0)
        assertNull(f("ff".repeat(32)))
    }

    @Test
    fun theTechnicalRowsAreFormattedAsAndroidShowsThem() {
        assertEquals("0000002a", MessageDetail.ackHex(42))
        assertEquals("deadbeef", MessageDetail.ackHex(0xDEADBEEFL))
        assertEquals(null, MessageDetail.ackHex(null))
        assertEquals("(none) — unauthenticated", MessageDetail.senderLine(" "))
        assertEquals("Bob — unauthenticated", MessageDetail.senderLine("Bob"))
        assertEquals("3 bytes", MessageDetail.lengthLine("hé"), "bytes on the wire, not characters")
    }
}
