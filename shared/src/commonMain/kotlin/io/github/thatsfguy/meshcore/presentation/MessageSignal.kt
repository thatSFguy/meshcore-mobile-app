package io.github.thatsfguy.meshcore.presentation

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Wording for a received message's SNR.
 *
 * The SNR the radio reports is the signal of the LAST TRANSMISSION it
 * heard — `pkt->getSNR()` on the packet as it arrived. After a relay that
 * is the last repeater's signal at this radio, not the sender's; the
 * sender's own signal is only ever seen when it was heard direct. A bubble
 * reading "2 hops · 6.5 dB" invited the wrong reading, so anything not
 * known to be direct says "last hop". Same rule as [HeardReach] uses for
 * heard nodes.
 */
object MessageSignal {

    /** For the line under a bubble; null when there is no SNR. */
    fun bubble(snr: Double?, hops: Int?): String? {
        snr ?: return null
        return if (hops == 0) db(snr) else "last hop ${db(snr)}"
    }

    /** For the message info sheet: the value, with what it measures. */
    fun info(snr: Double?, hops: Int?): String? {
        snr ?: return null
        return if (hops == 0) {
            "${db(snr)} — the sender's own signal, heard direct"
        } else {
            "${db(snr)} — the last repeater's signal at your radio, not the sender's"
        }
    }

    /** One decimal place, without a locale-dependent formatter. */
    internal fun db(snr: Double): String {
        // Half away from zero, so -0.25 and 0.25 mirror each other;
        // roundToLong alone sends ties toward +infinity.
        val a = (abs(snr) * 10).roundToLong()
        val sign = if (snr < 0 && a > 0) "-" else ""
        return "$sign${a / 10}.${a % 10} dB"
    }
}
