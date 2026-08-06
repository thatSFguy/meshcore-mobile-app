package io.github.thatsfguy.meshcore.protocol

/**
 * "Which repeaters carry MY traffic" (PARITY.md §2, `HeardRepeatsScreen`).
 *
 * The mirror of [HeardVia]. That one answers *how did this message reach
 * me*; this one answers *who passed my transmission on* — which is the
 * question behind "do I actually have coverage here", and the only one
 * of the two you can ask deliberately rather than waiting for someone to
 * message you.
 *
 * ## Why this is possible at all
 *
 * PARITY.md said for months that this was "not answerable from the RX
 * log alone". That was wrong, and the firmware is unambiguous about it:
 * `Dispatcher::checkRecv()` calls `logRxRaw()` on the very next line
 * after `recvRaw()` — **before** `tryParsePacket`, before the seen-table
 * check, before any routing decision. So the client is handed every
 * packet the radio demodulates, including the ones the mesh layer is
 * about to discard as duplicates. A repeater rebroadcasting our own
 * packet back at us is exactly such a duplicate: the firmware marks our
 * outbound packets seen precisely so it will not re-transmit them
 * ("mark this packet as already sent in case it is rebroadcast back to
 * us", `Mesh.cpp`). Dropped for routing, still logged for us.
 *
 * ## Why adverts, and only adverts
 *
 * The packet used here is **our own signed advert coming back**, and
 * that choice is what makes the feature honest rather than plausible:
 *
 *  - An ADVERT payload carries the sender's **full 32-byte public key**,
 *    so recognising our own is an exact comparison — not a one-byte
 *    `src_hash` narrowing, which is all a direct message would offer.
 *  - The advert is Ed25519-signed over that key, and the engine only
 *    accepts verified adverts. Forging a repeat of *our* advert needs
 *    *our* private key, so nobody can inflate someone else's coverage
 *    picture or invent a relay that does not exist.
 *  - It is deliberately triggerable: send a flood advert and the
 *    replies are a live measurement, rather than a report that only
 *    fills in when someone happens to message you.
 *
 * Channel messages are NOT used even though the engine can decrypt them,
 * because a channel message's only claim of authorship is its sender
 * *name*, which is unauthenticated display text (MESHCORE_PROTOCOL §12)
 * — treating "a message that says it is from me" as mine would be the
 * exact trust this codebase refuses everywhere else.
 *
 * ## Reading a path correctly
 *
 * A path is in TRAVEL order: hop 0 is the repeater nearest the SENDER,
 * the last hop is the one that transmitted the copy we actually heard.
 * For our own packet coming home that means the two ends say different
 * things, and conflating them would produce a confident wrong answer:
 *
 *  - **hop 0 heard US.** It pulled our transmission out of the air. That
 *    is the uplink working.
 *  - **the LAST hop reached US.** We demodulated *its* transmission, so
 *    it is the only hop whose SNR and RSSI we measured. That is the
 *    downlink working.
 *  - A one-hop path is both, and that is the interesting case: a relay
 *    you can hear and that can hear you.
 *
 * Attributing the measured SNR to any hop other than the last one would
 * be reporting a link nobody observed.
 */
object HeardRepeats {

    /**
     * Retained echoes. Bounded because a chatty mesh can produce these
     * faster than anyone looks at them; the cap is generous enough that
     * a deliberate advert-and-watch session is never truncated.
     */
    const val MAX_ECHOES = 200

    /**
     * One heard copy of a packet we originated.
     *
     * [snr] and [rssi] describe the transmission we demodulated — that
     * is, the LAST hop's — and belong to no other hop on the path.
     */
    data class Echo(
        /** Full path, hex, in travel order. */
        val pathHex: String,
        val hashWidth: Int,
        val snr: Double?,
        val rssi: Int?,
        val atMillis: Long,
    ) {
        /** Hop hashes in travel order; empty when the path is unusable. */
        val hops: List<String>
            get() {
                val chars = hashWidth.coerceIn(1, 4) * 2
                if (pathHex.isEmpty() || pathHex.length % chars != 0) return emptyList()
                return (0 until pathHex.length / chars)
                    .map { pathHex.substring(it * chars, (it + 1) * chars).lowercase() }
            }

        /**
         * A packet with no path was never relayed — we cannot hear our
         * own transmission, so this is not evidence of anything.
         */
        val isRelayed: Boolean get() = hops.isNotEmpty()
    }

    /** One repeater, aggregated across every echo it appears in. */
    data class Relay(
        val hashHex: String,
        /** How many of our packets this repeater carried. */
        val relayed: Int,
        /** It appeared at hop 0 — it received our transmission directly. */
        val heardUs: Boolean,
        /** It appeared last — we received ITS transmission directly. */
        val reachedUs: Boolean,
        /** Best SNR measured, and only ever from echoes where it was last. */
        val bestSnr: Double?,
        val lastAtMillis: Long,
    ) {
        /** Heard us AND reached us: a working two-way neighbour. */
        val isTwoWay: Boolean get() = heardUs && reachedUs
    }

    /** Add [echo], dropping the oldest past [MAX_ECHOES]. */
    fun record(known: List<Echo>, echo: Echo): List<Echo> =
        if (!echo.isRelayed) known else (known + echo).takeLast(MAX_ECHOES)

    /**
     * Aggregate [echoes] into one row per repeater.
     *
     * Ordered most-active first so the relay actually carrying your
     * traffic is at the top, with the hash as a stable tie-break — the
     * same data must not reorder itself between openings.
     */
    fun tally(echoes: List<Echo>): List<Relay> {
        data class Acc(
            var relayed: Int = 0,
            var heardUs: Boolean = false,
            var reachedUs: Boolean = false,
            var bestSnr: Double? = null,
            var lastAt: Long = Long.MIN_VALUE,
        )

        val acc = LinkedHashMap<String, Acc>()
        for (echo in echoes) {
            val hops = echo.hops
            if (hops.isEmpty()) continue
            val last = hops.lastIndex
            // A hop repeated within one path (a loop) is still one
            // repeater carrying one packet.
            for (hash in hops.toSet()) {
                val a = acc.getOrPut(hash) { Acc() }
                a.relayed++
                if (a.lastAt < echo.atMillis) a.lastAt = echo.atMillis
            }
            acc[hops[0]]?.heardUs = true
            acc[hops[last]]?.let { a ->
                a.reachedUs = true
                // Measured only for the transmitter of THIS copy.
                val snr = echo.snr
                if (snr != null && (a.bestSnr == null || snr > a.bestSnr!!)) a.bestSnr = snr
            }
        }
        return acc.entries
            .map { (hash, a) ->
                Relay(
                    hashHex = hash,
                    relayed = a.relayed,
                    heardUs = a.heardUs,
                    reachedUs = a.reachedUs,
                    bestSnr = a.bestSnr,
                    lastAtMillis = a.lastAt,
                )
            }
            .sortedWith(compareByDescending<Relay> { it.relayed }.thenBy { it.hashHex })
    }

    /**
     * What a single relay's direction flags mean, in words.
     *
     * Stated per row rather than once in a legend because "heard us" and
     * "reached us" are easy to read as the same thing, and the whole
     * value of the screen is that they are not.
     */
    fun direction(relay: Relay): String = when {
        relay.isTwoWay -> "Heard you directly, and you heard it directly"
        relay.heardUs -> "Heard you directly; its own transmission reached you only via another hop"
        relay.reachedUs -> "You heard it directly; it picked your traffic up from another node"
        else -> "Carried your traffic, but neither end of that link was measured here"
    }

    /** One line above the list. */
    fun summary(echoes: List<Echo>, relays: List<Relay>): String = when {
        echoes.isEmpty() ->
            "Nothing yet. Send a flood advert and leave this open — nodes that carry " +
                "it will appear here as their copies come back."
        relays.isEmpty() -> "Heard ${echoes.size} copy(s) of your own traffic, none with a route."
        else -> "${relays.size} node(s) seen carrying your traffic, " +
            "from ${echoes.size} returned copy(s)."
    }

    /**
     * Why the number can only ever be a floor.
     *
     * A repeater that relays our advert away from us — the normal case
     * for anything past the first ring — never sends a copy back into
     * our radio, so it cannot appear here at all. Presenting this list
     * as "my coverage" without saying that would overstate it in the
     * one direction that matters.
     */
    const val CAVEAT: String =
        "This lists nodes whose copy of your traffic came back within range of this " +
            "radio. A node that carried your traffic onward without a copy reaching " +
            "you again cannot appear here — so treat this as a floor, not a map of your " +
            "coverage. Repeaters, room servers and companions with client-repeat all " +
            "relay, so not every row is a repeater."
}
