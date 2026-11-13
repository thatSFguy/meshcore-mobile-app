package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.Ping.Outcome
import io.github.thatsfguy.meshcore.protocol.TracePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ping = a one-hop trace (issue #5).
 *
 * The reply frames here are written byte by byte from the FIRMWARE'S
 * writer, `examples/companion_radio/MyMesh.cpp:855-876` (onTraceRecv),
 * not produced by anything in this codebase — a builder tested against
 * our own parser is the same assumption twice (CLAUDE.md):
 * ```
 * [0x89][0][path_len = hash BYTES][flags][tag u32 LE][auth u32 LE]
 * [hashes: path_len bytes][snrs: path_len >> path_sz][final snr]
 * ```
 * SNRs are int8 quarter-dB (`(int8_t)(getSNR() * 4)`).
 */
class PingTest {

    /**
     * A repeater key whose first two bytes are B389 — the hop hash a live
     * trace on this mesh showed for SpartaMI ("B389: SpartaMI").
     */
    private val spartaKey = "b389" + "1f".repeat(30)

    private fun firmwareTraceFrame(
        flags: Int,
        hashes: ByteArray,
        snrQuarters: List<Int>,
        tag: Long = 0x01020304,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out += 0x89.toByte()
        out += 0
        out += hashes.size.toByte()
        out += flags.toByte()
        for (i in 0 until 4) out += ((tag shr (8 * i)) and 0xFF).toByte()
        repeat(4) { out += 0 }
        hashes.forEach { out += it }
        snrQuarters.forEach { out += it.toByte() }
        return out.toByteArray()
    }

    // --- the path sent -----------------------------------------------------

    @Test
    fun `the path is the key's leading bytes at the mesh's width`() {
        assertEquals("b3", Ping.hashHex(Ping.path(spartaKey, 1)!!))
        assertEquals("b389", Ping.hashHex(Ping.path(spartaKey, 2)!!))
        assertEquals("b3891f1f", Ping.hashHex(Ping.path(spartaKey, 4)!!))
    }

    @Test
    fun `every width a ping sends is one the trace flags declare exactly`() {
        // The firmware reads width as 1 << (flags & 3). A width the flags
        // round to something else would send a probe no repeater matches —
        // the trace-flags bug, again.
        for (w in Ping.TRACE_WIDTHS) {
            assertEquals(w, 1 shl (TracePath.flagsForHashWidth(w) and 0x03), "width $w")
        }
    }

    @Test
    fun `a width the trace flags cannot express is refused rather than rounded`() {
        // A mesh's path-hash mode can be 3 bytes; a trace's flags can say
        // only 1, 2, 4 or 8.
        assertNull(Ping.path(spartaKey, 3))
        assertNull(Ping.path(spartaKey, 0))
        assertNull(Ping.path(spartaKey, 8))
    }

    @Test
    fun `hostile keys produce no path`() {
        assertNull(Ping.path("", 1))
        assertNull(Ping.path("zz" + "00".repeat(31), 1))
        assertNull(Ping.path("abc", 1))
        // Shorter than the width.
        assertNull(Ping.path("b3", 2))
    }

    // --- reading the reply --------------------------------------------------

    @Test
    fun `a firmware reply through the pinged hop reads as reached with both SNRs`() {
        // The positive control: everything below asserts a ping is NOT
        // claimed, and would pass if this feature did nothing.
        val frame = firmwareTraceFrame(
            flags = 1, // 2-byte hashes
            hashes = byteArrayOf(0xB3.toByte(), 0x89.toByte()),
            snrQuarters = listOf(48, -22), // 12.0 dB out, -5.5 dB back
        )
        val outcome = Ping.outcome(TracePath.parse(frame), "b389", roundTripMs = 840)
        assertEquals(Outcome.Reached(heardUsDb = 12.0, weHeardDb = -5.5, roundTripMs = 840), outcome)
    }

    @Test
    fun `a one-byte mesh reads the same way`() {
        val frame = firmwareTraceFrame(flags = 0, hashes = byteArrayOf(0xB3.toByte()), snrQuarters = listOf(29, 30))
        val outcome = Ping.outcome(TracePath.parse(frame), "b3", 1_000)
        assertEquals(Outcome.Reached(7.25, 7.5, 1_000), outcome)
    }

    @Test
    fun `silence is no reply`() {
        assertEquals(Outcome.NoReply, Ping.outcome(null, "b389", 30_000))
    }

    @Test
    fun `a reply through a different hop is not this node answering`() {
        val frame = firmwareTraceFrame(1, byteArrayOf(0xC9.toByte(), 0x85.toByte()), listOf(40, 40))
        assertEquals(Outcome.NoReply, Ping.outcome(TracePath.parse(frame), "b389", 500))
    }

    @Test
    fun `a multi-hop reply is not a ping of one node`() {
        val frame = firmwareTraceFrame(
            1,
            byteArrayOf(0xB3.toByte(), 0x89.toByte(), 0xC9.toByte(), 0x85.toByte()),
            listOf(40, 40, 40),
        )
        assertEquals(Outcome.NoReply, Ping.outcome(TracePath.parse(frame), "b389", 500))
    }

    @Test
    fun `a reply missing its final SNR is not reported as a reading`() {
        val frame = firmwareTraceFrame(1, byteArrayOf(0xB3.toByte(), 0x89.toByte()), listOf(48))
        assertEquals(Outcome.NoReply, Ping.outcome(TracePath.parse(frame), "b389", 500))
    }

    @Test
    fun `the expected hash is matched case-insensitively`() {
        val frame = firmwareTraceFrame(1, byteArrayOf(0xB3.toByte(), 0x89.toByte()), listOf(4, 4))
        assertIs<Outcome.Reached>(Ping.outcome(TracePath.parse(frame), "B389", 10))
    }

    // --- shared hashes ------------------------------------------------------

    @Test
    fun `other nodes sharing the hop hash are counted but the target is not`() {
        val other = "b3" + "00".repeat(31)
        val unrelated = "c9" + "00".repeat(31)
        val known = listOf(spartaKey, other, other.uppercase(), unrelated)
        assertEquals(1, Ping.sharedPrefixCount("b3", spartaKey, known))
        // At two bytes the collision is gone.
        assertEquals(0, Ping.sharedPrefixCount("b389", spartaKey, known))
    }

    @Test
    fun `a shared hash is said out loud on a reply`() {
        val text = Ping.describe(Outcome.Reached(12.0, -5.5, 840), sharedWith = 2)
        assertTrue("840 ms" in text, text)
        assertTrue("12 dB" in text, text)
        assertTrue("-5.5 dB" in text, text)
        assertTrue("2 other known nodes share" in text, text)
        // And not when there is none.
        assertTrue("share" !in Ping.describe(Outcome.Reached(12.0, -5.5, 840)))
    }

    @Test
    fun `no reply does not claim the node is down`() {
        val text = Ping.describe(Outcome.NoReply)
        assertTrue("not proof" in text, text)
    }
}
