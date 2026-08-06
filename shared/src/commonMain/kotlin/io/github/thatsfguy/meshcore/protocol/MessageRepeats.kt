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
     * The one-line badge under a sent bubble.
     *
     * Null when nothing was heard — which is NOT the same as "nobody
     * repeated it", and is why the absence renders as nothing at all
     * rather than as a zero. A radio out of range of every repeat is
     * indistinguishable here from a message nobody carried.
     */
    fun badge(hex: String?, hashWidth: Int?): String? = when (val n = count(hex, hashWidth)) {
        0 -> null
        1 -> "↻ 1 repeat"
        // Terse because it sits under a bubble, where a sentence would
        // crowd the message. The count is DISTINCT nodes; [summary] says
        // so in full in the info sheet, which is where precision goes.
        else -> "↻ $n repeats"
    }

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

    /**
     * Which sent direct message a heard repeat belongs to, or null.
     *
     * [peerKeysHex] are the recipients of recently sent DMs and
     * [destHash] is the one byte the rebroadcast exposes. One byte is
     * 256 buckets, so this narrows and never identifies: a repeat is
     * credited **only when exactly one candidate matches**.
     *
     * Null is a real answer and the caller must drop the sighting. Two
     * messages to people whose keys share a first byte — or two messages
     * to the SAME person inside the window — are indistinguishable here,
     * and a repeat count on the wrong message looks exactly like a right
     * one.
     */
    fun creditDirect(peerKeysHex: List<String>, destHash: Int): String? {
        if (destHash !in 0..255) return null
        val prefix = destHash.toString(16).padStart(2, '0')
        return peerKeysHex
            .filter { it.length >= 2 && it.substring(0, 2).lowercase() == prefix }
            .distinct()
            .singleOrNull()
    }

    private fun split(hex: String, chars: Int): List<String>? {
        if (hex.isEmpty()) return emptyList()
        if (chars <= 0 || hex.length % chars != 0) return null
        return (0 until hex.length / chars)
            .map { hex.substring(it * chars, (it + 1) * chars).lowercase() }
    }
}
