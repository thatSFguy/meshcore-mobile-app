package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.messaging.BookMessage
import io.github.thatsfguy.meshcore.messaging.MessageStatus
import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathMapsTest {

    private fun c(keyHex: String, name: String, lat: Double?, lon: Double?, pathLen: Int = 0xFF, path: String = "") = Contact(
        publicKey = hexToBytesOrNull(keyHex)!!, type = Codes.ADV_TYPE_REPEATER, flags = 0, pathLen = pathLen,
        path = (hexToBytesOrNull(path) ?: ByteArray(0)).copyOf(64), name = name, timestamp = 0,
        latitude = lat, longitude = lon, lastModified = 0,
    )

    private val ridge = c("b389" + "00".repeat(30), "Ridge", 42.95, -85.60)
    private val mile = c("c985" + "11".repeat(30), "13 Mile", 43.05, -85.55)
    private val al = c("aa".repeat(32), "Al", 42.90, -85.70)

    private fun msg(path: String?, width: Int?) = BookMessage(
        id = 1, threadKey = "dm|${"aa".repeat(32)}", outgoing = false, text = "x", timestamp = 1, receivedAtMs = 1,
        status = MessageStatus.Delivered, hops = 2, arrivalPathHex = path, arrivalHashWidth = width,
    )

    @Test
    fun anArrivalIsDrawnSenderToRepeatersToUs() {
        val s = assertNotNull(PathMaps.arrival(msg("b389c985", 2), "Al", listOf(ridge, mile, al), "Blue Base", 43.0, -85.65))
        assertEquals(listOf("Al", "Ridge", "13 Mile", "Blue Base"), s.nodes.map { it.label })
        assertEquals(3, s.segments.size)
        assertTrue(s.segments.all { PathMaps.isSolid(it) })
        assertNull(PathMaps.legend(s))
    }

    @Test
    fun noRecordedRouteIsNoMap() {
        assertNull(PathMaps.arrival(msg(null, null), "Al", listOf(ridge), "Me", 43.0, -85.6))
        assertNull(PathMaps.arrival(msg("b389c985", 9), "Al", listOf(ridge), "Me", 43.0, -85.6))
    }

    @Test
    fun aFloodContactHasNoStoredRouteMap() {
        assertNull(PathMaps.storedRoute(al, listOf(ridge), "Me", 43.0, -85.6))
    }

    @Test
    fun aStoredRouteRunsFromUsToTheContact() {
        // 0x42: two hops at 2 bytes each — b389, c985.
        val dest = c("aa".repeat(32), "Al", 42.90, -85.70, pathLen = 0x42, path = "b389c985")
        val s = assertNotNull(PathMaps.storedRoute(dest, listOf(ridge, mile, dest), "Blue Base", 43.0, -85.65))
        assertEquals("Blue Base", s.nodes.first().label)
        assertEquals("Al", s.nodes.last().label)
    }
}
