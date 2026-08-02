package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TracePathTest {

    private fun traceFrame(
        pathLenByte: Int,
        flags: Int,
        tag: Long,
        auth: Long,
        path: ByteArray,
        snrs: ByteArray,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.PUSH_CODE_TRACE_DATA)
        w.writeByte(0)              // reserved
        w.writeByte(pathLenByte)
        w.writeByte(flags)
        w.writeUInt32LE(tag)
        w.writeUInt32LE(auth)
        w.writeBytes(path)
        w.writeBytes(snrs)
        return w.toBytes()
    }

    @Test
    fun parsesTwoHopTrace() {
        // flags 0 → width 1; 2 hops → 3 SNRs (per hop + link to us).
        val frame = traceFrame(
            pathLenByte = 2, flags = 0, tag = 0xAABBCCDDL, auth = 7L,
            path = byteArrayOf(0x11, 0x22),
            snrs = byteArrayOf(40, 20, -8),
        )
        val t = TracePath.parse(frame)
        assertNotNull(t)
        assertEquals(0xAABBCCDDL, t.tag)
        assertEquals(7L, t.auth)
        assertEquals(1, t.hashWidth)
        assertEquals(listOf("11", "22"), t.hops)
        assertEquals(2, t.hopCount)
        assertEquals(listOf(10.0, 5.0, -2.0), t.snrs)
    }

    @Test
    fun directTraceHasNoHops() {
        // 0xFF path_len = direct (no path); one SNR for our own link.
        val frame = traceFrame(0xFF, 0, 1L, 1L, ByteArray(0), byteArrayOf(32))
        val t = TracePath.parse(frame)!!
        assertEquals(0, t.hopCount)
        assertEquals(listOf(8.0), t.snrs)
    }

    @Test
    fun widthFromFlags() {
        // flags & 0x03 == 1 → width 2 → one 2-byte hop.
        val frame = traceFrame(2, 1, 1L, 1L, byteArrayOf(0xAB.toByte(), 0xCD.toByte()), byteArrayOf(0, 0))
        val t = TracePath.parse(frame)!!
        assertEquals(2, t.hashWidth)
        assertEquals(listOf("abcd"), t.hops)
    }

    @Test
    fun packedPathLenFallback() {
        // path_len byte with the top bits set encodes (width-1)<<6 | hops
        // when the raw value would overrun the frame: 0x42 → width 2, 2 hops.
        val frame = traceFrame(
            pathLenByte = 0x42, flags = 0, tag = 1L, auth = 1L,
            path = byteArrayOf(1, 2, 3, 4),
            snrs = byteArrayOf(0, 0, 0),
        )
        val t = TracePath.parse(frame)!!
        assertEquals(2, t.hashWidth)
        assertEquals(listOf("0102", "0304"), t.hops)
    }

    @Test
    fun malformedFramesNeverThrow() {
        assertNull(TracePath.parse(ByteArray(0)))
        assertNull(TracePath.parse(ByteArray(11)))            // too short
        // Claims 40 path bytes but carries none.
        assertNull(TracePath.parse(traceFrame(40, 0, 1L, 1L, ByteArray(0), ByteArray(0))))
        for (len in 12..20) {
            TracePath.parse(ByteArray(len) { if (it == 0) 0x89.toByte() else 0xFF.toByte() })
        }
    }

    @Test
    fun hopTokenParsingAcceptsCommonSeparators() {
        val expected = byteArrayOf(0x11, 0x22, 0x33)
        for (input in listOf("11 22 33", "11,22,33", "11:22:33", "11-22-33", " 11  22\t33 ")) {
            assertContentEquals(expected, PathCodec.parseHopTokens(input), "input=$input")
        }
        assertContentEquals(ByteArray(0), PathCodec.parseHopTokens("   "))
    }

    @Test
    fun hopTokenParsingRejectsGarbage() {
        assertNull(PathCodec.parseHopTokens("zz"))
        assertNull(PathCodec.parseHopTokens("111"))      // wrong width
        assertNull(PathCodec.parseHopTokens("1"))
        // Too many hops for MAX_PATH_SIZE.
        assertNull(PathCodec.parseHopTokens(List(PathCodec.MAX_HOPS + 1) { "aa" }.joinToString(" ")))
    }

    @Test
    fun hopFormattingRoundTrips() {
        val path = byteArrayOf(0x0A, 0xBC.toByte(), 0x01)
        assertEquals("0a bc 01", PathCodec.formatHops(path))
        assertContentEquals(path, PathCodec.parseHopTokens(PathCodec.formatHops(path)))
        assertEquals("0abc 01", PathCodec.formatHops(path.copyOfRange(0, 2), 2) + " 01")
    }

    @Test
    fun qualityLabels() {
        assertEquals("flood", PathCodec.qualityLabel(0, 0, isFlood = true))
        assertEquals("untested", PathCodec.qualityLabel(0, 0, isFlood = false))
        assertEquals("failing", PathCodec.qualityLabel(0, 3, isFlood = false))
        assertEquals("proven", PathCodec.qualityLabel(6, 0, isFlood = false))
        assertEquals("strong", PathCodec.qualityLabel(9, 1, isFlood = false))
        assertEquals("good", PathCodec.qualityLabel(3, 2, isFlood = false))
        assertEquals("fair", PathCodec.qualityLabel(1, 3, isFlood = false))
    }

    // ------------------------------------------------------------------
    // Trace flags (regression: a trace that silently did nothing)
    // ------------------------------------------------------------------

    @Test
    fun flagsDeclareTheHopHashWidth() {
        // The sender hardcoded 0, which told a 2-byte mesh that hops
        // were 1 byte wide. The probe went out malformed and nothing
        // ever came back — the failure looked like "the button does
        // nothing", not like an error.
        assertEquals(0, TracePath.flagsForHashWidth(1))
        assertEquals(1, TracePath.flagsForHashWidth(2))
        assertEquals(2, TracePath.flagsForHashWidth(3))
        assertEquals(2, TracePath.flagsForHashWidth(4))
        // Nonsense widths must still produce a legal flag value.
        assertEquals(0, TracePath.flagsForHashWidth(0))
        assertEquals(0, TracePath.flagsForHashWidth(-1))
    }

    @Test
    fun flagsRoundTripThroughTheWidthTheParserDerives() {
        // The parser reads the width back as `1 shl (flags and 0x03)`,
        // so encode-then-decode must land on the same width for every
        // width the protocol supports.
        for (width in listOf(1, 2, 4)) {
            val flags = TracePath.flagsForHashWidth(width)
            assertEquals(width, 1 shl (flags and 0x03), "width $width did not round trip")
        }
    }

    @Test
    fun hopTokensParseAtTheMeshsOwnWidth() {
        // Regression, found on a 2-byte mesh: the routing sheet called
        // parseHopTokens WITHOUT a width, so it defaulted to 1 and
        // rejected every real token. "Apply path" then sent nothing and
        // flashed an error naming the wrong digit count — a pinned route
        // that silently wasn't.
        val twoByte = PathCodec.parseHopTokens("b389 c985", 2)
        assertEquals(
            listOf<Byte>(0xb3.toByte(), 0x89.toByte(), 0xc9.toByte(), 0x85.toByte()),
            twoByte?.toList(),
        )
        // The same input at the WRONG width is refused, not truncated —
        // which is correct, and is why the default silently broke it.
        assertEquals(null, PathCodec.parseHopTokens("b389 c985", 1))
        // And 1-byte tokens still work at width 1.
        assertEquals(listOf<Byte>(0x3f, 0xa1.toByte()), PathCodec.parseHopTokens("3f a1", 1)?.toList())
    }
}
