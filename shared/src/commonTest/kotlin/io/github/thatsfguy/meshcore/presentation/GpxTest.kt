package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxTest {

    private fun c(seed: Int, name: String, lat: Double?, lon: Double?) = Contact(
        publicKey = ByteArray(32) { (seed + it).toByte() }, type = Codes.ADV_TYPE_REPEATER, flags = 0,
        pathLen = 0xFF, path = ByteArray(64), name = name, timestamp = 0, latitude = lat, longitude = lon, lastModified = 0,
    )

    @Test
    fun onlyPlacedNodesBecomeWaypoints() {
        val gpx = Gpx.fromContacts(listOf(c(1, "Ridge", 42.96, -85.67), c(2, "Nowhere", 0.0, 0.0), c(3, "Unset", null, null)))!!
        assertEquals(1, Regex("<wpt ").findAll(gpx).count())
        assertTrue("<name>Ridge</name>" in gpx)
        assertTrue("creator=\"MeshCore Hardened\"" in gpx)
    }

    @Test
    fun nothingPlacedIsNull() {
        assertNull(Gpx.fromContacts(listOf(c(1, "x", null, null))))
    }

    @Test
    fun aHostileNameCannotBreakTheFile() {
        val gpx = Gpx.fromContacts(listOf(c(1, "</name><wpt lat=\"0\"\u0000\u0007 & \"q\"", 42.0, -85.0)))!!
        assertEquals(1, Regex("<wpt ").findAll(gpx).count(), "an injected element must not appear")
        assertFalse('\u0000' in gpx || '\u0007' in gpx, "XML 1.0 cannot carry control characters")
        assertTrue("&lt;/name&gt;&lt;wpt lat=&quot;0&quot; &amp; &quot;q&quot;" in gpx, gpx)
    }

    @Test
    fun aBlankNameFallsBackToTheKeyPrefix() {
        val gpx = Gpx.fromContacts(listOf(c(0x10, "", 42.0, -85.0)))!!
        assertTrue("<name>101112131415</name>" in gpx, gpx)
    }
}
