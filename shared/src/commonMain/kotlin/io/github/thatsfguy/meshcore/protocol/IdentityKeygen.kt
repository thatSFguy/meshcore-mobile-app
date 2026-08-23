package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.fixed
import io.github.thatsfguy.meshcore.util.isHexString
import io.github.thatsfguy.meshcore.util.toHex

/**
 * Generating a repeater identity whose **leading bytes** are its own.
 *
 * ## Why the leading bytes are the whole point
 *
 * A MeshCore node is never named on air by its full 32-byte public key.
 * It is named by a prefix of it, and the firmware is explicit that the
 * prefix IS the identifier:
 *
 * ```
 * memcpy(dest, pub_key, len);    // hash is just prefix of pub_key
 * ```
 * (`Identity::copyHashTo`, `src/Identity.h:20-26`)
 *
 * Two prefixes matter, at two widths, and they are the two levels of
 * [ClashLevel]:
 *
 *  - **The path hash** — [ClashLevel.ROUTE] — `path_hash_mode + 1` bytes
 *    (1–3), appended by every repeater that forwards a packet and
 *    matched on the way back (`Mesh::onPacketReceived` →
 *    `self_id.isHashMatch(pkt->path, pkt->getPathHashSize())`,
 *    `src/Mesh.cpp:89`; the width travels in the top two bits of
 *    `path_len`, `src/Mesh.cpp:449`). Two repeaters sharing this prefix
 *    are the same node as far as a stored route is concerned: both
 *    answer to it, both forward for it, and a direct packet routed along
 *    that path can be picked up by the wrong one. **This is the width
 *    the caller passes**, and it is a property of the mesh rather than
 *    of any one node (CLAUDE.md; [PathHashMode]).
 *  - **The destination hash** — [ClashLevel.DESTINATION] — always
 *    exactly one byte: `#define PATH_HASH_SIZE 1`,
 *    `dest.copyHashTo(dest_hash)` (`src/Mesh.cpp:443-444, 462-463`).
 *    Every direct packet carries one, for every node type, not just
 *    repeaters. A clash here is noise rather than breakage — the wrong
 *    receiver fails the MAC and drops it — but it is worth avoiding when
 *    there is room.
 *
 * The destination hash is the narrower of the two, so a key clear at one
 * byte is clear at every width. That makes this one search with one
 * ordering rather than two passes.
 *
 * ## When there is no clean key, the clash is chosen and not accepted
 *
 * On a busy mesh at one byte per hop there are 254 usable names and they
 * do run out. Giving up is the wrong answer — the operator still needs
 * a key — and so is taking the first one that fits, because *who* the
 * new node collides with is the entire difference between a harmless
 * coincidence and a broken route.
 *
 * So every candidate is scored by the **worst** node it collides with
 * ([Remoteness]), and the search keeps the best it saw:
 *
 *  1. no clash at all — returned immediately;
 *  2. otherwise a destination-hash clash beats a route clash;
 *  3. otherwise, within a level, the more remote the other node the
 *     better — a repeater 90 km away that has never appeared in one of
 *     our paths, rather than the one on the next hill.
 *
 * [Outcome.clash] is never null-by-omission: when it is set, the caller
 * is expected to say so, name the node, and let the operator decide.
 *
 * ## What it refuses
 *
 * Every candidate has to satisfy the firmware's own acceptance rule
 * before it is offered at all — a public key beginning `00` or `ff` is
 * rejected by `LocalIdentity::validatePrivateKey` (`src/Identity.cpp:71-72`),
 * so about one key in 128 would have failed at the node for a reason
 * nothing on screen could explain. See [IdentityKey.isAcceptablePublicKey].
 */
object IdentityKeygen {

    /**
     * Attempts before the search settles for the best clash it found.
     *
     * Sized against the real bound, which is the one-byte space: 254
     * usable first bytes, so if even a tenth of them are free a run of
     * 4000 consecutive misses has probability ~0.9^4000. A search that
     * uses the whole budget is a mesh with essentially every prefix
     * taken — and that is the case the scoring exists for.
     */
    const val DEFAULT_MAX_ATTEMPTS = 4_000

    /** The widest prefix worth searching against; see [PathHashMode]. */
    const val MAX_WIDTH_BYTES = 4

    /**
     * How bad it would be to share leading bytes with a given node.
     *
     * A rank, deliberately coarse, and never a measurement: the inputs
     * are an advertised position that may be stale and a hop count that
     * describes one moment's route. It exists to order candidates, not
     * to be displayed as a number.
     *
     * Two axes, in priority order:
     *
     *  - **What the node is.** Only repeaters and room servers put
     *    themselves into a packet's path (`Mesh.cpp:345-349`), so only
     *    they can cause a [ClashLevel.ROUTE] problem at all. A chat or
     *    sensor node sharing a prefix costs a destination-hash near-miss
     *    and nothing else, which is why every non-infrastructure node
     *    outranks every repeater here rather than merely scoring higher.
     *  - **How far away it is.** Geographic distance where both nodes
     *    have advertised a position; otherwise hops, which is the more
     *    routing-relevant number but is only known for contacts with a
     *    stored path. Neither known means [UNKNOWN] — assume the worst,
     *    because a node we cannot place is not a node we can call far.
     */
    object Remoteness {

        /** Unknown, or our own radio. Nothing is worse to collide with. */
        const val UNKNOWN = 0

        /** Highest rank an infrastructure node can reach. */
        const val INFRASTRUCTURE_MAX = 5

        /** Where non-infrastructure ranks start — above every repeater. */
        const val NON_INFRASTRUCTURE_BASE = INFRASTRUCTURE_MAX + 1

        /**
         * [distanceMetres] and [hops] are both optional; pass null for
         * whichever is not known. [hops] should be null for a flood
         * contact, which has no route to count.
         */
        fun of(distanceMetres: Double?, hops: Int?, isInfrastructure: Boolean): Int {
            val band = when {
                distanceMetres != null && distanceMetres >= 0 -> when {
                    distanceMetres < 2_000 -> 1
                    distanceMetres < 10_000 -> 2
                    distanceMetres < 30_000 -> 3
                    distanceMetres < 100_000 -> 4
                    else -> 5
                }
                // No position. Hops are coarser but still real: a node
                // four repeaters away is not one of our neighbours.
                hops != null && hops >= 0 -> when {
                    hops <= 1 -> 1
                    hops == 2 -> 2
                    hops == 3 -> 3
                    else -> 4
                }
                else -> UNKNOWN
            }
            return if (isInfrastructure) band else NON_INFRASTRUCTURE_BASE + band
        }

        /**
         * How far away a node is, in words, for the sentence that names
         * it. "unknown" is said out loud rather than left out: a clash
         * with a node we cannot place is the one the operator most needs
         * to go and check.
         */
        fun describe(distanceMetres: Double?, hops: Int?): String = when {
            distanceMetres != null && distanceMetres >= 0 -> when {
                distanceMetres < 1_000 -> "${fixed(distanceMetres, 0)} m away"
                distanceMetres < 100_000 -> "${fixed(distanceMetres / 1000, 1)} km away"
                else -> "${fixed(distanceMetres / 1000, 0)} km away"
            }
            hops != null && hops >= 0 ->
                if (hops == 1) "1 hop away" else "$hops hops away"
            else -> "distance unknown"
        }
    }

    /**
     * A node whose leading bytes are already spoken for.
     *
     * [label] is display text the caller builds — a name and how far
     * away it is — because this file should not be deciding how a node
     * is described to a person, and the caller is the only thing that
     * knows.
     */
    data class KnownNode(
        val publicKeyHex: String,
        val label: String,
        val remoteness: Int,
    )

    /** A generated keypair, in all three forms the caller needs. */
    data class Candidate(
        /** 32-byte Ed25519 seed, 64 hex — the short form to write down. */
        val seedHex: String,
        /** 64-byte expanded key, 128 hex — what `set prv.key` takes. */
        val privateKeyHex: String,
        /** The public key this becomes: the node's new identity. */
        val publicKeyHex: String,
    ) {
        /** The leading [widthBytes] bytes, as hex — the on-air name. */
        fun prefixHex(widthBytes: Int): String =
            publicKeyHex.take(clampWidth(widthBytes) * 2)
    }

    /** Which of the two on-air names is shared. See the class docs. */
    enum class ClashLevel {
        /** The path hash: a routing problem between two repeaters. */
        ROUTE,

        /** The one-byte destination hash: noise, not breakage. */
        DESTINATION,
    }

    /** The worst node a candidate shares leading bytes with. */
    data class Clash(val level: ClashLevel, val with: KnownNode)

    /**
     * A key, and the truth about it. [clash] is null when nothing on
     * this mesh answers to those bytes; otherwise it names what the
     * search settled for and the caller must say so.
     *
     * [attempts] is how many keypairs were drawn before the search
     * stopped — one, usually, and the whole budget when there was no
     * clean key to find. [takenPrefixes] is how many of the
     * [totalPrefixes] names at this width are already spoken for, which
     * is what turns "unlucky" into "this mesh is full".
     */
    data class Outcome(
        val candidate: Candidate,
        val widthBytes: Int,
        val attempts: Int,
        val clash: Clash?,
        val takenPrefixes: Int,
    ) {
        val isClean: Boolean get() = clash == null
    }

    /** [widthBytes], clamped to something a prefix can actually be. */
    fun clampWidth(widthBytes: Int): Int = widthBytes.coerceIn(1, MAX_WIDTH_BYTES)

    /**
     * The leading-[widthBytes] prefixes of [publicKeyHexes], lowercased.
     *
     * Anything that isn't a full 32-byte public key is dropped rather
     * than truncated to whatever length it happens to be: a short or
     * malformed entry would otherwise contribute a prefix that names no
     * real node, and the search would avoid a name nobody holds.
     */
    fun prefixesOf(publicKeyHexes: Iterable<String>, widthBytes: Int): Set<String> {
        val width = clampWidth(widthBytes)
        val out = mutableSetOf<String>()
        for (raw in publicKeyHexes) {
            val key = normalise(raw) ?: continue
            out += key.take(width * 2)
        }
        return out
    }

    /** True when [publicKeyHex] shares its leading bytes with [taken]. */
    fun collides(publicKeyHex: String, taken: Set<String>, widthBytes: Int): Boolean {
        val width = clampWidth(widthBytes)
        val key = publicKeyHex.trim().lowercase()
        if (key.length < width * 2) return false
        return key.take(width * 2) in taken
    }

    /**
     * A fresh identity for a node whose path hash is [widthBytes] bytes
     * wide, judged against every node the caller knows of.
     *
     * [known] is deliberately every node and not just the repeaters.
     * Only repeaters put themselves in a path, so only repeaters can
     * clash there — but the destination hash is one byte of *every*
     * node's key, and a contact that is a plain chat node today can be
     * reflashed as a repeater tomorrow. [Remoteness] already ranks a
     * chat node as the safer thing to collide with, so including them
     * costs nothing and keeps the ordering honest.
     *
     * Returns null only when the platform could not produce a keypair at
     * all — an iOS build with the Ed25519 bridge missing, say. That is a
     * different failure from "no clean key exists", which comes back as
     * an [Outcome] carrying a [Clash].
     */
    fun generate(
        crypto: CryptoProvider,
        widthBytes: Int,
        known: Collection<KnownNode>,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Outcome? {
        val width = clampWidth(widthBytes)
        // The worst node holding each prefix, at both widths. Worst, not
        // any: a candidate is only as good as the nearest thing that
        // answers to its name.
        val routeHolders = worstByPrefix(known, width)
        val destinationHolders = worstByPrefix(known, 1)

        var best: Outcome? = null
        var attempts = 0
        var consecutiveFailures = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = candidate(crypto)
            if (candidate == null) {
                // A platform that cannot make one key will not make the
                // four thousandth either; fail fast rather than spin.
                if (++consecutiveFailures >= PLATFORM_FAILURE_LIMIT) return null
                continue
            }
            consecutiveFailures = 0

            val clash = clashFor(candidate, width, routeHolders, destinationHolders)
            val outcome = Outcome(candidate, width, attempts, clash, routeHolders.size)
            if (clash == null) return outcome
            if (best == null || rank(outcome) > rank(best)) best = outcome
        }
        return best?.copy(attempts = attempts)
    }

    /** The worst clash [candidate] has, or null when it has none. */
    private fun clashFor(
        candidate: Candidate,
        width: Int,
        routeHolders: Map<String, KnownNode>,
        destinationHolders: Map<String, KnownNode>,
    ): Clash? {
        routeHolders[candidate.publicKeyHex.take(width * 2)]?.let {
            return Clash(ClashLevel.ROUTE, it)
        }
        destinationHolders[candidate.publicKeyHex.take(2)]?.let {
            return Clash(ClashLevel.DESTINATION, it)
        }
        return null
    }

    /** Higher is better: clean, then level, then how remote the other node is. */
    private fun rank(outcome: Outcome): Int {
        val clash = outcome.clash ?: return Int.MAX_VALUE
        val levelRank = when (clash.level) {
            ClashLevel.DESTINATION -> 1
            ClashLevel.ROUTE -> 0
        }
        return levelRank * LEVEL_WEIGHT + clash.with.remoteness
    }

    /**
     * Prefix -> the least remote node holding it, at [widthBytes].
     *
     * Ties are broken by nothing at all: two equally close nodes are
     * equally bad to collide with, and picking between them would only
     * make the result depend on map iteration order.
     */
    private fun worstByPrefix(
        known: Collection<KnownNode>,
        widthBytes: Int,
    ): Map<String, KnownNode> {
        val width = clampWidth(widthBytes)
        val out = mutableMapOf<String, KnownNode>()
        for (node in known) {
            val key = normalise(node.publicKeyHex) ?: continue
            val prefix = key.take(width * 2)
            val existing = out[prefix]
            if (existing == null || node.remoteness < existing.remoteness) out[prefix] = node
        }
        return out
    }

    /**
     * How many distinct prefixes exist at [widthBytes] — 256, 65 536,
     * 16 777 216, 4 294 967 296. A [Long] because the four-byte answer
     * does not fit in an Int, which is the kind of detail that turns a
     * capacity message into a negative number.
     */
    fun totalPrefixes(widthBytes: Int): Long {
        var total = 1L
        repeat(clampWidth(widthBytes)) { total *= 256L }
        return total
    }

    /** A full 32-byte public key in lowercase hex, or null. */
    private fun normalise(raw: String): String? {
        val key = raw.trim().lowercase()
        return if (key.length == 64 && isHexString(key)) key else null
    }

    /** One keypair, or null if the firmware would refuse it. */
    private fun candidate(crypto: CryptoProvider): Candidate? {
        val seed = crypto.generateEd25519Seed()
        if (seed.size != 32) return null
        val pub = runCatching { crypto.ed25519PublicKey(seed) }.getOrNull() ?: return null
        if (pub.size != 32) return null
        val publicKeyHex = pub.toHex()
        if (!IdentityKey.isAcceptablePublicKey(publicKeyHex)) return null
        val seedHex = seed.toHex()
        if (IdentityKey.isDegenerate(seedHex)) return null
        val privateKeyHex = MeshIdentity.expandSeed(crypto, seed).toHex()
        return Candidate(seedHex, privateKeyHex, publicKeyHex)
    }

    /**
     * Bigger than any remoteness, so the level always decides first: a
     * destination-hash clash with our own radio still beats a route
     * clash with a repeater on the far side of the state.
     */
    private const val LEVEL_WEIGHT = 1_000

    /** Consecutive keypair failures that mean the platform, not luck. */
    private const val PLATFORM_FAILURE_LIMIT = 16
}
