package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.isHexDigits

/**
 * Asking someone on a channel for a private conversation — the "message
 * request" of a closed group, carried over the channel itself.
 *
 * ## Why it rides the channel and not a direct message
 *
 * A radio decrypts a direct message only when the sender is already in its
 * contacts (`BaseChatMesh.cpp:203`, `searchPeersByHash`). With auto-add
 * off, which is the point of a closed group, a first DM from someone new is
 * dropped by the radio before any app sees it. A Signal-style "accept this
 * message?" therefore has nothing to show. So the request goes over the
 * channel the two people share, and carries the requester's public key:
 *
 * ```
 * Alice: DM request for @[Bob] mch:r:<Alice's 64-hex key>
 * Bob:   DM accepted @[Alice] mch:a:<first 8 hex of Alice's key>:<Bob's 64-hex key>
 * ```
 *
 * Accepting adds Alice on Bob's radio and posts the reply; Alice's app sees
 * a reply to a request it actually made and adds Bob. After that both
 * radios hold each other and direct messages work.
 *
 * ## What it is not
 *
 * Not secret: everyone on the channel sees who asked whom. That is the
 * stated trade (the goal is keeping noise out, not hiding). Not
 * authenticated by name: channel names are display text anyone can type
 * (MESHCORE_PROTOCOL §9), so the request is addressed to a NAME, and the
 * reply to the requester's KEY. The confirmation shows the key so two
 * people who care can compare it.
 *
 * The human part uses the `@[name]` mention convention, so someone running
 * another app sees a mention and a readable line, not just a hex blob.
 *
 * ## Size
 *
 * A channel message is capped at [Codes.MAX_TEXT_PAYLOAD_BYTES] (160)
 * INCLUDING the firmware's `"<sender>: "` prefix, and anything longer is
 * cut off silently (`BaseChatMesh::sendGroupMessage`, `MAX_TEXT_LEN`). A
 * key cut short is worse than no key. Both forms fit with 31-byte names on
 * both ends, and [fits] is checked before anything is sent.
 */
object DmRequest {

    sealed class Parsed {
        /** [requesterKeyHex] asked whoever is called [targetName]. */
        data class Request(val targetName: String, val requesterKeyHex: String) : Parsed()

        /**
         * The holder of [accepterKeyHex] accepted a request from the key
         * starting [requesterPrefixHex]; [requesterName] is display text.
         */
        data class Accept(
            val requesterName: String,
            val requesterPrefixHex: String,
            val accepterKeyHex: String,
        ) : Parsed()
    }

    private const val REQUEST_HEAD = "DM request for "
    private const val ACCEPT_HEAD = "DM accepted "
    private const val REQUEST_TAG = "mch:r:"
    private const val ACCEPT_TAG = "mch:a:"
    const val PREFIX_HEX = 8
    private const val KEY_HEX = 64

    /** Longest name, in UTF-8 bytes, a node can carry (32 with its NUL). */
    const val MAX_NAME_BYTES = Codes.MAX_NAME_SIZE - 1

    private val REQUEST_RE =
        Regex("""^DM request for @\[([^\[\]]+)] mch:r:([0-9a-f]{64})$""")
    private val ACCEPT_RE =
        Regex("""^DM accepted @\[([^\[\]]+)] mch:a:([0-9a-f]{8}):([0-9a-f]{64})$""")

    /**
     * The request text, or null when it cannot be addressed: a name that
     * cannot be written as a mention, longer than a node name can be, or a
     * key that is not a full key.
     */
    fun request(targetName: String, myKeyHex: String): String? {
        val name = targetName.trim()
        val key = canonicalKey(myKeyHex) ?: return null
        if (!addressable(name)) return null
        return "$REQUEST_HEAD${Mentions.wire(name)} $REQUEST_TAG$key"
    }

    /** The reply text, or null under the same rules as [request]. */
    fun accept(requesterName: String, requesterKeyHex: String, myKeyHex: String): String? {
        val name = requesterName.trim()
        val requester = canonicalKey(requesterKeyHex) ?: return null
        val me = canonicalKey(myKeyHex) ?: return null
        if (!addressable(name)) return null
        return "$ACCEPT_HEAD${Mentions.wire(name)} $ACCEPT_TAG${requester.take(PREFIX_HEX)}:$me"
    }

    /** Read a channel message body; null for anything that is not one of these. */
    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.length > Codes.MAX_TEXT_PAYLOAD_BYTES) return null
        REQUEST_RE.matchEntire(t)?.let { m ->
            return Parsed.Request(m.groupValues[1], m.groupValues[2])
        }
        ACCEPT_RE.matchEntire(t)?.let { m ->
            return Parsed.Accept(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return null
    }

    /** Whether [text] sent by [senderName] arrives whole. */
    fun fits(senderName: String, text: String): Boolean =
        "$senderName: $text".encodeToByteArray().size <= Codes.MAX_TEXT_PAYLOAD_BYTES

    private fun addressable(name: String): Boolean =
        Mentions.canMention(name) && name.encodeToByteArray().size <= MAX_NAME_BYTES

    private fun canonicalKey(raw: String): String? =
        raw.trim().lowercase().takeIf { it.length == KEY_HEX && isHexDigits(it) }

    private fun canonicalName(name: String): String = name.trim().lowercase()

    // ------------------------------------------------------------------
    // What to do with one arriving on a channel
    // ------------------------------------------------------------------

    sealed class Action {
        /** Someone asked us. Show Accept / Decline. */
        data class AskedMe(val requesterName: String, val requesterKeyHex: String) : Action()

        /**
         * Our own outstanding request was accepted: add [accepterKeyHex]
         * as [accepterName] and tell the user.
         */
        data class TheyAccepted(val accepterName: String, val accepterKeyHex: String) : Action()

        /** Not for us, or not something we asked for. Show it as a line. */
        data object None : Action()
    }

    /**
     * Decide what a request or reply means for this radio.
     *
     * A reply is acted on ONLY when we have an outstanding request it
     * answers: an "accepted" nobody asked for must not add anyone to our
     * contacts, or anyone on the channel could add themselves.
     */
    fun decide(
        parsed: Parsed,
        senderName: String?,
        myName: String?,
        myKeyHex: String,
        outstanding: Outstanding,
        nowSec: Long,
    ): Action {
        val me = canonicalKey(myKeyHex) ?: return Action.None
        val sender = senderName?.trim().orEmpty()
        return when (parsed) {
            is Parsed.Request -> {
                if (parsed.requesterKeyHex == me) return Action.None // our own, echoed
                val mine = myName?.let(::canonicalName) ?: return Action.None
                if (canonicalName(parsed.targetName) != mine) return Action.None
                Action.AskedMe(sender, parsed.requesterKeyHex)
            }
            is Parsed.Accept -> {
                if (parsed.accepterKeyHex == me) return Action.None // our own, echoed
                if (!me.startsWith(parsed.requesterPrefixHex)) return Action.None
                if (!outstanding.answers(sender, nowSec)) return Action.None
                Action.TheyAccepted(sender, parsed.accepterKeyHex)
            }
        }
    }

    /**
     * Why a request does or does not offer Accept / Decline, for the
     * diagnostics log. The thread's LINE needs only the name to match;
     * the BUTTONS also need our key and an unanswered request, so a line
     * reading "wants to message you" with no buttons is one of the later
     * reasons here. Nothing secret: names and an 8-hex key prefix.
     */
    fun explain(
        parsed: Parsed.Request,
        senderName: String?,
        myName: String?,
        myKeyHex: String,
        handled: Handled,
        postReceivedSec: Long,
    ): String {
        val from = "from ${senderName?.trim().orEmpty().ifEmpty { "?" }} " +
            "(key ${parsed.requesterKeyHex.take(PREFIX_HEX)}) to ${parsed.targetName}"
        val me = canonicalKey(myKeyHex) ?: return "$from: no buttons — our radio's key is not known yet"
        if (parsed.requesterKeyHex == me) return "$from: no buttons — it carries our own key"
        val mine = myName?.let(::canonicalName) ?: return "$from: no buttons — our radio's name is not known yet"
        if (canonicalName(parsed.targetName) != mine) return "$from: not for us"
        val at = handled.answeredAt(parsed.requesterKeyHex)
        if (at != null && postReceivedSec <= at) {
            return "$from: no buttons — that key was answered at $at, and this post arrived at $postReceivedSec"
        }
        return "$from: Accept / Decline offered" + (at?.let { " (answered before, at $it; this is a new request)" } ?: "")
    }

    /**
     * The line a thread shows in place of the raw text, so a request reads
     * as what it is rather than as a hex blob.
     */
    fun describe(
        parsed: Parsed,
        senderName: String?,
        outgoing: Boolean,
        myName: String?,
        myKeyHex: String,
    ): String {
        val sender = senderName?.trim()?.ifEmpty { null } ?: "Someone"
        val me = canonicalKey(myKeyHex)
        return when (parsed) {
            is Parsed.Request -> when {
                outgoing -> "You asked ${parsed.targetName} to message privately."
                myName != null && canonicalName(parsed.targetName) == canonicalName(myName) ->
                    "$sender wants to message you privately."
                else -> "$sender asked ${parsed.targetName} to message privately."
            }
            is Parsed.Accept -> when {
                outgoing -> "You accepted ${parsed.requesterName}'s request to message privately."
                me != null && me.startsWith(parsed.requesterPrefixHex) ->
                    "$sender accepted your request. You can message each other directly."
                else -> "$sender accepted ${parsed.requesterName}'s request to message privately."
            }
        }
    }

    /**
     * Requests this app has made and not yet seen answered.
     *
     * Keyed by the canonical name the request was addressed to, or [ANYONE]
     * for a contact link shared outside the app (we cannot know who will
     * tap it). Entries lapse after [LIFETIME_SEC]: an accept arriving weeks
     * later is more likely a stranger than the person we asked.
     */
    class Outstanding(private val entries: Map<String, Long>) {

        fun answers(senderName: String, nowSec: Long): Boolean {
            val name = canonicalName(senderName)
            return live(nowSec).keys.any { it == ANYONE || (name.isNotEmpty() && it == name) }
        }

        fun live(nowSec: Long): Map<String, Long> =
            entries.filterValues { created -> nowSec - created in 0..LIFETIME_SEC }

        /** After asking [targetName]. */
        fun asked(targetName: String, nowSec: Long): Outstanding =
            Outstanding(live(nowSec) + (canonicalName(targetName) to nowSec))

        /** After sharing our link with someone unknown. */
        fun sharedLink(nowSec: Long): Outstanding = Outstanding(live(nowSec) + (ANYONE to nowSec))

        /** After [senderName] answered: their entry is done; a shared link stays open. */
        fun answered(senderName: String, nowSec: Long): Outstanding =
            Outstanding(live(nowSec) - canonicalName(senderName))

        fun toMap(): Map<String, Long> = entries

        companion object {
            const val ANYONE = "*"
            const val LIFETIME_SEC = 7L * 24 * 3600
            val NONE = Outstanding(emptyMap())
        }
    }

    /**
     * Requests to us that have been answered, by requester key → WHEN
     * (our clock, epoch seconds).
     *
     * The time is the point. This used to be a set of keys, so once a
     * requester had been answered, nothing they posted could offer the
     * buttons again — and an accept is a channel post with no ACK. If it
     * was lost, the requester's radio still lacked us (our direct messages
     * to them were dropped), their app still said "waiting", and asking
     * again did nothing on our side: the new request read as already
     * answered. A request RECEIVED after we answered is a new question,
     * so it opens again; the post we answered, and any before it, do not.
     *
     * Times are ours on both sides — when the post was stored and when we
     * answered — never the sender's unauthenticated clock.
     */
    class Handled(private val entries: Map<String, Long>) {

        /** Whether a request from [requesterKeyHex] received at [postReceivedSec] still wants an answer. */
        fun isOpen(requesterKeyHex: String, postReceivedSec: Long): Boolean {
            val at = entries[requesterKeyHex.trim().lowercase()] ?: return true
            return postReceivedSec > at
        }

        /** When [requesterKeyHex] was last answered, or null if never. */
        fun answeredAt(requesterKeyHex: String): Long? = entries[requesterKeyHex.trim().lowercase()]

        /** After answering [requesterKeyHex] (accepted or declined) at [nowSec]. */
        fun answered(requesterKeyHex: String, nowSec: Long): Handled {
            val next = entries + (requesterKeyHex.trim().lowercase() to nowSec)
            return Handled(
                if (next.size <= MAX_ENTRIES) next
                else next.entries.sortedByDescending { it.value }.take(MAX_ENTRIES).associate { it.toPair() },
            )
        }

        /** One "key=seconds" string per entry, sorted, for storage. */
        fun encode(): List<String> = entries.entries.map { "${it.key}=${it.value}" }.sorted()

        override fun equals(other: Any?): Boolean = other is Handled && other.entries == entries
        override fun hashCode(): Int = entries.hashCode()

        companion object {
            const val MAX_ENTRIES = 256
            val NONE = Handled(emptyMap())

            /**
             * Read [encode]'s strings back; junk is dropped.
             *
             * A bare key is how this was stored before it carried a time
             * (up to Android 0.11.3 / iOS 0.2.0). It is read as answered
             * at [legacySec], which the caller sets to NOW and then saves
             * ([hasLegacy]) — so it is converted exactly once. Requests
             * already in the thread stay answered; one that arrives after
             * the update asks again.
             *
             * 0.11.4 read them as answered FOREVER instead, on the theory
             * that this was what they had meant. It locked out exactly the
             * case the time was added for: anyone who had answered a node
             * once could never see its buttons again (reported 2026-11-27,
             * a request from an EdgeRelay node with no Accept).
             */
            fun decode(stored: Collection<String>, legacySec: Long): Handled = Handled(
                stored.mapNotNull { raw ->
                    val line = raw.trim().lowercase()
                    val key = line.substringBefore('=')
                    if (key.length != KEY_HEX || !isHexDigits(key)) return@mapNotNull null
                    val at = if ('=' in line) line.substringAfter('=').toLongOrNull() ?: return@mapNotNull null
                    else legacySec
                    key to at
                }.toMap(),
            )

            /** Whether [stored] holds entries in the old, time-less form — save after [decode]. */
            fun hasLegacy(stored: Collection<String>): Boolean =
                stored.any { it.isNotBlank() && '=' !in it }
        }
    }
}
