package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.isHexDigits

/**
 * Suppressing traffic you don't want (PARITY.md §3).
 *
 * ## Two different things, deliberately not one feature
 *
 * PARITY §3 says to block by *key*, never by sender name. That is right
 * — and for channels it is not possible. A MeshCore group message is
 * `"name: text"` inside the channel ciphertext (MESHCORE_PROTOCOL §9,
 * §10); there is no sender key on the wire, because a channel has no
 * per-sender identity at all. Anyone holding the PSK can type any name.
 *
 * So this file has two mechanisms with deliberately different names and
 * different promises:
 *
 *  - [isBlockedSender] — direct messages, matched on the sender's
 *    public key. This is a real block: the key is cryptographic
 *    identity, and a blocked peer cannot get around it by renaming.
 *  - [isFilteredChannelName] — channels, matched on display name. This
 *    is a NOISE FILTER. It stops a spammer who keeps their name, and
 *    nothing more. The UI must never call it a block, because a user
 *    who believes they've blocked someone will behave differently from
 *    one who knows they've hidden a name.
 *
 * Conflating the two would be the security bug: shipping name-matching
 * under the word "block" is how someone ends up trusting that a person
 * cannot reach them.
 */
object BlockList {

    /** Cap on stored entries — bounded storage, bounded matching cost. */
    const val MAX_ENTRIES = 512

    // ------------------------------------------------------------------
    // Direct messages — a real block, on the public key
    // ------------------------------------------------------------------

    /**
     * Canonical form of a key for storage and comparison: lowercase hex.
     * Returns null for anything that isn't a full 32-byte public key —
     * a prefix is NOT accepted, because a 6-byte prefix is 48 bits and
     * blocking one would block everyone who collides with it.
     */
    fun canonicalKey(raw: String?): String? {
        val key = raw?.trim()?.lowercase() ?: return null
        if (key.length != 64) return null
        return key.takeIf { isHexDigits(it) }
    }

    /**
     * True when a direct message from [senderKeyHex] should be dropped.
     *
     * [senderKeyHex] null means the sender could not be resolved to a
     * full key — only a 6-byte prefix arrived and no contact matched it.
     * That is NOT treated as blocked: dropping unresolved traffic would
     * silently discard messages from anyone not yet in the contact list.
     */
    fun isBlockedSender(senderKeyHex: String?, blockedKeys: Set<String>): Boolean {
        if (blockedKeys.isEmpty()) return false
        val key = canonicalKey(senderKeyHex) ?: return false
        return key in blockedKeys
    }

    // ------------------------------------------------------------------
    // Channels — a noise filter, on an unauthenticated name
    // ------------------------------------------------------------------

    /**
     * Canonical form of a channel sender name for matching: trimmed and
     * case-folded, so "Spammer" and "spammer " are one entry. Nothing
     * stronger is possible or promised.
     */
    fun canonicalName(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }

    /**
     * True when a channel message whose claimed sender name is [name]
     * should be hidden.
     *
     * Matching is exact after canonicalisation — deliberately not a
     * prefix or substring match. "spam" hiding "spammy-but-fine" would
     * make the filter's behaviour hard to predict, and a filter whose
     * effects surprise you is one you stop trusting.
     */
    fun isFilteredChannelName(name: String?, filteredNames: Set<String>): Boolean {
        if (filteredNames.isEmpty()) return false
        val canonical = canonicalName(name) ?: return false
        return canonical in filteredNames
    }

    const val MAX_NAME_LENGTH = 64

    /**
     * The sentence the UI has to show next to a channel filter. Kept
     * here so it can't drift out of sync with what the code does.
     */
    const val CHANNEL_FILTER_CAVEAT =
        "Channel messages carry a name, not a key — anyone on the channel can use any " +
            "name. This hides messages claiming that name; it cannot stop a person."
}
