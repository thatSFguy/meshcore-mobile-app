package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remembered-paths table (PARITY §13).
 *
 * The two assertions that matter: a hop count is not a byte count, and
 * the flood route — the empty path — is never mistaken for junk. Both
 * were live defects on the phone.
 */
class PathHistoryHygieneTest {

    @Test
    fun aHopCountIsNotAByteCount() {
        // BYRONCTR's real stored route: b389 c985 — two hops, four
        // bytes, on a mesh with 2-byte hop hashes. Every write site used
        // to store 4 and the sheet read "4 hop(s)".
        assertEquals(2, PathHistoryHygiene.hopCount(4, 2))
        assertEquals(4, PathHistoryHygiene.hopCount(4, 1))
        assertEquals(1, PathHistoryHygiene.hopCount(4, 4))
    }

    @Test
    fun floodIsAValidRouteNotAnEmptyRow() {
        // "" is the flood route: the one entry that always works.
        assertNull(PathHistoryHygiene.defect("", 1))
        assertNull(PathHistoryHygiene.defect("", 2))
        assertTrue(PathHistoryHygiene.isUsable("", 4))
    }

    @Test
    fun theRealCapturedRouteSurvives() {
        assertNull(PathHistoryHygiene.defect("b389c985", 2))
        assertEquals(2, PathHistoryHygiene.hopCount("b389c985".length / 2, 2))
    }

    @Test
    fun theZeroPaddedRowsAnOlderBuildWroteAreRecognised() {
        // `copyOfRange(0, pathLen)` with pathLen read as a byte length
        // wrote the whole 64-byte buffer: two real hops then padding.
        val junk = "b389c985" + "00".repeat(60)
        assertEquals(PathHistoryHygiene.Defect.ZeroHop, PathHistoryHygiene.defect(junk, 2))
        // At width 1 the same row trips the hop cap first — 64 hops is
        // more than a path_len byte can encode. Different reason, same
        // verdict, which is what the repair pass acts on.
        assertEquals(PathHistoryHygiene.Defect.TooLong, PathHistoryHygiene.defect(junk, 1))
        for (width in 1..4) assertFalse(PathHistoryHygiene.isUsable(junk, width), "width $width")
    }

    @Test
    fun aZeroHopAnywhereIsJunkNotJustTrailing() {
        assertEquals(PathHistoryHygiene.Defect.ZeroHop, PathHistoryHygiene.defect("0000b389", 2))
        assertEquals(PathHistoryHygiene.Defect.ZeroHop, PathHistoryHygiene.defect("b3890000", 2))
    }

    @Test
    fun aHopThatMerelyCONTAINSZerosIsFine() {
        // "b300" is a perfectly ordinary hop; only an ALL-zero hop is
        // the padding signature. Over-eager matching here would delete
        // real routes.
        assertNull(PathHistoryHygiene.defect("b300", 2))
        assertNull(PathHistoryHygiene.defect("00b3", 2))
        // At width 1 the same bytes ARE two hops, one of them zero.
        assertEquals(PathHistoryHygiene.Defect.ZeroHop, PathHistoryHygiene.defect("b300", 1))
    }

    @Test
    fun hostileHexIsRejected() {
        assertEquals(PathHistoryHygiene.Defect.NotHex, PathHistoryHygiene.defect("b38", 2))
        assertEquals(PathHistoryHygiene.Defect.NotHex, PathHistoryHygiene.defect("zzzz", 2))
        assertEquals(PathHistoryHygiene.Defect.NotHex, PathHistoryHygiene.defect("b3 9", 2))
    }

    @Test
    fun aPathTooLongForTheRecordIsRejected() {
        val huge = "ab".repeat(Codes.MAX_PATH_SIZE + 1)
        assertEquals(PathHistoryHygiene.Defect.TooLong, PathHistoryHygiene.defect(huge, 1))
    }

    @Test
    fun aPathThatDoesNotDivideIntoWholeHopsIsRejected() {
        // Three bytes at 2 bytes per hop is one hop and a half — either
        // the width is wrong or the row is corrupt. Either way it must
        // not be offered as a route.
        assertEquals(PathHistoryHygiene.Defect.NotWholeHops, PathHistoryHygiene.defect("b389c9", 2))
        assertNull(PathHistoryHygiene.defect("b389c9", 1))
    }

    @Test
    fun everyUsablePathRoundTripsThroughThePathLenEncoding() {
        // A row we keep must be one the radio will accept back.
        for (width in 1..4) {
            for (hops in 1..PathCodec.maxHopsFor(width)) {
                val hex = "ab".repeat(hops * width)
                assertNull(PathHistoryHygiene.defect(hex, width), "width $width hops $hops")
                val info = PathCodec.decodePathLen(PathCodec.encodePathLen(hops, width))
                assertEquals(hops, info.hops, "width $width hops $hops")
                assertEquals(hex.length / 2, info.byteLength, "width $width hops $hops")
            }
            // One hop past the cap must be refused at every width — 63
            // hops at 4 bytes encodes to 0xFF, the flood sentinel.
            val over = "ab".repeat((PathCodec.maxHopsFor(width) + 1) * width)
            assertEquals(PathHistoryHygiene.Defect.TooLong, PathHistoryHygiene.defect(over, width))
        }
    }

    @Test
    fun everyDefectHasWording() {
        for (d in PathHistoryHygiene.Defect.entries) {
            assertTrue(PathHistoryHygiene.explain(d).isNotBlank())
        }
    }
}
