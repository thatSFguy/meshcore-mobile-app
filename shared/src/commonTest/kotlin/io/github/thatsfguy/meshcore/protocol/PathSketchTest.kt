package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Laying a route out on a map, including the parts nobody can place.
 *
 * The rule this whole file protects: a node whose IDENTITY is unknown
 * is never given a position, at any confidence. A node whose identity
 * is known but whose position is not may be placed approximately, and
 * must be marked as such — because an inferred pin and a surveyed pin
 * look identical on a map, and this app's stated posture is that a
 * route drawn by guessing is worse than no route at all.
 */
class PathSketchTest {

    private fun known(label: String, lat: Double, lon: Double) =
        PathSketch.Waypoint(label, lat, lon)

    private fun noPosition(label: String) =
        PathSketch.Waypoint(label, null, null)

    private fun unidentified(label: String, why: String = "several contacts match") =
        PathSketch.Waypoint(label, null, null, unidentifiedReason = why)

    // --- the baseline ------------------------------------------------------

    @Test
    fun `a fully known route is solid end to end`() {
        val s = PathSketch.build(
            listOf(
                known("sender", 43.0, -85.0),
                known("R1", 43.1, -85.1),
                known("me", 43.2, -85.2),
            ),
        )
        assertEquals(0, s.unplaced)
        assertEquals(0, s.inferred)
        assertEquals(2, s.segments.size)
        assertTrue(s.segments.all { it.style == PathSketch.Style.Solid })
    }

    // --- the reported case: a companion with no GPS ------------------------

    @Test
    fun `a sender with no position is placed away from the rest of the route`() {
        // Companion -> R1 -> R2. The companion must not land on R1, and
        // must not land on the R1->R2 side of it.
        val s = PathSketch.build(
            listOf(
                noPosition("companion"),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -84.0), // due east of R1
            ),
        )
        val companion = s.nodes.first()
        assertEquals(PathSketch.Certainty.Inferred, companion.certainty)
        assertNotNull(companion.point)
        // R2 is east, so the companion goes west of R1.
        assertTrue(
            companion.point!!.longitude < -85.0,
            "companion placed toward the route, not away: ${companion.point}",
        )
        // And not on top of its anchor.
        assertTrue(PathSketch.separation(companion.point!!, PathSketch.Point(43.0, -85.0)) > 0.0)
        // The line to it is dotted, never solid — it is not a claim.
        assertEquals(PathSketch.Style.Dotted, s.segments.first().style)
    }

    @Test
    fun `the receiver with no position is placed by the same rule mirrored`() {
        // The commonest case of all: this phone's own node has no GPS.
        val s = PathSketch.build(
            listOf(
                known("R1", 43.0, -84.0),
                known("R2", 43.0, -85.0), // R2 is west of R1
                noPosition("me"),
            ),
        )
        val me = s.nodes.last()
        assertEquals(PathSketch.Certainty.Inferred, me.certainty)
        // Continuing westward, away from R1.
        assertTrue(me.point!!.longitude < -85.0, "receiver placed back toward the route")
        assertEquals(PathSketch.Style.Dotted, s.segments.last().style)
    }

    // --- the second scenario: an unpositioned repeater mid-route -----------

    @Test
    fun `an unplaceable middle repeater sits on the line between its neighbours`() {
        // R1 (known) -> R2 (no position) -> R3 (known).
        val s = PathSketch.build(
            listOf(
                known("R1", 43.0, -85.0),
                noPosition("R2"),
                known("R3", 43.0, -84.0),
            ),
        )
        val r2 = s.nodes[1]
        assertEquals(PathSketch.Certainty.Inferred, r2.certainty)
        // Midway, because there is exactly one unknown between them.
        assertEquals(43.0, r2.point!!.latitude, 1e-9)
        assertEquals(-84.5, r2.point!!.longitude, 1e-9)
        // Both segments dotted: one end of each is an inferred point.
        assertTrue(s.segments.all { it.style == PathSketch.Style.Dotted })
    }

    @Test
    fun `two unplaceable repeaters in a row are spread not stacked`() {
        val s = PathSketch.build(
            listOf(
                known("R1", 43.0, -85.0),
                noPosition("R2"),
                noPosition("R3"),
                known("R4", 43.0, -84.0),
            ),
        )
        val r2 = s.nodes[1].point!!
        val r3 = s.nodes[2].point!!
        assertEquals(-84.0 - 2.0 / 3.0, r2.longitude, 1e-9) // one third along
        assertEquals(-84.0 - 1.0 / 3.0, r3.longitude, 1e-9) // two thirds
        assertTrue(PathSketch.separation(r2, r3) > 0.0, "the two inferred pins overlap")
    }

    @Test
    fun `two unplaceable endpoints step outward instead of stacking`() {
        val s = PathSketch.build(
            listOf(
                noPosition("companion"),
                noPosition("R0"),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -84.0),
            ),
        )
        val a = s.nodes[0].point!!
        val b = s.nodes[1].point!!
        assertTrue(PathSketch.separation(a, b) > 0.0, "stacked endpoints")
        // The further one along the chain is the further one on the map.
        val anchor = PathSketch.Point(43.0, -85.0)
        assertTrue(PathSketch.separation(a, anchor) > PathSketch.separation(b, anchor))
    }

    // --- the rule that must not bend --------------------------------------

    @Test
    fun `a hop whose identity is unknown is never given a position`() {
        // Ambiguous or unmatched: we do not know WHO, so there is
        // nothing to place approximately. This is the case the app
        // already refuses to guess at, and inference must not sneak a
        // pin in through the back door.
        val s = PathSketch.build(
            listOf(
                known("R1", 43.0, -85.0),
                unidentified("??"),
                known("R3", 43.0, -84.0),
            ),
        )
        val mystery = s.nodes[1]
        assertNull(mystery.point)
        assertNull(mystery.certainty)
        assertEquals("several contacts match", mystery.reason)
        // The span across it is dashed — the app's existing "no route is
        // being claimed here" line.
        assertEquals(1, s.segments.size)
        assertEquals(PathSketch.Style.Dashed, s.segments.single().style)
    }

    @Test
    fun `an unidentified endpoint is not placed either`() {
        val s = PathSketch.build(
            listOf(
                unidentified("??"),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -84.0),
            ),
        )
        assertNull(s.nodes.first().point)
        assertEquals(1, s.unplaced)
    }

    // --- degenerate shapes -------------------------------------------------

    @Test
    fun `a single known node still places its unknown neighbours`() {
        // Nothing to point away from; the direction must still be
        // deterministic rather than arbitrary.
        val a = PathSketch.build(listOf(noPosition("companion"), known("R1", 43.0, -85.0)))
        val b = PathSketch.build(listOf(noPosition("companion"), known("R1", 43.0, -85.0)))
        assertEquals(a.nodes.first().point, b.nodes.first().point)
        assertNotNull(a.nodes.first().point)
    }

    @Test
    fun `co-sited anchors do not produce a zero-length direction`() {
        // Two repeaters on the same mast. "Opposite direction" has no
        // meaning; the sender still has to go somewhere sensible.
        val s = PathSketch.build(
            listOf(
                noPosition("companion"),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -85.0),
            ),
        )
        val p = s.nodes.first().point
        assertNotNull(p)
        assertTrue(
            PathSketch.separation(p!!, PathSketch.Point(43.0, -85.0)) > 0.0,
            "sender stacked on its anchor",
        )
    }

    @Test
    fun `nothing placeable means no segments and an honest summary`() {
        val s = PathSketch.build(listOf(noPosition("companion"), unidentified("??")))
        assertTrue(s.isEmpty)
        assertTrue(s.segments.isEmpty())
        assertTrue(s.summary().contains("None"))
    }

    @Test
    fun `an empty chain is a route that was never recorded`() {
        val s = PathSketch.build(emptyList())
        assertTrue(s.nodes.isEmpty())
        assertTrue(s.segments.isEmpty())
        assertTrue(s.summary().contains("No route"))
    }

    @Test
    fun `an all-zero position counts as unset not as the Gulf of Guinea`() {
        val s = PathSketch.build(
            listOf(
                PathSketch.Waypoint("sender", 0.0, 0.0),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -84.0),
            ),
        )
        // Treated as "no position" and inferred, not plotted at (0,0).
        assertEquals(PathSketch.Certainty.Inferred, s.nodes.first().certainty)
        assertTrue(s.nodes.first().point!!.latitude > 40.0)
    }

    @Test
    fun `an out-of-range position is rejected rather than drawn`() {
        val s = PathSketch.build(
            listOf(
                PathSketch.Waypoint("sender", 999.0, 999.0),
                known("R1", 43.0, -85.0),
                known("R2", 43.0, -84.0),
            ),
        )
        assertEquals(PathSketch.Certainty.Inferred, s.nodes.first().certainty)
    }

    // --- what the map tells the reader -------------------------------------

    @Test
    fun `the summary distinguishes located from approximated`() {
        val s = PathSketch.build(
            listOf(
                noPosition("companion"),
                known("R1", 43.0, -85.0),
                unidentified("??"),
                known("R3", 43.0, -84.0),
            ),
        )
        val summary = s.summary()
        assertTrue(summary.contains("2 of 4 located"), summary)
        assertTrue(summary.contains("1 placed approximately"), summary)
        assertTrue(summary.contains("1 not placed"), summary)
    }

    @Test
    fun `the legend explains both non-solid styles`() {
        // A dotted line and a dashed line mean different things, and an
        // unexplained convention is its own kind of overclaim.
        assertTrue(PathSketch.LEGEND.contains("dotted"))
        assertTrue(PathSketch.LEGEND.contains("dashed"))
    }

    @Test
    fun `inference is deterministic across repeated builds`() {
        val chain = listOf(
            noPosition("companion"),
            known("R1", 43.0, -85.0),
            noPosition("R2"),
            known("R3", 43.5, -84.0),
            noPosition("me"),
        )
        val a = PathSketch.build(chain)
        val b = PathSketch.build(chain)
        assertEquals(a.nodes.map { it.point }, b.nodes.map { it.point })
    }
}
