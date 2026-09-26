package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes

/**
 * What says a heard-but-not-added repeater is worth adding.
 *
 * Two signals, both facts this radio observed rather than guesses:
 *
 * - **It carried your traffic.** Every message that reaches us carries its
 *   route: one short hash per repeater it passed, [RouteSample]. A
 *   repeater whose hash appears in those routes is one the mesh really
 *   uses to reach you.
 * - **It is heard direct.** An advert that arrived with zero hops came
 *   from a repeater within this radio's own range — a neighbour.
 *
 * Distance was deliberately left out: on LoRa, terrain decides reach far
 * more than kilometres do.
 *
 * The one judgement in here is ambiguity. A route hash is the first one to
 * three bytes of a public key, so two nodes can share one — at one byte,
 * routinely. A hash that matches more than one node we know is credited to
 * none of them: counting it for a repeater that did not relay anything
 * would add it on the strength of someone else's traffic. The same rule as
 * "Who repeats me" ([HeardRepeatsModel]).
 */
object RepeaterSignals {

    /** How far back a route counts: a week of traffic. */
    const val RELAY_WINDOW_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Relayed messages needed before a repeater is credited with carrying
     * your traffic, shown as such, or auto-added.
     *
     * Chosen by the operator on 2026-09-25 after seeing the first cut on
     * the phone: with a threshold of one, a repeater in Wisconsin was
     * credited because a single flooded message had passed through it, and
     * a lone hit on a 2-byte hash can as easily be a node we have never
     * heard of. Three in a week is a pattern rather than an accident.
     */
    const val MIN_RELAYS = 3

    /** A received message's route, as stored with it. */
    data class RouteSample(val pathHex: String, val hashWidth: Int)

    /** The two signals for one node, as the Heard section shows them. */
    data class Signals(
        /** Messages you received that went through this node's hash. */
        val relayed: Int,
        /** Its hash is shared with another node we know, so [relayed] cannot be credited. */
        val relayAmbiguous: Boolean,
        /** An advert from it arrived with zero hops. */
        val heardDirect: Boolean,
    ) {
        /**
         * Relayed traffic that can be credited to this node and no other,
         * and enough of it ([MIN_RELAYS]) to be a pattern.
         */
        val provenRelay: Boolean get() = relayed >= MIN_RELAYS && !relayAmbiguous
    }

    /** Which app-side auto-add rules are switched on. */
    data class Rules(val relaysMyTraffic: Boolean, val heardDirect: Boolean) {
        val any: Boolean get() = relaysMyTraffic || heardDirect
    }

    /**
     * The hop hashes in [pathHex], lower-case, each [hashWidth] bytes.
     * A path whose length is not a whole number of hops is malformed and
     * yields nothing rather than a guess at where the hops divide.
     */
    fun hopHashes(pathHex: String, hashWidth: Int): List<String> {
        if (hashWidth !in 1..3) return emptyList()
        val clean = pathHex.trim().lowercase()
        val step = hashWidth * 2
        if (clean.isEmpty() || clean.length % step != 0) return emptyList()
        if (clean.any { it !in '0'..'9' && it !in 'a'..'f' }) return emptyList()
        return clean.chunked(step)
    }

    /**
     * How many of [samples] passed through each hop hash. A hash is counted
     * once per message, however many times it appears in that route.
     */
    fun relayCounts(samples: List<RouteSample>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (s in samples) {
            for (h in hopHashes(s.pathHex, s.hashWidth).toSet()) {
                counts[h] = (counts[h] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * The signals for the node with public key [keyHex].
     *
     * [minHops] is the fewest hops any of its adverts arrived with, or null
     * when that was never recorded. [otherKnownKeys] is every other node we
     * know — contacts and other heard nodes — and decides ambiguity.
     */
    fun signalsFor(
        keyHex: String,
        minHops: Int?,
        counts: Map<String, Int>,
        otherKnownKeys: Collection<String>,
    ): Signals {
        val key = keyHex.lowercase()
        val others = otherKnownKeys.map { it.lowercase() }.filter { it != key }
        var relayed = 0
        var ambiguous = false
        for (width in 1..3) {
            val hash = key.take(width * 2)
            if (hash.length < width * 2) continue
            val n = counts[hash] ?: continue
            relayed += n
            if (others.any { it.startsWith(hash) }) ambiguous = true
        }
        return Signals(relayed = relayed, relayAmbiguous = ambiguous, heardDirect = minHops == 0)
    }

    /** Whether [signals] meet any rule switched on in [rules]. */
    fun matches(signals: Signals, rules: Rules): Boolean =
        (rules.relaysMyTraffic && signals.provenRelay) || (rules.heardDirect && signals.heardDirect)

    /** A heard node, as the auto-add pass needs it. */
    data class Candidate(val keyHex: String, val type: Int, val minHops: Int?)

    /**
     * Which of [candidates] an auto-add pass should add, in order.
     *
     * Nothing at all when no rule is on, when the radio's policy is not yet
     * known ([radioAutoAddFlags] null), when the radio adds repeaters itself
     * (it would add every one of them anyway), or when its contact list is
     * full. Only repeaters, never one already [tried] this session, and
     * only those meeting a switched-on rule.
     */
    fun toAutoAdd(
        candidates: List<Candidate>,
        rules: Rules,
        radioAutoAddFlags: Int?,
        contactsFull: Boolean,
        counts: Map<String, Int>,
        knownKeys: Collection<String>,
        tried: Set<String> = emptySet(),
    ): List<String> {
        if (!rules.any || radioAutoAddFlags == null || contactsFull) return emptyList()
        if (radioAutoAddFlags and Codes.AUTO_ADD_REPEATER != 0) return emptyList()
        return candidates
            .filter { it.type == Codes.ADV_TYPE_REPEATER && it.keyHex !in tried }
            .filter { matches(signalsFor(it.keyHex, it.minHops, counts, knownKeys), rules) }
            .map { it.keyHex }
    }

    /** One line for a Heard row, or null when there is nothing to say. */
    fun describe(signals: Signals): String? {
        val parts = buildList {
            if (signals.heardDirect) add("heard direct")
            when {
                // Below the threshold it is not a claim worth making.
                signals.relayed < MIN_RELAYS -> {}
                signals.relayAmbiguous -> add("its route hash is shared, so relays can't be credited")
                else -> add(
                    "relayed ${signals.relayed} message${if (signals.relayed == 1) "" else "s"} you received",
                )
            }
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }
}
