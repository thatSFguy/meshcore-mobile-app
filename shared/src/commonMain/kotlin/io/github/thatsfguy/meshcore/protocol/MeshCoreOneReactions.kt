package io.github.thatsfguy.meshcore.protocol

/**
 * The OTHER reaction convention on the mesh — MeshCore One's.
 *
 * MeshCore has no reaction field and the firmware has no reaction
 * payload (issue #880 proposes one and is still open), so every client
 * that offers reactions invents a text format. Two are live here:
 *
 *  - [Reactions] — `r:HHHH:II`, from MeshCore Open. What this app SENDS,
 *    and what its forks (Meshtrax, SigurdOS) understand.
 *  - this one — `{emoji}@[{sender}]\n{hash}`, from **MeshCore One**
 *    (`Avi0n/MeshCoreOne`, `docs/Reactions.md`, a document explicitly
 *    written for interoperability). Understood here, not emitted.
 *
 * Their hash is the better design and it is worth saying why, because
 * the choice not to adopt it is about neighbours rather than merit:
 * ours inherits Dart's `String.hashCode` truncated to 16 bits — another
 * runtime's implementation detail, mirrored by hand ([DartStringHash]).
 * Theirs is SHA-256 over the message text and the sender's own
 * timestamp, so any language reproduces it, and 40 bits collide far less
 * often than 16.
 *
 * **Verified against live traffic, not just read.** On 2026-08-23 a
 * reaction arrived on the Public channel reading
 * `😂@[KE8PKP_TACW]\nb0c26wb5`, and the message it targeted was
 * `"Alexa, find me the nearest Crab Rangoon"` at 1787534834. This code
 * reproduces `b0c26wb5` from those two values, and the test pins that
 * captured pair rather than an example from the document.
 */
object MeshCoreOneReactions {

    /** The hash is 5 bytes, which is exactly 8 Crockford characters. */
    const val HASH_LENGTH = 8

    /**
     * A reaction as it arrived.
     *
     * [targetSenderName] is present on channel reactions only: two
     * people can post identical text in the same second, so the format
     * carries the author to tell those apart. It is unauthenticated
     * display text like every other channel sender name (§12) and is
     * used to narrow a match, never to establish who reacted.
     */
    data class Parsed(
        val emoji: String,
        val targetSenderName: String?,
        val targetHash: String,
    )

    /**
     * Parse [text] as a MeshCore One reaction, or null.
     *
     * Deliberately strict, because a false positive HIDES a message
     * somebody typed. Three things must hold: the hash is exactly eight
     * Crockford Base32 characters; the emoji part carries no ASCII
     * letters or digits, so `hey@[Bob]\nsomething` stays a message; and
     * there is nothing after the hash.
     */
    fun parse(text: String): Parsed? {
        val trimmed = text.trim()
        val newline = trimmed.indexOf('\n')
        if (newline <= 0) return null

        val head = trimmed.substring(0, newline).trim()
        val hash = normaliseHash(trimmed.substring(newline + 1).trim()) ?: return null

        // Channel form carries the target's sender name in brackets.
        val marker = head.indexOf("@[")
        if (marker < 0) {
            val emoji = head.takeIf { isEmojiOnly(it) } ?: return null
            return Parsed(emoji, null, hash)
        }
        if (!head.endsWith("]")) return null
        val emoji = head.substring(0, marker).trim().takeIf { isEmojiOnly(it) } ?: return null
        val name = head.substring(marker + 2, head.length - 1)
        if (name.isBlank()) return null
        return Parsed(emoji, name, hash)
    }

    /**
     * The target hash for a message, per `docs/Reactions.md`:
     * `SHA-256(UTF-8(text) + LE(UInt32(senderTimestamp)))`, first 5
     * bytes, Crockford Base32, lower case.
     *
     * [text] is the message body WITHOUT any sender-name prefix — the
     * channel form carries the author separately for exactly that
     * reason — and [senderTimestampSeconds] is the sender's own
     * timestamp, not our receive time, or no two nodes agree.
     */
    fun targetHash(
        text: String,
        senderTimestampSeconds: Long,
        sha256: (ByteArray) -> ByteArray,
    ): String {
        val ts = senderTimestampSeconds.toInt()
        val stamped = byteArrayOf(
            (ts and 0xFF).toByte(),
            ((ts shr 8) and 0xFF).toByte(),
            ((ts shr 16) and 0xFF).toByte(),
            ((ts shr 24) and 0xFF).toByte(),
        )
        val digest = sha256(text.encodeToByteArray() + stamped)
        return CrockfordBase32.encode(digest.copyOfRange(0, 5))
    }

    /**
     * The message [parsed] points at, or null when none matches.
     *
     * Never guesses: an ambiguous hash (two candidates, which 40 bits
     * makes rare but not impossible) resolves to nothing rather than to
     * the newer one, for the same reason a colliding hop is never named.
     * On a channel the sender name in the reaction must also match the
     * candidate's, which is what that field is for.
     */
    fun target(
        candidates: List<ReactionRouting.Candidate>,
        parsed: Parsed,
        isChannel: Boolean,
        sha256: (ByteArray) -> ByteArray,
    ): ReactionRouting.Candidate? {
        val hits = candidates.filter { candidate ->
            if (isChannel && parsed.targetSenderName != null &&
                !parsed.targetSenderName.equals(candidate.senderName, ignoreCase = true)
            ) {
                return@filter false
            }
            targetHash(candidate.text, candidate.timestamp, sha256) == parsed.targetHash
        }
        return hits.singleOrNull()
    }

    /** Eight Crockford characters, normalised, or null. */
    private fun normaliseHash(raw: String): String? {
        if (raw.length != HASH_LENGTH) return null
        val out = StringBuilder(HASH_LENGTH)
        for (c in raw) {
            // Crockford's published substitutions: the alphabet omits
            // I, L, O and U so that they can be read back as the digits
            // people mistake them for.
            val n = when (c.lowercaseChar()) {
                'o' -> '0'
                'i', 'l' -> '1'
                else -> c.lowercaseChar()
            }
            if (n !in CrockfordBase32.ALPHABET_LOWER) return null
            out.append(n)
        }
        return out.toString()
    }

    /**
     * True for a short run of non-ASCII-alphanumeric characters — an
     * emoji, possibly with a variation selector or skin-tone modifier,
     * and nothing that looks like typed words.
     */
    private fun isEmojiOnly(s: String): Boolean {
        if (s.isEmpty() || s.length > 16) return false
        return s.none { it.isWhitespace() || it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
    }
}

/**
 * Crockford Base32 — the encoding MeshCore One's reaction hash uses.
 *
 * Chosen by that format because 5 bytes land on exactly 8 characters
 * using only alphanumerics, so nothing in the output can collide with
 * the `@`, `[`, `]` and newline that delimit the wire format. The
 * alphabet deliberately omits I, L, O and U.
 */
object CrockfordBase32 {

    const val ALPHABET_LOWER = "0123456789abcdefghjkmnpqrstvwxyz"

    /** Big-endian, 5 bits at a time. Lower case, as seen on the wire. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0L
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET_LOWER[((buffer shr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) {
            out.append(ALPHABET_LOWER[((buffer shl (5 - bits)) and 0x1F).toInt()])
        }
        return out.toString()
    }
}

/**
 * "Is this text a reaction at all?" — across every format this app
 * reads.
 *
 * The display sites do not care whose convention it is; they care that
 * `r:1a2b:00` and `😂@[Someone]\nb0c26wb5` are both reactions and
 * neither should be shown to a human as typed words. Asking here rather
 * than at each site is what stops a third format being understood by
 * the receiver and still rendered as wire text in the chat list, the
 * bubble and the notification.
 */
object AnyReaction {

    /** The emoji of a reaction in any understood format, or null. */
    fun emojiOf(text: String): String? =
        Reactions.parse(text)?.emoji ?: MeshCoreOneReactions.parse(text)?.emoji

    /** True when [text] is a reaction rather than something someone typed. */
    fun isReaction(text: String): Boolean = emojiOf(text) != null
}
