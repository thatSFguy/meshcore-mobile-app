package io.github.thatsfguy.meshcore.protocol

/**
 * "How did this message get to me" — recovering the route a received
 * message actually travelled (PARITY.md §2, §13).
 *
 * The companion message frame carries `path_len` and nothing else — a
 * hop COUNT, not a route (MESHCORE_PROTOCOL §8). That is why the message
 * sheet can honestly say "4 hops" and no more. But the same packet also
 * comes up through `PUSH_CODE_LOG_RX_DATA` with its **full path**
 * ([RawPacket.pathBytes]), so the route is recoverable — by correlating
 * the raw packet with the message it carried.
 *
 * The two halves are not equally certain, and this object exists to keep
 * that distinction rather than paper over it:
 *
 *  - **Channel messages are exact.** The engine decrypts the GRP_TXT
 *    payload itself, so the packet and the message are the same object.
 *    No correlation, no doubt.
 *
 *  - **Direct messages must be correlated.** A DM payload is encrypted
 *    to the radio's identity key, which the app never holds, so the raw
 *    packet and the decrypted message arrive separately and have to be
 *    matched on what they share: the sender's key prefix, the hop count,
 *    and arrival time. [match] returns a route only when exactly one
 *    candidate fits. Two plausible packets means we do not know which
 *    one carried it — and a route attributed to the wrong message looks
 *    exactly like a correct one, which is the failure mode PARITY §12
 *    exists to prevent.
 */
object HeardVia {

    /**
     * How long an unmatched raw packet stays available for correlation.
     *
     * The radio pushes the RX log when it hears the packet and delivers
     * the decrypted message from its queue afterwards, so the gap is
     * normally well under a second. The window is generous because the
     * queue drains only when the app asks — but not unbounded, because a
     * stale packet is exactly what would produce a confident wrong
     * answer.
     */
    const val MATCH_WINDOW_MS = 60_000L

    /** Unmatched arrivals retained. Bounded: this is attacker-fed. */
    const val MAX_PENDING = 32

    /**
     * A raw packet heard over the air, waiting to be matched to the
     * message it carried.
     */
    data class Arrival(
        /** The full path, hex, in TRAVEL order: first hop nearest the sender. */
        val pathHex: String,
        val hashWidth: Int,
        val hops: Int,
        /**
         * The packet's `src_hash` — the first byte of the sender's public
         * key. One byte, so it narrows candidates; it never identifies.
         */
        val srcHash: Int,
        val atMillis: Long,
    )

    /**
     * Add [arrival] to [pending], dropping the oldest past [MAX_PENDING].
     *
     * Bounded on purpose: anyone within radio range can cause entries
     * here, so an unbounded buffer is a remote memory-growth lever.
     */
    fun remember(pending: List<Arrival>, arrival: Arrival): List<Arrival> =
        (pending + arrival).takeLast(MAX_PENDING)

    /** Drop arrivals too old to be correlated with anything. */
    fun expire(pending: List<Arrival>, nowMillis: Long): List<Arrival> =
        pending.filter { nowMillis - it.atMillis in 0..MATCH_WINDOW_MS }

    /**
     * The arrival that carried a direct message, or null when it cannot
     * be said which one did.
     *
     * [senderPrefixHex] is the 6-byte prefix from the message frame;
     * [hops] is its decoded hop count, or null when unknown.
     *
     * Null is a real answer here and the UI must render it as "not
     * known", never as "direct" — those lead somewhere different.
     */
    fun match(
        pending: List<Arrival>,
        senderPrefixHex: String,
        hops: Int?,
        nowMillis: Long,
    ): Arrival? {
        val srcHash = senderPrefixHex.take(2)
            .takeIf { it.length == 2 }
            ?.toIntOrNull(16)
            ?: return null
        val candidates = pending.filter { a ->
            nowMillis - a.atMillis in 0..MATCH_WINDOW_MS &&
                a.srcHash == srcHash &&
                // The hop count is the one field BOTH sides state
                // independently. Requiring it to agree is what stops a
                // different packet from the same sender being credited.
                (hops == null || a.hops == hops)
        }
        // Exactly one, or nothing. Picking the most recent of several
        // would be a guess wearing a timestamp.
        return candidates.singleOrNull()
    }

    /**
     * Reverse an arrival path into a route for sending.
     *
     * An arrival path is ordered from the sender outward: hop 0 is the
     * repeater nearest THEM, the last hop is the one that reached US. A
     * stored out-path is ordered the other way. Pinning an arrival path
     * as a reply route without reversing it addresses the hops backwards
     * — a route that looks specific and cannot work.
     */
    fun reverse(pathBytes: ByteArray, hashWidth: Int): ByteArray {
        val width = hashWidth.coerceIn(1, 4)
        if (pathBytes.isEmpty() || pathBytes.size % width != 0) return ByteArray(0)
        val out = ByteArray(pathBytes.size)
        val hops = pathBytes.size / width
        for (h in 0 until hops) {
            val from = h * width
            val to = (hops - 1 - h) * width
            pathBytes.copyInto(out, to, from, from + width)
        }
        return out
    }

    /** [reverse] over the hex form, for storage and display. */
    fun reverseHex(pathHex: String, hashWidth: Int): String {
        val width = hashWidth.coerceIn(1, 4)
        val chars = width * 2
        if (pathHex.isEmpty() || pathHex.length % chars != 0) return ""
        return (pathHex.length / chars - 1 downTo 0)
            .joinToString("") { pathHex.substring(it * chars, (it + 1) * chars) }
    }

    /**
     * One sentence for the message sheet. [hops] is what the message
     * frame stated; [route] is the correlated path, if any.
     *
     * The wording separates "no repeaters were involved" from "we don't
     * know which repeaters were involved" — the whole point of the
     * feature is not to imply the first when we mean the second.
     */
    fun summary(hops: Int?, route: String?, hashWidth: Int): String {
        val width = hashWidth.coerceIn(1, 4)
        val routed = route?.takeIf { it.isNotEmpty() && it.length % (width * 2) == 0 }
        return when {
            routed != null -> "Arrived via ${routed.length / (width * 2)} repeater(s), " +
                "listed in the order it travelled."
            // FLOOD IS NOT DIRECT. path_len 0xFF decodes to -1, and
            // folding that in with 0 put "Arrived directly — no repeater
            // in between" directly under "Hops travelled: flood" on a
            // real message. A flooded packet is re-broadcast by whoever
            // hears it and the frame records no route at all, so the one
            // thing we can be sure of is that we do NOT know.
            hops != null && hops < 0 ->
                "Sent by flooding, so no route was recorded — a flooded packet is passed " +
                    "on by whoever hears it. Which repeaters relayed this isn't known."
            hops == 0 -> "Arrived directly — no repeater in between."
            hops != null && hops > 0 ->
                "Travelled $hops hop(s), but which repeaters carried it isn't known — " +
                    "the message frame states a hop count only."
            else -> "How this arrived isn't known."
        }
    }
}
