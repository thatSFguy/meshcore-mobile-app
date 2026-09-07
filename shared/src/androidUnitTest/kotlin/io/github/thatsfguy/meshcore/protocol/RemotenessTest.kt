package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.protocol.IdentityKeygen.Remoteness
import io.github.thatsfguy.meshcore.util.haversineMetres
import io.github.thatsfguy.meshcore.util.isPlausiblePosition
import io.github.thatsfguy.meshcore.util.meshCentre
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ------------------------------------------------------------------
    // meshCentre — the other half of the same rule. isPlausiblePosition
    // refuses the wrong answer; this supplies a usable one for the places
    // that have to point a camera somewhere.

    @Test
    fun `the centre of a local mesh is inside it`() {
        // The positive control: three nodes around Grand Rapids must
        // produce a point among them, not an average with a stray zero
        // dragged into it.
        val centre = meshCentre(
            listOf(
                42.9634 to -85.6681,
                43.0125 to -85.5500,
                42.8000 to -85.7000,
            ),
        )!!
        assertTrue(centre.first in 42.7..43.1, "latitude was ${centre.first}")
        assertTrue(centre.second in -85.8..-85.4, "longitude was ${centre.second}")
    }

    @Test
    fun `a node with no fix does not drag the centre towards Africa`() {
        // The bug this exists to prevent. Two nodes on the mesh and one
        // that has never had a fix: averaged naively the centre lands a
        // third of the way to the Gulf of Guinea and every camera that
        // uses it opens on ocean.
        val withUnset = meshCentre(
            listOf(42.9634 to -85.6681, 43.0125 to -85.5500, 0.0 to 0.0),
        )!!
        val without = meshCentre(listOf(42.9634 to -85.6681, 43.0125 to -85.5500))!!
        assertEquals(without.first, withUnset.first, 1e-9)
        assertEquals(without.second, withUnset.second, 1e-9)
    }

    @Test
    fun `out of range coordinates are discarded like unset ones`() {
        // A hostile advert is not obliged to send a real latitude.
        val centre = meshCentre(
            listOf(42.9634 to -85.6681, 91.0 to -85.0, 42.0 to 181.0),
        )!!
        assertEquals(42.9634, centre.first, 1e-9)
        assertEquals(-85.6681, centre.second, 1e-9)
    }

    @Test
    fun `nothing placed means no guess at all`() {
        // The caller has to be able to tell "we have no idea" from a
        // point, or 0, 0 comes back in through this door instead.
        assertNull(meshCentre(emptyList()))
        assertNull(meshCentre(listOf(0.0 to 0.0, 0.0 to 0.0)))
        assertNull(meshCentre(listOf(91.0 to 0.0)))
    }

    @Test
    fun `a mesh straddling the antimeridian does not fold into Africa`() {
        // Averaging longitude as a number puts the midpoint of 179 and
        // -179 at zero — the far side of the planet, and by coincidence
        // the exact wrong answer this whole file is about. Averaged as a
        // direction it stays on the date line.
        val centre = meshCentre(listOf(-16.5 to 179.9, -16.6 to -179.9))!!
        assertTrue(
            kotlin.math.abs(centre.second) > 179.0,
            "longitude folded to ${centre.second}",
        )
        assertTrue(centre.first in -16.7..-16.4, "latitude was ${centre.first}")
    }

    @Test
    fun `antipodal longitudes fall back to a real node rather than to zero`() {
        // Two nodes exactly half a world apart cancel to the origin,
        // where the direction is undefined and atan2(0, 0) quietly
        // reads as 0 degrees — Null Island by arithmetic accident.
        val centre = meshCentre(listOf(10.0 to 0.0, 10.0 to 180.0))!!
        assertTrue(
            centre.second == 0.0 || kotlin.math.abs(centre.second) == 180.0,
            "longitude was ${centre.second}",
        )
        assertEquals(10.0, centre.first, 1e-9)
    }
}
