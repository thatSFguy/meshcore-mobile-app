package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `path_len` on an ARRIVAL, which is the opposite of `path_len` on a
 * contact record.
 *
 * The firmware writes
 * `pkt->isRouteFlood() ? pkt->path_len : 0xFF` (`MyMesh::queueMessage`),
 * so 0xFF here means the packet was NOT flooded. Read the other way
 * round — which is how it shipped until 2026-09-22 — every routed
 * message is labelled "flood" and every flooded one is reported as
 * though it had been routed, silently and plausibly.
 *
 * Both readings of the byte are exercised here side by side, because
 * the two living in one codebase is the actual hazard.
 */
class ArrivalPathLenTest {

    @Test
    fun `0xFF on an arrival means routed and not flooded`() {
        val a = PathCodec.decodeArrival(0xFF)
        assertFalse(a.flooded, "0xFF is the NON-flood case on an arrival")
        assertNull(a.hops, "a routed packet consumes its path and reports no count")
        assertEquals(PathCodec.HOPS_ROUTED, a.storedHops)
    }

    @Test
    fun `readInt8's spelling of 0xFF is the same case`() {
        // The legacy channel frame is read with readInt8, which hands
        // back -1. Accepting only one spelling left the other decoding
        // as a hop count.
        assertEquals(PathCodec.decodeArrival(0xFF), PathCodec.decodeArrival(-1))
    }

    @Test
    fun `a real value on an arrival means it flooded and states the count`() {
        // 0x44: width mode 1 (2-byte hashes), 4 hops — the live contact
        // byte already pinned in PathLenCodecTest, seen here as an
        // arrival instead of as a stored path.
        val a = PathCodec.decodeArrival(0x44)
        assertTrue(a.flooded)
        assertEquals(4, a.hops)
        assertEquals(4, a.storedHops)
    }

    @Test
    fun `a flooded arrival with no repeater states zero rather than unknown`() {
        val a = PathCodec.decodeArrival(0x00)
        assertTrue(a.flooded)
        assertEquals(0, a.hops, "heard straight from the sender IS a count of zero")
    }

    @Test
    fun `the hop counts from the nine-copy case decode as stated`() {
        // 4-11 hops observed on one contact, at 2-byte hashes (mode 1).
        for (hops in 4..11) {
            val raw = (1 shl 6) or hops
            assertEquals(hops, PathCodec.decodeArrival(raw).hops)
            assertTrue(PathCodec.decodeArrival(raw).flooded)
        }
    }

    @Test
    fun `the contact-record reading of the same byte is unchanged`() {
        // THE POINT OF THE SPLIT. On a contact record 0xFF really does
        // mean "no stored path, so packets flood" — decodePathLen must
        // keep saying so, or fixing the arrival reading breaks the
        // routing screens instead.
        val stored = PathCodec.decodePathLen(0xFF)
        assertTrue(stored.isFlood, "a contact with no stored path floods")
        assertEquals(-1, stored.hops)
        // ...and the two readings disagree about this byte on purpose.
        assertFalse(PathCodec.decodeArrival(0xFF).flooded)
    }

    @Test
    fun `an arrival and a stored path agree about a real hop count`() {
        // Only the sentinel is inverted; the packed encoding is shared.
        for (raw in 0..0xFE) {
            assertEquals(PathCodec.decodePathLen(raw).hops, PathCodec.decodeArrival(raw).hops)
        }
    }
}
