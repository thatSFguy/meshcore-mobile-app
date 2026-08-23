package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.protocol.IdentityKeygen.Remoteness
import io.github.thatsfguy.meshcore.util.haversineMetres
import io.github.thatsfguy.meshcore.util.isPlausiblePosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How safe a node is to share leading bytes with.
 *
 * This is a ranking, so the tests are about **order**, not values: what
 * matters is that a repeater on the next hill always loses to one across
 * the state, and that a node we cannot place never wins by default.
 * Asserting the numbers themselves would pin an arbitrary scale and stop
 * the bands from ever being retuned.
 */
class RemotenessTest {

    private fun repeater(metres: Double?, hops: Int? = null) =
        Remoteness.of(metres, hops, isInfrastructure = true)

    private fun chatNode(metres: Double?, hops: Int? = null) =
        Remoteness.of(metres, hops, isInfrastructure = false)

    @Test
    fun fartherIsSafer() {
        val ranks = listOf(500.0, 5_000.0, 20_000.0, 60_000.0, 250_000.0).map { repeater(it) }
        assertEquals(ranks.sorted(), ranks, "distance bands are not monotonic: $ranks")
        assertTrue(ranks.first() < ranks.last())
    }

    @Test
    fun moreHopsIsSaferWhenThereIsNoPosition() {
        val ranks = listOf(0, 1, 2, 3, 6).map { repeater(null, hops = it) }
        assertEquals(ranks.sorted(), ranks, "hop bands are not monotonic: $ranks")
        assertTrue(repeater(null, hops = 6) > repeater(null, hops = 1))
    }

    @Test
    fun aNodeWeCannotPlaceIsTreatedAsTheClosestThingThereIs() {
        // The conservative direction on purpose. Ranking an unplaceable
        // node as distant would let the search pick it as the "safe"
        // clash partner precisely because nothing is known about it,
        // which is backwards.
        assertEquals(Remoteness.UNKNOWN, repeater(null, hops = null))
        assertTrue(repeater(null, null) < repeater(500.0))
        assertTrue(repeater(null, null) < repeater(null, hops = 2))
    }

    @Test
    fun aFloodContactCountsAsUnplaceableRatherThanFarAway() {
        // A flood contact has no stored route, so it has no hop count.
        // Callers pass null for it; a negative hop count must never be
        // read as a very large distance.
        assertEquals(Remoteness.UNKNOWN, repeater(null, hops = -1))
    }

    @Test
    fun anyOrdinaryNodeIsSaferToCollideWithThanAnyRepeater() {
        // Only repeaters and room servers append themselves to a path
        // (Mesh.cpp:345-349), so a chat node sharing a prefix costs a
        // destination-hash near-miss and nothing else. That difference
        // is categorical, not a matter of degree — the nearest chat node
        // still beats the most distant repeater.
        assertTrue(chatNode(null, null) > repeater(250_000.0))
        assertTrue(chatNode(10.0) > repeater(1_000_000.0))
    }

    @Test
    fun distanceWinsOverHopsWhenBothAreKnown() {
        // A node 200 km away reached in one hop (a mountain-top link) is
        // still 200 km away, and that is the number worth trusting: the
        // hop count describes one moment's route.
        assertTrue(repeater(200_000.0, hops = 1) > repeater(1_000.0, hops = 6))
    }

    // ---- the words that go with it -----------------------------------

    @Test
    fun distanceIsDescribedInUnitsAPersonCanActOn() {
        assertEquals("400 m away", Remoteness.describe(400.0, null))
        assertEquals("2.5 km away", Remoteness.describe(2_500.0, null))
        assertEquals("250 km away", Remoteness.describe(250_000.0, null))
        assertEquals("1 hop away", Remoteness.describe(null, 1))
        assertEquals("3 hops away", Remoteness.describe(null, 3))
        // Said out loud rather than omitted: a clash with a node nobody
        // can place is the one to go and check.
        assertEquals("distance unknown", Remoteness.describe(null, null))
    }

    // ---- the geometry it rests on ------------------------------------

    /**
     * Pinned against arithmetic rather than against a place.
     *
     * One degree of latitude on a sphere of radius 6 371 km is
     * 2πr/360 = 111 194.9 m, which can be checked on paper. The first
     * draft of this test used a city pair and a distance recalled from
     * memory; the number was wrong and the code was right, which is
     * exactly the way round that wastes an afternoon.
     */
    @Test
    fun theDistanceItselfIsRight() {
        val oneDegreeOfLatitude = 2 * kotlin.math.PI * 6_371_000.0 / 360.0
        assertTrue(
            kotlin.math.abs(haversineMetres(0.0, 0.0, 1.0, 0.0) - oneDegreeOfLatitude) < 1.0,
            "got ${haversineMetres(0.0, 0.0, 1.0, 0.0)}, expected $oneDegreeOfLatitude",
        )
        // A degree of longitude is the same at the equator and shrinks
        // with the cosine of the latitude — at 60° it is half.
        assertTrue(
            kotlin.math.abs(haversineMetres(0.0, 0.0, 0.0, 1.0) - oneDegreeOfLatitude) < 1.0,
        )
        assertTrue(
            kotlin.math.abs(haversineMetres(60.0, 0.0, 60.0, 1.0) - oneDegreeOfLatitude / 2) < 60.0,
        )
        // Same point is zero, and the function is symmetric.
        assertEquals(0.0, haversineMetres(42.9634, -85.6681, 42.9634, -85.6681))
        assertEquals(
            haversineMetres(42.9634, -85.6681, 42.3314, -83.0458),
            haversineMetres(42.3314, -83.0458, 42.9634, -85.6681),
        )
    }

    @Test
    fun nullIslandIsNotAPosition() {
        // A node that has never had a fix advertises 0, 0 — a real place
        // in the Gulf of Guinea about 10 000 km from this mesh. Treating
        // it as one turns "no position" into "very far away", which is
        // the single most dangerous wrong answer this file can give.
        assertFalse(isPlausiblePosition(0.0, 0.0))
        assertFalse(isPlausiblePosition(null, null))
        assertFalse(isPlausiblePosition(42.9634, null))
        assertFalse(isPlausiblePosition(91.0, 10.0))
        assertFalse(isPlausiblePosition(45.0, 181.0))
        assertTrue(isPlausiblePosition(42.9634, -85.6681))
    }
}
