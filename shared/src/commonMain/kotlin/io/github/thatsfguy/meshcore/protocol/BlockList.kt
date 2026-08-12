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
    // What a block is ABOUT — scope, not mechanism
    // ------------------------------------------------------------------

    /**
     * True when a message of [txtType] is the kind a block is for.
     *
     * A block suppresses **unsolicited** traffic. A CLI reply
     * ([Codes.TXT_TYPE_CLI_DATA]) is not unsolicited: it is the answer
     * to a command this app sent seconds earlier, by name, from the
     * console the user is looking at. Dropping it is not blocking
     * anyone, it is breaking your own tool.
     *
     * That is not hypothetical. Blocking a repeater used to swallow its
     * console replies before they became rows, with no error — and the
     * form-based settings screens kept working throughout, because they
     * await the engine event directly and never reach the repository.
     * One node, one command, two screens, two answers.
     */
    fun isBlockableMessage(txtType: Int): Boolean = txtType != Codes.TXT_TYPE_CLI_DATA

    /**
     * True when a node of advert type [advType] has anything a block can
     * act on — i.e. whether to offer the action at all.
     *
     * Repeaters and sensors do not send chat. Everything they say is
     * either a CLI reply (never blockable, above) or a binary response
     * that never passes through this check at all, so blocking one is a
     * button that appears to do something and does nothing. And it
     * cannot do the thing its name suggests: a repeater relays traffic
     * that carries the ORIGINAL sender's key, so nothing about a block
     * touches what passes through it. That is a property of the mesh,
     * not a gap in the implementation.
     *
     * Room servers keep it. A room's chat arrives as direct messages
     * from the server's key, so "stop showing me this room" is a real
     * want with a real effect.
     *
     * Unknown types default to blockable: a node we cannot classify may
     * well be sending chat, and failing open on an unknown talker is the
     * wrong way round for a safety control.
     */
    fun isBlockableNodeType(advType: Int): Boolean = when (advType) {
        Codes.ADV_TYPE_REPEATER, Codes.ADV_TYPE_SENSOR -> false
        else -> true
    }

    /** Why the action is absent, for anyone who goes looking for it. */
    const val NOT_BLOCKABLE_NOTE: String =
        "Blocking does not apply here: this node sends no messages of its own, and it " +
            "cannot be stopped from relaying — traffic through it carries the original " +
            "sender's key, not this node's."

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
