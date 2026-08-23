package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
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
 * Two prefixes matter, at two widths:
 *
 *  - **The path hash**, `path_hash_mode + 1` bytes (1–3), appended by
 *    every repeater that forwards a packet and matched on the way back
 *    (`Mesh::onPacketReceived` → `self_id.isHashMatch(pkt->path,
 *    pkt->getPathHashSize())`, `src/Mesh.cpp:89`; the width travels in
 *    the top two bits of `path_len`, `src/Mesh.cpp:449`). Two repeaters
 *    sharing this prefix are the same node as far as a stored route is
 *    concerned: both answer to it, both forward for it, and a direct
 *    packet routed along that path can be picked up by the wrong one.
 *    **This is the width the caller must pass**, and it is a property of
 *    the mesh rather than of any one node (CLAUDE.md; [PathHashMode]).
 *  - **The destination hash**, always exactly one byte —
 *    `#define PATH_HASH_SIZE 1`, `dest.copyHashTo(dest_hash)`
 *    (`src/Mesh.cpp:443-444, 462-463`). Every direct packet carries one,
 *    for every node type, not just repeaters. A clash here is noise
 *    rather than breakage — the receiver still fails the MAC and drops
 *    it — but it is free to avoid, so this generator prefers a key that
 *    avoids it and says so when it could not.
 *
 * Because the destination hash is the *narrower* of the two, a key clear
 * at one byte is clear at every width. So there is only one search, with
 * a hard requirement and a preference, and [Outcome.Generated.clearOfFirstByte]
 * reports which of the two the caller got.
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
     * Attempts before the search gives up.
     *
     * Sized against the real bound, which is the one-byte space: 254
     * usable first bytes, so if even a tenth of them are free a run of
     * 4000 consecutive misses has probability ~0.9^4000. Anything that
     * exhausts this budget is a mesh with essentially every prefix
     * taken, which is a fact worth reporting rather than a slow search
     * worth continuing.
     */
    const val DEFAULT_MAX_ATTEMPTS = 4_000

    /** The widest prefix worth searching against; see [PathHashMode]. */
    const val MAX_WIDTH_BYTES = 4

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

    sealed interface Outcome {

        /**
         * A key nobody else on this mesh answers to at [widthBytes].
         *
         * [clearOfFirstByte] is false when the search could only satisfy
         * the path-hash width and had to accept a shared destination
         * hash — true is better, false is still correct.
         */
        data class Generated(
            val candidate: Candidate,
            val widthBytes: Int,
            val clearOfFirstByte: Boolean,
            val attempts: Int,
        ) : Outcome

        /**
         * No key clear at [widthBytes] turned up in [attempts] tries.
         *
         * [takenPrefixes] of [totalPrefixes] were already in use, which
         * is what makes this actionable: at one byte per hop there are
         * only 256 names to go round, and a mesh that has used them all
         * needs a wider path hash, not a luckier key.
         */
        data class Exhausted(
            val widthBytes: Int,
            val attempts: Int,
            val takenPrefixes: Int,
            val totalPrefixes: Long,
        ) : Outcome
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
            val key = raw.trim().lowercase()
            if (key.length != 64 || !isHexString(key)) continue
            out += key.take(width * 2)
        }
        return out
    }

    /** True when [publicKeyHex] shares its leading bytes with [taken]. */
    fun collides(publicKeyHex: String, taken: Set<String>, widthBytes: Int): Boolean {
        val key = publicKeyHex.trim().lowercase()
        if (key.length < clampWidth(widthBytes) * 2) return false
        return key.take(clampWidth(widthBytes) * 2) in taken
    }

    /**
     * A fresh identity whose first [widthBytes] bytes are not already in
     * use by any of [knownKeys] — the full public keys, hex, of every
     * node this phone has heard of.
     *
     * [knownKeys] is deliberately every node and not just the repeaters.
     * Only repeaters put themselves in a path, so only repeaters can
     * clash there — but the destination hash is one byte of *every*
     * node's key, and a contact that is a plain chat node today can be
     * reflashed as a repeater tomorrow. Avoiding the wider set costs
     * nothing while there is room, and when there isn't, the outcome
     * says so.
     */
    fun generate(
        crypto: CryptoProvider,
        widthBytes: Int,
        knownKeys: Collection<String>,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): Outcome {
        val width = clampWidth(widthBytes)
        val pathPrefixes = prefixesOf(knownKeys, width)
        val firstBytes = prefixesOf(knownKeys, 1)

        var fallback: Candidate? = null
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val candidate = candidate(crypto) ?: continue
            if (collides(candidate.publicKeyHex, pathPrefixes, width)) continue
            if (!collides(candidate.publicKeyHex, firstBytes, 1)) {
                return Outcome.Generated(candidate, width, clearOfFirstByte = true, attempts)
            }
            // Clear where it has to be, shares a destination hash with
            // someone. Hold it and keep looking for the better one.
            if (fallback == null) fallback = candidate
        }
        fallback?.let {
            return Outcome.Generated(it, width, clearOfFirstByte = false, attempts)
        }
        return Outcome.Exhausted(
            widthBytes = width,
            attempts = attempts,
            takenPrefixes = pathPrefixes.size,
            totalPrefixes = totalPrefixes(width),
        )
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
}
