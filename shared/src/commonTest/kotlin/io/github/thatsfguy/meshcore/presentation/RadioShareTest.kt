package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioShareTest {

    /** Blue Base: 910.525 MHz, 62.5 kHz, SF7, CR5, 2-byte hashes. */
    private fun share(width: Int? = 2, region: String? = "mi", name: String? = "USA/Canada (Recommended)") =
        RadioShare.uriFor(910_525, 62_500, 7, 5, width, region, name)

    @Test
    fun whatIsSharedIsWhatAScannerApplies() {
        val c = ShareUri.decode(share()!!) as ShareUri.Decoded.RadioConfig
        assertEquals(910_525, c.frequencyKhz)
        assertEquals(62_500, c.bandwidthHz)
        assertEquals(7, c.spreadingFactor)
        assertEquals(5, c.codingRate)
        // Width 2 is mode 1 — the off-by-one this codebase keeps meeting.
        assertEquals(1, c.pathHashMode)
        assertEquals("mi", c.region)
        assertEquals("USA/Canada (Recommended)", c.name)
    }

    @Test
    fun anUnknownWidthIsNotShared() {
        // DEVICE_INFO without a width, or one this build has no mode for:
        // a clamped guess in a code misconfigures everyone who scans it.
        assertNull(share(width = null))
        assertNull(share(width = 0))
        assertNull(share(width = 4))
        assertTrue(share(width = 1) != null && share(width = 3) != null)
    }

    @Test
    fun noRegionMeansNoRegionField() {
        for (none in listOf(null, "", "  ")) {
            val uri = share(region = none)!!
            assertTrue("region" !in uri, uri)
        }
    }

    @Test
    fun aRegionIsSharedCanonically() {
        val c = ShareUri.decode(share(region = "#MI")!!) as ShareUri.Decoded.RadioConfig
        assertEquals("mi", c.region)
    }
}
