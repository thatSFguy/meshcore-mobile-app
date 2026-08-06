package io.github.thatsfguy.meshcore.protocol

/**
 * "Who picked THIS message up" — repeats of one sent message
 * (PARITY.md §2, the per-message half of `HeardRepeatsScreen`).
 *
 * [HeardRepeats] answers the standing question — what does the mesh
 * around this radio look like — by watching our own adverts come home.
 * This answers the one you have while looking at a message you just
 * sent, which is a different question and wants a different place to
 * live: on the message.
 *
 * Same evidence, same firmware behaviour ([HeardRepeats] documents why
 * the RX log sees our own packets at all). What differs is how we know
 * the packet is ours, and the two halves are NOT equally certain:
 *
 *  - **Channel posts are exact.** The engine decrypts the GRP_TXT
 *    payload itself, so an echo can be compared against what we sent.
 *    That is not trusting the sender name — an unauthenticated field we
 *    never use for identity — it is checking equality with a row already
 *    in our own outbox.
 *  - **Direct messages are correlated.** The payload is encrypted to the
 *    recipient, so a rebroadcast is opaque to us. It carries a one-byte
 *    `dest_hash` and a one-byte `src_hash`, which narrow and never
 *    identify, so a repeat is credited only when exactly one sent
 *    message fits — the rule [HeardVia] already applies in the other
 *    direction.
 *
 * A repeat count attributed to the wrong message looks exactly like a
 * correct one, which is why "we don't know" has to stay reachable.
 */
object MessageRepeats {

    /**
     * Distinct repeaters remembered per message.
     *
     * A flood on a busy mesh can be re-broadcast by many nodes, and this
     * is stored per row; the cap keeps one chatty message from growing a
     * column without bound.
     */
    const val MAX_RELAYS = 16

    /**
     * Merge the hops of a newly heard echo into what a message already
     * knows, returning the accumulated hop hashes in first-seen order.
     *
     * Union, not append: hearing the same repeater twice is one repeater.
     * Order is first-seen so the list does not reshuffle under the reader
     * as more copies land.
     *
     * Returns null when [newPathHex] is unusable, so a caller can leave
     * the stored value untouched rather than blanking it.
     */
    fun merge(
        existingHex: String?,
        newPathHex: String,
        hashWidth: Int,
    ): String? {
        val width = hashWidth.coerceIn(1, 4)
        val chars = width * 2
        val fresh = split(newPathHex, chars) ?: return null
        if (fresh.isEmpty()) return null
        val known = split(existingHex.orEmpty(), chars) ?: emptyList()
        val out = ArrayList<String>(known)
        for (hop in fresh) {
            if (out.size >= MAX_RELAYS) break
            if (hop !in out) out.add(hop)
        }
        return out.joinToString("")
    }

    /** Hop hashes stored for a message, in first-seen order. */
    fun relays(hex: String?, hashWidth: Int?): List<String> {
        val width = (hashWidth ?: return emptyList()).coerceIn(1, 4)
        return split(hex.orEmpty(), width * 2) ?: emptyList()
    }

    /** How many distinct repeaters are known to have carried it. */
    fun count(hex: String?, hashWidth: Int?): Int = relays(hex, hashWidth).size

    /**
     * The badge under a sent bubble: the glyph and a count, nothing else.
     *
     * No noun. The bubble footer already carries a time, a tick and
     * sometimes an attempt count, and "2 repeats" spent more of that
     * line on the word than on the number. [summary] says it in full in
     * the info sheet, which is where precision belongs.
     *
     * Null when nothing was heard — which is NOT the same as "nobody
     * repeated it", and is why the absence renders as nothing at all
     * rather than as a zero. A radio out of range of every repeat is
     * indistinguishable here from a message nobody carried.
     */
    fun badge(hex: String?, hashWidth: Int?): String? =
        count(hex, hashWidth).takeIf { it > 0 }?.let { "↻ $it" }

    /**
     * The heading line in the message info sheet.
     *
     * Says "node", not "repeater": a room server relayed the author's
     * first real measurement, and a companion with client-repeat enabled
     * will do the same. Calling them all repeaters would be wrong in a
     * way the screen itself disproves.
     */
    fun summary(hex: String?, hashWidth: Int?): String = when (val n = count(hex, hashWidth)) {
        0 -> "No repeat of this message was heard by this radio. That is not the same as " +
            "nobody carrying it — a node that relayed it away from you cannot be heard here."
        1 -> "1 node was heard re-broadcasting this message."
        else -> "$n nodes were heard re-broadcasting this message."
    }

    /** A sent direct message a heard repeat might belong to. */
    data class SentRef(
        val id: Long,
        val peerKeyHex: String,
        val sentAtMillis: Long,
    )

    /**
     * How long after a send an echo can still plausibly be its repeat.
     *
     * A rebroadcast follows its original by airtime, not minutes — but a
     * direct message may be retried up to three times, and the row is
     * stamped at the FIRST attempt, so the last attempt's echo can lag
     * the stamp considerably. Generous, but not open-ended: past this an
     * echo is more likely to belong to something else entirely.
     */
    const val MAX_ECHO_LAG_MS = 45_000L

    /**
     * Two sends this close together cannot be told apart.
     *
     * An echo arrives seconds after its original, so messages a minute
     * apart are unambiguous while messages a few seconds apart are not.
     * This is the width of "genuinely cannot say".
     */
    const val AMBIGUOUS_GAP_MS = 8_000L

    /**
     * Which sent direct message a heard repeat belongs to, or null.
     *
     * A rebroadcast DM is encrypted to its recipient and exposes one
     * byte of it — 256 buckets, so the recipient hash narrows and never
     * identifies. What actually separates two messages to the SAME
     * person is time: an echo follows its original by seconds.
     *
     * So: take the sends to that recipient that could plausibly have
     * produced this echo, and credit the most recent — unless another
     * send sits within [AMBIGUOUS_GAP_MS] of it, in which case the two
     * are indistinguishable and the honest answer is none.
     *
     * The first cut of this demanded exactly one candidate across a
     * two-minute window, which meant that sending three messages to one
     * contact — what testing the feature looks like — discarded every
     * repeat. Refusing when uncertain is right; refusing whenever busy
     * is just broken.
     */
    fun creditDirect(
        candidates: List<SentRef>,
        destHash: Int,
        echoAtMillis: Long,
    ): SentRef? {
        if (destHash !in 0..255) return null
        val prefix = destHash.toString(16).padStart(2, '0')
        val plausible = candidates
            .filter { it.peerKeyHex.length >= 2 && it.peerKeyHex.take(2).lowercase() == prefix }
            .filter { echoAtMillis >= it.sentAtMillis }
            .filter { echoAtMillis - it.sentAtMillis <= MAX_ECHO_LAG_MS }
            .sortedByDescending { it.sentAtMillis }
        val newest = plausible.firstOrNull() ?: return null
        val runnerUp = plausible.getOrNull(1)
        if (runnerUp != null && newest.sentAtMillis - runnerUp.sentAtMillis <= AMBIGUOUS_GAP_MS) {
            return null
        }
        return newest
    }

    private fun split(hex: String, chars: Int): List<String>? {
        if (hex.isEmpty()) return emptyList()
        if (chars <= 0 || hex.length % chars != 0) return null
        return (0 until hex.length / chars)
            .map { hex.substring(it * chars, (it + 1) * chars).lowercase() }
    }
}
