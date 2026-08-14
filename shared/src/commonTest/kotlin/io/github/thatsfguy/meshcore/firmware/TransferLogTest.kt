package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sampling a transfer's progress into a 500-line log.
 *
 * The numbers here are the real ones: a companion image of 372,044
 * bytes, moved 20 at a time behind a receipt every 10 packets.
 */
class TransferLogTest {

    private val IMAGE = 372_044

    @Test
    fun `a whole transfer fits in a small share of the log`() {
        // The property that matters is not "fewer lines" but "few
        // enough that everything else survives". 500 is the buffer;
        // a transfer may not fill it.
        var logged = TransferLog.NOTHING_LOGGED
        var lines = 0
        var sent = 0
        while (sent < IMAGE) {
            sent = minOf(sent + 200, IMAGE) // one receipt: 10 packets of 20
            if (TransferLog.shouldLog(sent, IMAGE, logged)) {
                logged = sent
                lines++
            }
        }
        assertTrue(lines in 2..40, "a transfer logged $lines lines")
        // Unsampled, this is what it was.
        assertEquals(1_861, (IMAGE + 199) / 200)
    }

    @Test
    fun `the first and last updates are never sampled away`() {
        // The first proves bytes moved at all. The last is the number a
        // stall is reported from — round it off and the log says the
        // transfer stopped 16 KB before it did.
        assertTrue(TransferLog.shouldLog(200, IMAGE, TransferLog.NOTHING_LOGGED))
        assertFalse(TransferLog.shouldLog(400, IMAGE, 200))
        assertTrue(TransferLog.shouldLog(IMAGE, IMAGE, IMAGE - 20))
    }

    @Test
    fun `progress reads as bytes and share and rate`() {
        assertEquals(
            "sent 16384/372044 bytes (4%) at 2.00 kB/s",
            TransferLog.describe(16_384, IMAGE, elapsedMs = 8_000),
        )
        // 372,044 of 372,044 is 100%, not 99% — an integer division
        // that reports the end of a finished transfer as unfinished is
        // the kind of thing nobody checks and everybody reads.
        assertTrue(TransferLog.describe(IMAGE, IMAGE, 60_000).contains("(100%)"))
    }

    @Test
    fun `a rate is not claimed before there is one to claim`() {
        // Dividing 200 bytes by 40 ms produces a confident number that
        // describes nothing.
        assertNull(TransferLog.rate(200, elapsedMs = 40))
        assertNull(TransferLog.rate(0, elapsedMs = 10_000))
        assertFalse(TransferLog.describe(200, IMAGE, 40).contains("kB/s"))
        // And no division by zero at the very first update.
        assertEquals("sent 0/0 bytes (0%)", TransferLog.describe(0, 0, 0))
    }

    @Test
    fun `the rate is the one a slowing link would show`() {
        // The whole point of carrying it: 2 kB/s early and 0.2 kB/s
        // later is a link degrading, which reads completely differently
        // from a node that stopped dead at full speed.
        assertEquals("2.00 kB/s", TransferLog.rate(16_384, 8_000))
        assertEquals("0.20 kB/s", TransferLog.rate(16_384, 80_000))
    }
}
