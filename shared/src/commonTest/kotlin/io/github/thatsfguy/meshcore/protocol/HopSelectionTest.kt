package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picking and ordering hops instead of typing them (PARITY §13).
 *
 * The load-bearing assertions here are about the **hash width**. It has
 * caused four defects in this codebase, every one of them the same
 * shape: a width-dependent value computed once, at the wrong width,
 * then carried around as if it were the truth. So the tests below care
 * less about "does add() append" than about what happens when the width
 * turns out to be 2 after the fact — the case that shipped broken.
 */
class HopSelectionTest {

    // Real keys from this mesh: BYRONCTR's stored 2-hop route was
    // `b389 c985`, and b389 is SpartaMI, confirmed by a live trace that
    // came back in ~1s. Pinning the captured value, not a property.
    private val sparta = "b389" + "5a".repeat(30)
    private val byron = "c985" + "6b".repeat(30)

    private val contacts = mapOf(sparta to "SpartaMI", byron to "BYRONCTR")

    // --- width, the whole point ------------------------------------------

    @Test
    fun pickedHopFollowsTheMeshWidth() {
        val hop = HopSelection.fromContact(sparta, "SpartaMI")
        // The picker used to hardcode take(2) — one byte — so on this
        // mesh (2-byte hops) every tapped repeater inserted half a hop.
        assertEquals("b3", hop.hashHex(1))
        assertEquals("b389", hop.hashHex(2))
        assertEquals("b3895a5a", hop.hashHex(4))
    }

    @Test
    fun aWidthArrivingLateCorrectsThePickedRouteInsteadOfPinningItWrong() {
        // DEVICE_INFO can land after the sheet opens: the selection is
        // made while we still believe width 1, then the radio says 2.
        val hops = listOf(
            HopSelection.fromContact(sparta, "SpartaMI"),
            HopSelection.fromContact(byron, "BYRONCTR"),
        )
        assertEquals("b3c9", HopSelection.toHex(hops, 1))
        assertEquals("b389c985", HopSelection.toHex(hops, 2))
    }

    @Test
    fun aLiteralHashCapturedAtOneWidthDoesNotSilentlyBecomeAnotherRoute() {
        val hop = HopSelection.fromHash("b389")
        assertEquals("b389", hop.hashHex(2))
        // Truncating "b389" to "b3" would be a DIFFERENT node, not a
        // narrower description of the same one.
        assertNull(hop.hashHex(1))
        assertNull(hop.hashHex(4))
    }

    @Test
    fun anUnresolvableHopFailsTheWholeRouteRatherThanShorteningIt() {
        val hops = listOf(
            HopSelection.fromContact(sparta, "SpartaMI"),
            HopSelection.fromHash("b3"), // captured at width 1
        )
        assertNull(HopSelection.toBytes(hops, 2))
        assertEquals(listOf(1), HopSelection.unresolvedIndices(hops, 2))
    }

    @Test
    fun aKeyShorterThanTheWidthIsUnresolvedNotPadded() {
        val stub = HopSelection.fromContact("b3", "Stub")
        assertEquals("b3", stub.hashHex(1))
        assertNull(stub.hashHex(2))
    }

    // --- hostile input ----------------------------------------------------

    @Test
    fun nonHexNeverReachesTheWire() {
        // A name field, a QR, a pasted route — all attacker-controlled.
        assertNull(HopSelection.fromContact("zzzz" + "11".repeat(30), "Evil").hashHex(2))
        assertNull(HopSelection.fromHash("zz").hashHex(1))
        assertNull(HopSelection.fromHash("").hashHex(1))
        assertNull(HopSelection.Hop().hashHex(2))
    }

    @Test
    fun anUnusableHopStillRendersSomethingTheUserCanSee() {
        // An invisible row in an ordered list is a route you cannot tell
        // is wrong.
        assertEquals("(unusable hop)", HopSelection.Hop().label(2))
        assertEquals("zz", HopSelection.fromHash("zz").label(1))
    }

    @Test
    fun caseAndWhitespaceAreNormalisedBeforeComparison() {
        assertEquals("b389", HopSelection.fromContact("  B389" + "5A".repeat(30), null).hashHex(2))
        assertEquals("b389", HopSelection.fromHash(" B389 ").hashHex(2))
    }

    // --- ordering ---------------------------------------------------------

    @Test
    fun reorderingSwapsNeighboursAndIgnoresTheEdges() {
        val a = HopSelection.fromHash("aa")
        val b = HopSelection.fromHash("bb")
        val c = HopSelection.fromHash("cc")
        val hops = listOf(a, b, c)

        assertEquals(listOf(b, a, c), HopSelection.move(hops, 1, -1))
        assertEquals(listOf(a, c, b), HopSelection.move(hops, 1, +1))
        // Up from the top and down from the bottom are no-ops, not
        // wraps: a route that quietly reorders itself is unusable.
        assertEquals(hops, HopSelection.move(hops, 0, -1))
        assertEquals(hops, HopSelection.move(hops, 2, +1))
        assertEquals(hops, HopSelection.move(hops, 9, -1))
    }

    @Test
    fun orderIsTheRouteSoASwapChangesTheBytes() {
        val hops = listOf(
            HopSelection.fromContact(sparta, "SpartaMI"),
            HopSelection.fromContact(byron, "BYRONCTR"),
        )
        assertEquals("b389c985", HopSelection.toHex(hops, 2))
        assertEquals("c985b389", HopSelection.toHex(HopSelection.move(hops, 0, +1), 2))
    }

    @Test
    fun removeTakesOnlyTheIndexedHop() {
        val hops = listOf(HopSelection.fromHash("aa"), HopSelection.fromHash("bb"))
        assertEquals("bb", HopSelection.toHex(HopSelection.removeAt(hops, 0), 1))
        assertEquals(hops, HopSelection.removeAt(hops, 5))
        assertEquals(hops, HopSelection.removeAt(hops, -1))
    }

    // --- capacity ---------------------------------------------------------

    @Test
    fun addStopsAtTheRecordCapacityRatherThanTruncatingOnApply() {
        for (width in 1..4) {
            val cap = PathCodec.maxHopsFor(width)
            var hops = emptyList<HopSelection.Hop>()
            repeat(cap + 5) {
                hops = HopSelection.add(hops, HopSelection.fromContact(sparta, "S"), width)
            }
            assertEquals(cap, hops.size, "width $width")
            // Whatever add() allowed must actually encode. The old
            // failure mode was a list that looked accepted and then lost
            // hops silently at send time.
            assertNotNull(HopSelection.toBytes(hops, width), "width $width")
        }
    }

    @Test
    fun anOverlongRouteIsRefusedNotTrimmed() {
        val width = 2
        val tooMany = List(PathCodec.maxHopsFor(width) + 1) {
            HopSelection.fromContact(sparta, "S")
        }
        assertNull(HopSelection.toBytes(tooMany, width))
    }

    @Test
    fun everyWidthEncodesToTheRightByteCount() {
        for (width in 1..4) {
            for (n in 0..PathCodec.maxHopsFor(width)) {
                val hops = List(n) { HopSelection.fromContact(sparta, "S") }
                val bytes = HopSelection.toBytes(hops, width)
                assertNotNull(bytes, "width $width n $n")
                assertEquals(n * width, bytes.size, "width $width n $n")
                // And the encoded length must survive the path_len
                // round-trip that 63-hops-at-4-bytes once broke by
                // colliding with the flood sentinel.
                val decoded = PathCodec.decodePathLen(PathCodec.encodePathLen(n, width))
                assertEquals(n, decoded.hops, "width $width n $n")
                assertEquals(width, decoded.hashWidth, "width $width n $n")
            }
        }
    }

    // --- round trips ------------------------------------------------------

    @Test
    fun aStoredPathComesBackNamedWhenTheMatchIsUnambiguous() {
        val path = byteArrayOf(0xb3.toByte(), 0x89.toByte(), 0xc9.toByte(), 0x85.toByte())
        val hops = HopSelection.fromPath(path, 2, contacts)
        assertEquals(listOf("SpartaMI", "BYRONCTR"), hops.map { it.name })
        assertEquals("b389c985", HopSelection.toHex(hops, 2))
    }

    @Test
    fun anAmbiguousHopStaysAHashInsteadOfAdoptingOneCandidatesKey() {
        // Two contacts sharing a 1-byte hop is ordinary, not exotic.
        val twins = mapOf(
            "b3" + "11".repeat(31) to "One",
            "b3" + "22".repeat(31) to "Two",
        )
        val hops = HopSelection.fromPath(byteArrayOf(0xb3.toByte()), 1, twins)
        assertEquals(1, hops.size)
        assertNull(hops[0].name)
        assertNull(hops[0].keyHex)
        // Adopting a candidate's key would silently rewrite the route to
        // a DIFFERENT node the moment the width widened.
        assertEquals("b3", hops[0].hashHex(1))
        assertNull(hops[0].hashHex(2))
    }

    @Test
    fun unknownHopsSurviveAsHashes() {
        val hops = HopSelection.fromPath(byteArrayOf(0x7f, 0x01), 1, contacts)
        assertEquals(listOf("7f", "01"), hops.map { it.hashHex(1) })
        assertEquals("7f01", HopSelection.toHex(hops, 1))
    }

    @Test
    fun freeTextRoundTripsThroughTheSameCodecTheEditorUses() {
        val hops = HopSelection.fromTokens("b389 c985", 2)
        assertNotNull(hops)
        assertEquals(2, hops.size)
        assertEquals("b389 c985", HopSelection.toTokens(hops, 2))
        assertEquals("b389c985", HopSelection.toHex(hops, 2))
    }

    @Test
    fun freeTextAtTheWrongWidthIsRejectedRatherThanReinterpreted() {
        // "b3 89" is two hops at width 1 and nonsense at width 2. The
        // Apply-path bug was exactly this, silently.
        assertNull(HopSelection.fromTokens("b3 89", 2))
        assertEquals(2, HopSelection.fromTokens("b3 89", 1)?.size)
        assertNull(HopSelection.fromTokens("nothex", 1))
    }

    @Test
    fun emptyIsAnEmptyRouteNotAFailure() {
        assertEquals(0, HopSelection.toBytes(emptyList(), 2)?.size)
        assertEquals(emptyList(), HopSelection.fromPath(ByteArray(0), 2, contacts))
        assertEquals(emptyList(), HopSelection.fromTokens("   ", 2))
        assertTrue(HopSelection.toTokens(emptyList(), 2).isEmpty())
    }
}
