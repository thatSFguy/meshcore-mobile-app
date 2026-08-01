package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plotting a mesh path (PARITY §9).
 *
 * The load-bearing assertion is that an ambiguous hop is NOT drawn. A
 * map that guesses looks exactly like a map that knows, which makes a
 * wrong route more harmful than a missing one — this is PARITY §12
 * ("hop hashes are not identities") applied to geometry.
 */
class PathGeometryTest {

    private fun contact(key: String, name: String, lat: Double?, lon: Double?) =
        PathGeometry.PositionedContact(key, name, lat, lon)

    private val alice = contact("aa" + "11".repeat(31), "Alice", 37.77, -122.42)
    private val bob = contact("bb" + "22".repeat(31), "Bob", 37.80, -122.40)
    private val noPos = contact("cc" + "33".repeat(31), "Nowhere", null, null)
    private val zeroPos = contact("dd" + "44".repeat(31), "Unset", 0.0, 0.0)

    @Test
    fun plotsHopsThatResolveToOnePositionedContact() {
        val path = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val plot = PathGeometry.plot(path, 1, listOf(alice, bob))
        assertEquals(2, plot.hops.size)
        assertTrue(plot.hops.all { it.isPlotted })
        assertEquals("Alice", plot.hops[0].name)
        assertEquals(37.77, plot.hops[0].latitude)
        assertEquals("Bob", plot.hops[1].name)
        assertFalse(plot.hasGaps)
        assertEquals("All 2 hop(s) located.", plot.summary())
    }

    @Test
    fun anAmbiguousHopIsNeverDrawn() {
        // Two contacts share the leading byte. Picking one would put a
        // line through a node that may have had nothing to do with it.
        val twin = contact("aa" + "99".repeat(31), "Alice's twin", 40.0, -70.0)
        val plot = PathGeometry.plot(byteArrayOf(0xAA.toByte()), 1, listOf(alice, twin))
        assertEquals(1, plot.hops.size)
        assertFalse(plot.hops[0].isPlotted)
        assertEquals(PathGeometry.Gap.Ambiguous, plot.hops[0].gap)
        assertNull(plot.hops[0].name, "an ambiguous hop must not be named either")
        assertTrue(plot.plotted.isEmpty())
    }

    @Test
    fun aHopWithNoMatchingContactIsAGapNotAnError() {
        val plot = PathGeometry.plot(byteArrayOf(0x77), 1, listOf(alice))
        assertEquals(PathGeometry.Gap.NoMatch, plot.hops[0].gap)
        assertFalse(plot.hops[0].isPlotted)
    }

    @Test
    fun aKnownNodeWithNoPositionIsNamedButNotPlotted() {
        val plot = PathGeometry.plot(byteArrayOf(0xCC.toByte()), 1, listOf(noPos))
        assertEquals(PathGeometry.Gap.NoPosition, plot.hops[0].gap)
        assertEquals("Nowhere", plot.hops[0].name, "we know who it is, just not where")
        assertFalse(plot.hops[0].isPlotted)
    }

    @Test
    fun theAllZeroPositionIsTreatedAsUnsetNotAsNullIsland() {
        // 0,0 is MeshCore's "no position", and plotting it would put a
        // node in the Gulf of Guinea.
        val plot = PathGeometry.plot(byteArrayOf(0xDD.toByte()), 1, listOf(zeroPos))
        assertEquals(PathGeometry.Gap.NoPosition, plot.hops[0].gap)
        assertFalse(plot.hops[0].isPlotted)
    }

    @Test
    fun outOfRangeCoordinatesAreRefused() {
        val bogus = contact("ee" + "55".repeat(31), "Bogus", 91.0, 200.0)
        val plot = PathGeometry.plot(byteArrayOf(0xEE.toByte()), 1, listOf(bogus))
        assertFalse(plot.hops[0].isPlotted)
    }

    @Test
    fun widerHashesCollideLessAndPlotMore() {
        // The same two contacts: ambiguous at 1 byte, distinct at 2.
        val twin = contact("aa" + "99".repeat(31), "Twin", 40.0, -70.0)
        val oneByte = PathGeometry.plot(byteArrayOf(0xAA.toByte()), 1, listOf(alice, twin))
        assertEquals(PathGeometry.Gap.Ambiguous, oneByte.hops[0].gap)

        val twoByte = PathGeometry.plot(
            byteArrayOf(0xAA.toByte(), 0x11), 2, listOf(alice, twin),
        )
        assertTrue(twoByte.hops[0].isPlotted)
        assertEquals("Alice", twoByte.hops[0].name)
    }

    @Test
    fun anEmptyPathIsAFloodedContactNotAnError() {
        val plot = PathGeometry.plot(ByteArray(0), 1, listOf(alice))
        assertTrue(plot.hops.isEmpty())
        assertFalse(plot.hasGaps)
        assertTrue("flooding" in plot.summary())
    }

    @Test
    fun aMixedPathReportsWhatItCouldAndCouldNotDo() {
        val path = byteArrayOf(0xAA.toByte(), 0x77, 0xBB.toByte())
        val plot = PathGeometry.plot(path, 1, listOf(alice, bob))
        assertEquals(3, plot.hops.size)
        assertEquals(2, plot.plotted.size)
        assertEquals(1, plot.gaps)
        assertTrue(plot.summary().startsWith("2 of 3 hop(s) located"))
    }

    @Test
    fun aPathWhereNothingResolvesSaysSoRatherThanDrawingNothingSilently() {
        val plot = PathGeometry.plot(byteArrayOf(0x77, 0x78), 1, listOf(alice))
        assertTrue(plot.plotted.isEmpty())
        assertTrue("nothing to draw" in plot.summary())
    }

    @Test
    fun everyGapHasAnExplanation() {
        for (gap in PathGeometry.Gap.entries) {
            assertTrue(PathGeometry.gapReason(gap).isNotBlank(), "$gap has no wording")
        }
    }

    @Test
    fun hashWidthIsClampedRatherThanTrusted() {
        // The width comes off the wire; a nonsense value must not crash
        // or read past the path.
        for (width in listOf(-1, 0, 5, 99)) {
            PathGeometry.plot(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), width, listOf(alice))
        }
    }
}
