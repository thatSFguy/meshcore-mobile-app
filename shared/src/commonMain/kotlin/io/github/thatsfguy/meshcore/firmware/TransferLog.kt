package io.github.thatsfguy.meshcore.firmware

/**
 * How much of a transfer's progress is worth writing down.
 *
 * Every receipt notification moves the byte count, and a stock
 * bootloader takes 20 bytes at a time, so a 372 KB image produces about
 * **1,860** progress updates. Logging all of them costs twice:
 *
 * 1. The diagnostics log holds 500 lines. A finished transfer therefore
 *    evicts everything that came before it — the scan, the MTU, the DFU
 *    revision, the jump, the receipt interval — and a FAILED one buries
 *    its own failure's context behind hundreds of lines that all say the
 *    same thing. Every flash attempted on hardware so far has been
 *    diagnosed from a log like that, which is a large part of why so
 *    little could be concluded from any of them.
 * 2. Each line is redacted by three regexes and republished as a copied
 *    500-element list, on the thread driving a latency-sensitive BLE
 *    stream that must not go quiet for 30 seconds.
 *
 * So progress is sampled, and what is sampled carries the RATE — which
 * is the number that distinguishes a link that degraded from one that
 * stopped dead.
 */
object TransferLog {

    /**
     * Bytes between log lines: about 24 lines for a full companion
     * image, which is a readable shape of the whole transfer and leaves
     * the other 476 for everything that is not a progress bar.
     */
    const val STEP_BYTES = 16 * 1024

    /** Nothing logged yet. */
    const val NOTHING_LOGGED = -1

    /**
     * The first update and the last are always logged. The first proves
     * bytes started moving at all; the last is the one a stall is
     * measured from, and rounding it away would lose the only number
     * that says how far the transfer got.
     */
    fun shouldLog(bytesSent: Int, totalBytes: Int, lastLogged: Int): Boolean =
        lastLogged == NOTHING_LOGGED ||
            bytesSent >= totalBytes ||
            bytesSent - lastLogged >= STEP_BYTES

    /**
     * `sent 16384/372044 bytes (4%) at 2.14 kB/s`.
     *
     * Deliberately no platform formatting — `String.format` is JVM-only
     * and this is commonMain, which is where a JVM-only call last broke
     * the iOS build.
     */
    fun describe(bytesSent: Int, totalBytes: Int, elapsedMs: Long): String {
        val percent = if (totalBytes <= 0) 0 else bytesSent.toLong() * 100 / totalBytes
        return "sent $bytesSent/$totalBytes bytes ($percent%)" +
            rate(bytesSent, elapsedMs)?.let { " at $it" }.orEmpty()
    }

    /**
     * Average kB/s over the whole transfer so far, or null when too
     * little time has passed for the figure to mean anything.
     */
    fun rate(bytesSent: Int, elapsedMs: Long): String? {
        if (elapsedMs < 1_000 || bytesSent <= 0) return null
        // Hundredths of a kB/s, in integer arithmetic.
        val hundredths = bytesSent.toLong() * 100 * 1000 / (1024 * elapsedMs)
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')} kB/s"
    }
}
