package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeater neighbour tables (PARITY §6).
 *
 * Two jobs here, and the suite used to do only one of them.
 *
 * The **parser** takes bytes off the mesh via a node we don't control,
 * so it must refuse anything that isn't a well-formed table — especially
 * a count the payload cannot back, which is the classic way to walk a
 * reader off the end.
 *
 * The **builder** has to satisfy a reader we don't control either, and
 * that half shipped broken behind nine green receive-side tests: the
 * request was one byte, the firmware reads eleven, and it took `count`
 * from whatever memory followed. `theRequestMatchesWhatTheFirmwareReads`
 * is the test that would have caught it, and it is written against
 * `examples/simple_repeater/MyMesh.cpp:279-294` (v1.16.0), not against
 * our own parser.
 */
class NeighboursTest {

    private fun body(
        total: Int,
        entries: List<Triple<String, Long, Int>>,
        declaredCount: Int = entries.size,
        prefixBytes: Int = Neighbours.DEFAULT_PREFIX_BYTES,
    ): ByteArray {
        val w = BufferWriter()
        w.writeUInt16LE(total)
        w.writeUInt16LE(declaredCount)
        for ((prefix, heardAgo, snrQuarters) in entries) {
            w.writeBytes(
                ByteArray(prefixBytes) { prefix.substring(it * 2, it * 2 + 2).toInt(16).toByte() },
            )
            w.writeUInt32LE(heardAgo)
            w.writeByte(snrQuarters and 0xFF)
        }
        return w.toBytes()
    }

    // --- The builder: what the firmware's reader actually consumes ---

    @Test
    fun theRequestMatchesWhatTheFirmwareReads() {
        val nonce = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val bytes = Neighbours.Request(
            offset = 0x0102,
            orderBy = Neighbours.Order.STRONGEST_FIRST,
            prefixBytes = 6,
            count = 11,
        ).payload(nonce)

        // MyMesh.cpp reads payload[0..10]. A shorter request does not
        // fail — it silently reads whatever follows, which is how this
        // shipped returning zero rows.
        assertEquals(Neighbours.REQUEST_BYTES, bytes.size)
        assertEquals(Codes.REQ_TYPE_GET_NEIGHBORS, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[1].toInt(), "request_version: only 0 is handled")
        assertEquals(11, bytes[2].toInt() and 0xFF, "count is payload[2]")
        // offset is memcpy'd as a little-endian u16 from payload[3..4].
        assertEquals(0x02, bytes[3].toInt() and 0xFF)
        assertEquals(0x01, bytes[4].toInt() and 0xFF)
        assertEquals(Neighbours.Order.STRONGEST_FIRST, bytes[5].toInt())
        assertEquals(6, bytes[6].toInt(), "pubkey_prefix_length is payload[6]")
        assertEquals(nonce.toList(), bytes.copyOfRange(7, 11).toList())
    }

    @Test
    fun theDefaultRequestAsksForRowsRatherThanNone() {
        // The whole defect in one assertion: count must not be zero.
        val bytes = Neighbours.Request().payload(ByteArray(4))
        assertTrue(
            (bytes[2].toInt() and 0xFF) > 0,
            "a count of 0 makes the firmware's fill loop never run",
        )
        assertEquals(Neighbours.DEFAULT_PREFIX_BYTES, bytes[6].toInt())
    }

    @Test
    fun countNeverExceedsWhatAPageCanCarry() {
        // results_buffer is 130 bytes; entries are prefix+5. Asking for
        // more just truncates, and then `total` vs `count` looks like
        // paging when it was our own over-ask.
        for (prefix in 1..Neighbours.MAX_PREFIX_BYTES) {
            val req = Neighbours.Request(prefixBytes = prefix, count = 255)
            val fits = Neighbours.RESULTS_BUFFER_BYTES / (prefix + 5)
            assertEquals(fits, req.effectiveCount, "prefix $prefix")
            assertTrue(
                req.effectiveCount * (prefix + 5) <= Neighbours.RESULTS_BUFFER_BYTES,
                "prefix $prefix overflows the node's results buffer",
            )
        }
    }

    @Test
    fun prefixLengthIsClampedTheWayTheFirmwareClampsIt() {
        // The firmware clamps >PUB_KEY_SIZE down to 32. If we asked for
        // 40 and parsed at 40, every entry would be misaligned.
        val req = Neighbours.Request(prefixBytes = 40)
        assertEquals(Neighbours.MAX_PREFIX_BYTES, req.effectivePrefixBytes)
        assertEquals(Neighbours.MAX_PREFIX_BYTES, req.payload(ByteArray(4))[6].toInt())
    }

    @Test
    fun aRequestRefusesABadNonceOrOffset() {
        assertFailsWith<IllegalArgumentException> {
            Neighbours.Request().payload(ByteArray(3))
        }
        assertFailsWith<IllegalArgumentException> {
            Neighbours.Request(offset = 70_000).payload(ByteArray(4))
        }
    }

    @Test
    fun twoRequestsWithDifferentNoncesDifferOnTheWire() {
        // Identical packets hash alike and the mesh drops the duplicate,
        // so "fetch again" would silently do nothing.
        val a = Neighbours.Request().payload(byteArrayOf(1, 2, 3, 4))
        val b = Neighbours.Request().payload(byteArrayOf(5, 6, 7, 8))
        assertTrue(!a.contentEquals(b))
    }

    // --- The parser ---

    @Test
    fun parsesAWellFormedTable() {
        val table = Neighbours.parse(
            body(
                2,
                listOf(
                    Triple("aabbccddeeff", 30L, 40),
                    Triple("112233445566", 7_200L, -20),
                ),
            ),
        )!!
        assertEquals(2, table.entries.size)
        assertEquals("aabbccddeeff", table.entries[0].keyPrefixHex)
        assertEquals(30L, table.entries[0].heardSecondsAgo)
        // Quarter-dB steps: 40 → 10.0 dB, -20 → -5.0 dB.
        assertEquals(10.0, table.entries[0].snr)
        assertEquals(-5.0, table.entries[1].snr)
        assertTrue(!table.isPartial)
    }

    @Test
    fun parsesAtWhateverWidthWasRequested() {
        // The width is a parameter we chose, not a constant. Parsing a
        // 4-byte-prefix reply as 6 would slide every field.
        val req = Neighbours.Request(prefixBytes = 4)
        val table = Neighbours.parse(
            body(1, listOf(Triple("aabbccdd", 99L, 40)), prefixBytes = 4),
            req,
        )!!
        assertEquals("aabbccdd", table.entries[0].keyPrefixHex)
        assertEquals(99L, table.entries[0].heardSecondsAgo)
        assertEquals(10.0, table.entries[0].snr)
    }

    @Test
    fun aWidthMismatchIsRefusedRatherThanMisread() {
        // 4-byte entries read at the 6-byte default: 9 bytes of payload
        // cannot back one 11-byte entry.
        val fourByte = body(1, listOf(Triple("aabbccdd", 99L, 40)), prefixBytes = 4)
        assertNull(Neighbours.parse(fourByte, Neighbours.Request()))
    }

    @Test
    fun pagingAccountsForTheOffsetItAskedAt() {
        val page2 = Neighbours.parse(
            body(9, listOf(Triple("aabbccddeeff", 1L, 0))),
            Neighbours.Request(offset = 5),
        )!!
        assertEquals(9, page2.total)
        assertEquals(5, page2.offset)
        assertEquals(6, page2.nextOffset)
        assertTrue(page2.isPartial, "5 seen + 1 here < 9 known")
        // And the last page closes it out rather than looping forever.
        val last = Neighbours.parse(
            body(9, listOf(Triple("aabbccddeeff", 1L, 0))),
            Neighbours.Request(offset = 8),
        )!!
        assertTrue(!last.isPartial)
    }

    @Test
    fun anEmptyFirstPageWithRowsKnownIsFlaggedAsARejectedRequest() {
        // This is the exact reply the broken one-byte request produced:
        // total=2, count=0. It must not read as "paged".
        val t = Neighbours.parse(body(2, emptyList()))!!
        assertTrue(t.isEmptyButNotEmpty)
        // Distinguish it from a genuinely empty table...
        assertTrue(!Neighbours.parse(body(0, emptyList()))!!.isEmptyButNotEmpty)
        // ...and from a real page boundary.
        assertTrue(
            !Neighbours.parse(body(2, emptyList()), Neighbours.Request(offset = 2))!!
                .isEmptyButNotEmpty,
        )
    }

    @Test
    fun refusesACountThePayloadCannotBack() {
        // The walk-off-the-end case: claim 50 entries, supply one.
        val lying = body(50, listOf(Triple("aabbccddeeff", 1L, 0)), declaredCount = 50)
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
        val full = body(1, listOf(Triple("aabbccddeeff", 1L, 0)))
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
                    Triple("000000000000", 0L, 127),   //  31.75 dB
                    Triple("111111111111", 0L, -128),  // -32.0 dB
                    Triple("222222222222", 0L, 0),
                ),
            ),
        )!!
        assertEquals(31.75, table.entries[0].snr)
        assertEquals(-32.0, table.entries[1].snr)
        assertEquals(0.0, table.entries[2].snr)
    }

    @Test
    fun heardSecondsAgoCoversTheWholeU32Range() {
        // It is elapsed seconds, not an epoch stamp, and the firmware
        // computes it as an unsigned subtraction — a repeater whose
        // clock has gone backwards wraps to near 2^32 rather than
        // going negative.
        val table = Neighbours.parse(
            body(2, listOf(Triple("aabbccddeeff", 0L, 0), Triple("112233445566", 4_294_967_295L, 0))),
        )!!
        assertEquals(0L, table.entries[0].heardSecondsAgo)
        assertEquals(4_294_967_295L, table.entries[1].heardSecondsAgo)
    }

    @Test
    fun resolutionReportsEveryMatchAndNeverPicks() {
        // A prefix is not an identity. Two matches stay two matches.
        val n = Neighbours.Neighbour("aabbccddeeff", 0, 0.0)
        val keys = listOf(
            "aabbccddeeff" + "11".repeat(26),
            "aabbccddeeff" + "22".repeat(26),
            "ffffffffffff",
        )
        val hits = Neighbours.resolve(n, keys) { it }
        assertEquals(2, hits.size)
        assertEquals(emptyList(), Neighbours.resolve(n, listOf("ffffffffffff")) { it })
    }

    @Test
    fun labelsSayWhenTheyAreUnsure() {
        val n = Neighbours.Neighbour("aabbccddeeff", 0, 0.0)
        assertEquals("aabbccddeeff Blue Ridge", Neighbours.label(n, listOf("Blue Ridge")))
        assertEquals("aabbccddeeff (2 matches)", Neighbours.label(n, listOf("A", "B")))
        assertEquals("aabbccddeeff", Neighbours.label(n, emptyList()))
    }

    @Test
    fun heardAgoReadsAsElapsedTime() {
        assertEquals("0s ago", Neighbours.heardAgoLabel(0))
        assertEquals("59s ago", Neighbours.heardAgoLabel(59))
        assertEquals("1m ago", Neighbours.heardAgoLabel(60))
        assertEquals("59m ago", Neighbours.heardAgoLabel(3_599))
        assertEquals("1h ago", Neighbours.heardAgoLabel(3_600))
        assertEquals("23h ago", Neighbours.heardAgoLabel(86_399))
        assertEquals("1d ago", Neighbours.heardAgoLabel(86_400))
        assertEquals("clock skew", Neighbours.heardAgoLabel(-1))
    }
}
