package io.github.thatsfguy.meshcore.protocol

/**
 * How long to wait for a message's ACK before giving up on an attempt.
 *
 * The radio states a timeout with every `RESP_CODE_SENT` and it is
 * normally the right number to trust — it is computed from the actual
 * airtime of the actual packet, which the app cannot see. For a
 * stored-path send it also scales with distance:
 *
 * ```cpp
 * uint32_t MyMesh::calcDirectTimeoutMillisFor(uint32_t pkt_airtime_millis, uint8_t path_len) const {
 *   uint8_t path_hash_count = path_len & 63;
 *   return SEND_TIMEOUT_BASE_MILLIS +
 *          ((pkt_airtime_millis * DIRECT_SEND_PERHOP_FACTOR + DIRECT_SEND_PERHOP_EXTRA_MILLIS) *
 *           (path_hash_count + 1));
 * }
 * ```
 *
 * **The flood estimate does not.** It is a flat multiple of airtime and
 * knows nothing about how far away the recipient is:
 *
 * ```cpp
 * uint32_t MyMesh::calcFloodTimeoutMillisFor(uint32_t pkt_airtime_millis) const {
 *   return SEND_TIMEOUT_BASE_MILLIS + (FLOOD_SEND_TIMEOUT_FACTOR * pkt_airtime_millis);
 * }
 * ```
 *
 * (both `examples/companion_radio/MyMesh.cpp`; `SEND_TIMEOUT_BASE_MILLIS`
 * 500, `FLOOD_SEND_TIMEOUT_FACTOR` 16, `DIRECT_SEND_PERHOP_FACTOR` 6,
 * `DIRECT_SEND_PERHOP_EXTRA_MILLIS` 250.)
 *
 * Sixteen airtimes is about eight hops of round trip with zero
 * contention — and a flood has to cross the mesh and come back. At the
 * USA/Canada preset a short DM is roughly 165 ms on air, so the flood
 * budget is ~3.1 s whether the recipient is one hop away or eleven,
 * while eleven hops is ~3.6 s of pure airtime before a single repeater
 * waits its turn. Past that distance the attempt is guaranteed to time
 * out *even when the ACK is already on its way back*, and the sender
 * retries a message that arrived.
 *
 * Observed 2026-09-22 from the receiving end: a contact 4–11 hops out
 * delivered the same message nine times — a full automatic ladder plus
 * manual resends — because none of its attempts ever waited long enough
 * to be acknowledged.
 *
 * So when we flood to a node whose distance we DO know, we spend the
 * firmware's own per-hop budget on the distance we know, and keep the
 * radio's figure whenever it is larger. This is not a second opinion
 * about airtime — the airtime is recovered from the radio's own
 * estimate — it is the one input the radio left out.
 */
object AckTimeout {

    /** `SEND_TIMEOUT_BASE_MILLIS`. */
    const val BASE_MS = 500L

    /** `FLOOD_SEND_TIMEOUT_FACTOR`. */
    const val FLOOD_AIRTIME_FACTOR = 16L

    /** `DIRECT_SEND_PERHOP_FACTOR`. */
    const val PER_HOP_AIRTIME_FACTOR = 6L

    /** `DIRECT_SEND_PERHOP_EXTRA_MILLIS`. */
    const val PER_HOP_EXTRA_MS = 250L

    /**
     * Floor and ceiling on any wait.
     *
     * The floor covers a radio that answers with an implausibly small
     * number; the ceiling stops a corrupt or hostile estimate from
     * parking a send for the rest of the afternoon.
     */
    const val MIN_WAIT_MS = 3_000L
    const val MAX_WAIT_MS = 60_000L

    /**
     * The per-hop budget can only be spent when the hop count is real.
     * The firmware's own field is six bits, so anything past this is a
     * decode gone wrong rather than a very long route.
     */
    const val MAX_CREDIBLE_HOPS = 63

    /**
     * The airtime the radio's flood estimate implies, or null when the
     * estimate cannot have come from that formula.
     *
     * Inverting the firmware's own arithmetic rather than re-deriving
     * airtime from the radio parameters: the modulation settings, the
     * preamble and the true on-air length all live on the radio, and a
     * second implementation of that sum here would be a second chance
     * to disagree with the thing actually transmitting.
     */
    fun airtimeFromFloodEstimate(floodEstimateMs: Long): Long? =
        ((floodEstimateMs - BASE_MS) / FLOOD_AIRTIME_FACTOR).takeIf { it > 0 }

    /**
     * The firmware's stored-path budget for [hops] hops of [airtimeMs].
     *
     * `hops + 1` because a path of N hops is N + 1 transmissions: each
     * repeater plus the final one to the recipient.
     */
    fun perHopBudgetMs(airtimeMs: Long, hops: Int): Long =
        BASE_MS + (airtimeMs * PER_HOP_AIRTIME_FACTOR + PER_HOP_EXTRA_MS) * (hops + 1L)

    /**
     * How long to wait for this attempt's ACK.
     *
     * [radioEstimateMs] is `RESP_CODE_SENT`'s timeout, [isFlood] its
     * is_flood byte, and [knownHops] how far away we believe the
     * recipient is — null when we have no idea, which is the honest
     * answer for a node we have never heard from.
     *
     * Never shorter than the radio asked for. The radio knows things we
     * do not and this only ever adds the distance it left out; taking a
     * *smaller* number than the firmware's would be second-guessing the
     * half it gets right.
     */
    fun waitFor(radioEstimateMs: Long, isFlood: Boolean, knownHops: Int?): Long {
        val fromRadio = radioEstimateMs.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        if (!isFlood) return fromRadio
        val hops = knownHops?.takeIf { it in 0..MAX_CREDIBLE_HOPS } ?: return fromRadio
        val airtime = airtimeFromFloodEstimate(radioEstimateMs) ?: return fromRadio
        val distanceAware = perHopBudgetMs(airtime, hops).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        return maxOf(fromRadio, distanceAware)
    }

    /**
     * The best distance estimate we hold for a contact, in hops, or null.
     *
     * Two independent signals, and the LARGER wins. [storedPathHops] is
     * the route the radio would send along, which is a lower bound on a
     * flood's round trip and is exactly the figure that is stale when a
     * flood is needed in the first place. [lastHeardHops] is how far a
     * message from them actually travelled to reach us, which is the
     * more honest measure of how far apart the two radios are.
     *
     * Erring long costs a slower "Failed"; erring short costs a
     * duplicate delivered to someone else's phone, and that is the
     * failure this whole object exists to stop.
     */
    fun estimateHops(storedPathHops: Int?, lastHeardHops: Int?): Int? {
        val candidates = listOfNotNull(storedPathHops, lastHeardHops)
            .filter { it in 0..MAX_CREDIBLE_HOPS }
        return candidates.maxOrNull()
    }
}
