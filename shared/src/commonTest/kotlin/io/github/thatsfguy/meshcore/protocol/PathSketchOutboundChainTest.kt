package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chain for a contact's stored outbound route (routing sheet map).
 *
 * The rule under test is the tail. A stored route's last hop is usually
 * the destination itself — routing to a repeater is the ordinary case,
 * and the live one that prompted this drew `b389 SpartaMI, c985
 * KCEST-GRR-BYRONCTR-01` for the contact `c985cffd466e…`. Appending the
 * contact again would stack two pins on one spot with a zero-length
 * segment between them.
 */
class PathSketchOutboundChainTest {

    private fun hop(
        hashHex: String,
        name: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        gap: PathGeometry.Gap? = null,
    ) = PathGeometry.HopPoint(hashHex, name, lat, lon, gap)

    private val dest = "c985cffd466e7a50c48a620d842f13aaf551bc7ec59b9b6dc60f990858240acb"

    private fun chain(hops: List<PathGeometry.HopPoint>) = PathSketch.outboundChain(
        selfLabel = "Blue",
        selfLatitude = 43.16,
        selfLongitude = -85.64,
        hops = hops,
        destKeyHex = dest,
        destLabel = "KCEST-GRR-BYRONCTR-01",
        destLatitude = 42.854,
        destLongitude = -85.682,
    )

    @Test
    fun theLastHopBeingTheDestinationIsNotRepeated() {
        // The live case: 2 hops, the second IS the contact.
        val c = chain(
            listOf(
                hop("b389", "SpartaMI", 43.16, -85.64),
                hop("c985", "KCEST-GRR-BYRONCTR-01", 42.854, -85.682),
            ),
        )
        assertEquals(3, c.size, "self + 2 hops, with no duplicated destination")
        assertEquals(listOf("Blue", "SpartaMI", "KCEST-GRR-BYRONCTR-01"), c.map { it.label })
        // Both ends are endpoints; the middle hop is not.
        assertTrue(c.first().isEndpoint)
        assertTrue(!c[1].isEndpoint)
        assertTrue(c.last().isEndpoint, "the last hop stands in as the endpoint")
    }

    @Test
    fun aDestinationBeyondTheLastHopIsAppended() {
        // Routing to a companion THROUGH repeaters: the contact is not
        // in the hop list and must be drawn as its own endpoint.
        val c = chain(listOf(hop("b389", "SpartaMI", 43.16, -85.64)))
        assertEquals(listOf("Blue", "SpartaMI", "KCEST-GRR-BYRONCTR-01"), c.map { it.label })
        assertTrue(c.first().isEndpoint)
        assertTrue(!c[1].isEndpoint, "an intermediate hop is not an endpoint")
        assertTrue(c.last().isEndpoint)
        assertEquals(42.854, c.last().latitude)
    }

    @Test
    fun anUnidentifiedLastHopIsNeverTreatedAsTheDestination() {
        // A hash that matched two contacts tells us nothing about which
        // one this is, so it cannot stand in for the destination even
        // when the prefix matches.
        for (gap in listOf(PathGeometry.Gap.Ambiguous, PathGeometry.Gap.NoMatch)) {
            val c = chain(listOf(hop("c985", null, gap = gap)))
            assertEquals(3, c.size, "$gap must not collapse into the destination")
            assertEquals("KCEST-GRR-BYRONCTR-01", c.last().label)
            assertTrue(c.last().isEndpoint)
            assertTrue(!c[1].isEndpoint)
        }
    }

    @Test
    fun aHopWithNoPositionStillCountsAsTheDestination() {
        // NoPosition is "we know who, not where" — identity is intact,
        // so it is still the endpoint and may be placed by inference.
        val c = chain(
            listOf(hop("c985", "KCEST-GRR-BYRONCTR-01", gap = PathGeometry.Gap.NoPosition)),
        )
        assertEquals(2, c.size)
        assertTrue(c.last().isEndpoint)
        assertNull(c.last().unidentifiedReason, "NoPosition is not an identity failure")
    }

    @Test
    fun onlyIdentityFailuresCarryAReason() {
        val c = chain(
            listOf(
                hop("aabb", null, gap = PathGeometry.Gap.Ambiguous),
                hop("ccdd", "Named", 1.0, 2.0),
            ),
        )
        assertEquals("several contacts match this hop hash", c[1].unidentifiedReason)
        assertNull(c[2].unidentifiedReason)
    }

    @Test
    fun anEmptyRouteHasNothingToDraw() {
        // A flooded contact has no stored path; drawing self→contact
        // would invent a route that was never taken.
        assertEquals(emptyList(), chain(emptyList()))
    }

    @Test
    fun theHopHashIsTheLabelWhenNoContactMatched() {
        val c = chain(listOf(hop("dead", null, gap = PathGeometry.Gap.NoMatch)))
        assertEquals("dead", c[1].label)
    }

    @Test
    fun prefixMatchingIsCaseInsensitive() {
        // Hop hashes are rendered lower-case here and upper-case by the
        // node's own CLI; a route must not double its endpoint on that.
        val c = PathSketch.outboundChain(
            selfLabel = "Blue", selfLatitude = null, selfLongitude = null,
            hops = listOf(hop("C985", "Dest", 1.0, 2.0)),
            destKeyHex = dest, destLabel = "Dest",
            destLatitude = 1.0, destLongitude = 2.0,
        )
        assertEquals(2, c.size)
    }
}
