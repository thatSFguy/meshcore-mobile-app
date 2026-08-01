package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeater neighbour tables (PARITY §6).
 *
 * The bytes come off the mesh via a node we don't control, so the
 * parser's job is to refuse anything that isn't a well-formed table —
 * especially a count the payload cannot back, which is the classic way
 * to walk a reader off the end.
 */
class NeighboursTest {

    private fun body(
        total: Int,
        entries: List<Triple<String, Long, Int>>,
        declaredCount: Int = entries.size,
    ): ByteArray {
        val w = BufferWriter()
        w.writeUInt16LE(total)
        w.writeUInt16LE(declaredCount)
        for ((prefix, lastHeard, snrQuarters) in entries) {
            w.writeBytes(
                ByteArray(4) { prefix.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
            )
            w.writeUInt32LE(lastHeard)
            w.writeByte(snrQuarters and 0xFF)
        }
        return w.toBytes()
    }

    @Test
    fun parsesAWellFormedTable() {
        val table = Neighbours.parse(
            body(2, listOf("aabbccdd" to 1_700_000_000L to 40, "11223344" to 1_700_000_100L to -20)
                .map { Triple(it.first.first, it.first.second, it.second) }),
        )!!
        assertEquals(2, table.entries.size)
        assertEquals("aabbccdd", table.entries[0].keyPrefixHex)
        assertEquals(1_700_000_000L, table.entries[0].lastHeard)
        // Quarter-dB steps: 40 → 10.0 dB, -20 → -5.0 dB.
        assertEquals(10.0, table.entries[0].snr)
        assertEquals(-5.0, table.entries[1].snr)
        assertTrue(!table.isPartial)
    }

    @Test
    fun reportsAPagedReplyAsPartial() {
        val table = Neighbours.parse(
            body(9, listOf(Triple("aabbccdd", 1L, 0))),
        )!!
        assertEquals(9, table.total)
        assertEquals(1, table.entries.size)
        assertTrue(table.isPartial, "a page of a longer list must say so")
    }

    @Test
    fun refusesACountThePayloadCannotBack() {
        // The walk-off-the-end case: claim 50 entries, supply one.
        val lying = body(50, listOf(Triple("aabbccdd", 1L, 0)), declaredCount = 50)
        assertNull(Neighbours.parse(lying))
    }

    @Test
    fun refusesAnAbsurdCount() {
        val w = BufferWriter()
        w.writeUInt16LE(65_535)
        w.writeUInt16LE(65_535)
        assertNull(Neighbours.parse(w.toBytes()))
    }

    @Test
    fun refusesShortAndTruncatedBodies() {
        assertNull(Neighbours.parse(ByteArray(0)))
        assertNull(Neighbours.parse(ByteArray(3)))
        val full = body(1, listOf(Triple("aabbccdd", 1L, 0)))
        // Every truncation of a valid table must be refused, not
        // half-parsed.
        for (n in 4 until full.size) {
            assertNull(Neighbours.parse(full.copyOfRange(0, n)), "accepted $n bytes")
        }
    }

    @Test
    fun anEmptyTableIsValidAndDistinctFromAFailure() {
        val table = Neighbours.parse(body(0, emptyList()))
        assertTrue(table != null && table.entries.isEmpty())
        assertTrue(!table.isPartial)
    }

    @Test
    fun snrCoversTheWholeSignedRange() {
        val table = Neighbours.parse(
            body(
                3,
                listOf(
                    Triple("00000000", 0L, 127),   //  31.75 dB
                    Triple("11111111", 0L, -128),  // -32.0 dB
                    Triple("22222222", 0L, 0),
                ),
            ),
        )!!
        assertEquals(31.75, table.entries[0].snr)
        assertEquals(-32.0, table.entries[1].snr)
        assertEquals(0.0, table.entries[2].snr)
    }

    @Test
    fun resolutionReportsEveryMatchAndNeverPicks() {
        // 4 bytes is 32 bits — unlikely to collide by accident, cheap to
        // collide on purpose. Two matches stay two matches.
        val n = Neighbours.Neighbour("aabbccdd", 0, 0.0)
        val keys = listOf("aabbccdd" + "11".repeat(30), "aabbccdd" + "22".repeat(30), "ffffffff")
        val hits = Neighbours.resolve(n, keys) { it }
        assertEquals(2, hits.size)
        assertEquals(emptyList(), Neighbours.resolve(n, listOf("ffffffff")) { it })
    }

    @Test
    fun labelsSayWhenTheyAreUnsure() {
        val n = Neighbours.Neighbour("aabbccdd", 0, 0.0)
        assertEquals("aabbccdd Blue Ridge", Neighbours.label(n, listOf("Blue Ridge")))
        assertEquals("aabbccdd (2 matches)", Neighbours.label(n, listOf("A", "B")))
        assertEquals("aabbccdd", Neighbours.label(n, emptyList()))
    }

    @Test
    fun theRequestPayloadIsJustTheType() {
        assertEquals(
            listOf(Codes.REQ_TYPE_GET_NEIGHBORS.toByte()),
            Neighbours.requestPayload().toList(),
        )
    }
}
