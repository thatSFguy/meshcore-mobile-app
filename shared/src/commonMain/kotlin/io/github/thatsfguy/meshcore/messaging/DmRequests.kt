package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.presentation.DmRequest
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * "Message privately" from a channel — the request / accept handshake
 * over a channel post, carrying out the shared [DmRequest] rules. What
 * Android spread across its ViewModel, repository and service, as one
 * class for iOS (2026-11-18).
 *
 * Why a handshake at all: each radio decrypts direct messages only from
 * its own contacts, so BOTH sides must add the other. The requester posts
 * its full key; the person asked adds it and answers with theirs; the
 * requester's app adds that — but only for a request it actually made
 * ([DmRequest.Outstanding]), so a stranger can't push a contact onto
 * anyone by posting an "accept".
 *
 * State is two small strings the platform persists ([outstandingRaw],
 * [handledRaw]); [onStateChanged] says when to save them.
 */
class DmRequests(
    private val radio: Radio,
    /** Epoch seconds; a parameter so tests can fix it. */
    private val nowSec: () -> Long = { kotlinx.datetime.Clock.System.now().epochSeconds },
) {

    interface Radio {
        val myKeyHex: String?
        val myName: String?
        /** Whether [keyHex] is already on the radio — then it is not added again. */
        fun hasContact(keyHex: String): Boolean
        suspend fun addContact(keyHex: String, name: String): Boolean
        suspend fun sendChannel(index: Int, text: String): Boolean
    }

    private var outstanding: DmRequest.Outstanding = DmRequest.Outstanding.NONE
    private var handled: DmRequest.Handled = DmRequest.Handled.NONE

    /** Set when [handledRaw] converted old entries, which must be saved once. */
    private var unsavedMigration = false

    /**
     * Called after any state change; save [outstandingRaw] and [handledRaw].
     *
     * The platform loads state first and attaches this after, so a
     * conversion done while loading ([handledRaw]) is saved the moment it
     * is attached. Unsaved, the old entries would be re-stamped at every
     * launch, and a request received between two launches would read as
     * answered at the second.
     */
    var onStateChanged: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null && unsavedMigration) {
                unsavedMigration = false
                value()
            }
        }

    /** Requests we made and still expect an answer to: "name=epochSeconds" lines. */
    var outstandingRaw: String
        get() = outstanding.toMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        set(value) {
            outstanding = DmRequest.Outstanding(
                value.lines().mapNotNull { line ->
                    val name = line.substringBeforeLast('=', "")
                    val at = line.substringAfterLast('=', "").toLongOrNull()
                    if (name.isNotEmpty() && at != null) name to at else null
                }.toMap(),
            )
        }

    /** Requests to us that have been answered, and when: "key=epochSeconds" lines ([DmRequest.Handled]). */
    var handledRaw: String
        get() = handled.encode().joinToString("\n")
        set(value) {
            val lines = value.lines()
            handled = DmRequest.Handled.decode(lines, legacySec = nowSec())
            if (DmRequest.Handled.hasLegacy(lines)) {
                unsavedMigration = true
                onStateChanged?.let { unsavedMigration = false; it() }
            }
        }

    private fun changed() = onStateChanged?.invoke()

    /** Ask [targetName] on channel [index]; the sentence to show. */
    suspend fun ask(index: Int, targetName: String, nowSec: Long): String {
        val me = radio.myKeyHex ?: return "Not connected to a radio."
        val text = DmRequest.request(targetName, me)
        if (text == null || !DmRequest.fits(radio.myName.orEmpty(), text)) return "Can't send a request to that name"
        outstanding = outstanding.asked(targetName, nowSec)
        changed()
        val sent = runCatching { radio.sendChannel(index, text) }.getOrDefault(false)
        return if (sent) "Asked ${targetName.trim()}. You can message them once they accept." else "The radio didn't send the request."
    }

    /**
     * Accept a request addressed to us: add the requester to the radio,
     * THEN answer on the same channel so their app adds us. If the add
     * fails nothing is posted — an accept without the contact would tell
     * them they can write to someone who can't read them.
     *
     * Someone already on the radio is NOT added again: the add writes a
     * flood route, a zero timestamp and the channel's display name, which
     * would throw away a route and an advertised name the radio learned.
     * That is the case of a repeated request after our first accept was
     * lost — they still need the answer, not a fresh contact.
     */
    suspend fun accept(index: Int, requesterName: String, requesterKeyHex: String, nowSec: Long): String {
        val me = radio.myKeyHex ?: return "Not connected to a radio."
        val reply = DmRequest.accept(requesterName, requesterKeyHex, me)
        if (reply == null || !DmRequest.fits(radio.myName.orEmpty(), reply)) return "Can't answer a request from that name"
        val name = requesterName.trim()
        val known = radio.hasContact(requesterKeyHex)
        if (!known) {
            val added = runCatching { radio.addContact(requesterKeyHex, name) }.getOrDefault(false)
            if (!added) return "Couldn't add $name — is the radio's contact list full?"
        }
        runCatching { radio.sendChannel(index, reply) }
        markHandled(requesterKeyHex, nowSec)
        return if (known) "Answered $name again. You can message each other directly."
        else "Added $name. You can message each other directly."
    }

    fun decline(requesterKeyHex: String, nowSec: Long) = markHandled(requesterKeyHex, nowSec)

    /**
     * "My code": our contact card as a link, for a QR or a message
     * (Android's selfShareUri). Showing it is an invitation — whoever adds
     * it can answer on a shared channel, and that answer should add them
     * here — so it opens a shared-link window, as Android does.
     */
    fun myCode(nowSec: Long): String? {
        val key = radio.myKeyHex?.takeIf { it.isNotBlank() } ?: return null
        outstanding = outstanding.sharedLink(nowSec)
        changed()
        return io.github.thatsfguy.meshcore.protocol.ShareUri.encodeContact(
            name = radio.myName.orEmpty(),
            pubKeyHex = key,
            type = Codes.ADV_TYPE_CHAT,
        )
    }

    private fun markHandled(keyHex: String, nowSec: Long) {
        handled = handled.answered(keyHex, nowSec)
        changed()
    }

    /**
     * A channel post arrived. If it answers a request we made, add the
     * accepter. Returns a sentence when something was done, else null.
     */
    suspend fun onChannelPost(senderName: String?, text: String, nowSec: Long): String? {
        val parsed = DmRequest.parse(text) as? DmRequest.Parsed.Accept ?: return null
        val me = radio.myKeyHex ?: return null
        val action = DmRequest.decide(parsed, senderName, null, me, outstanding, nowSec)
            as? DmRequest.Action.TheyAccepted ?: return null
        // Already a contact (we asked again after a lost answer, or added
        // them by code): nothing to add, and re-adding would reset them.
        val added = radio.hasContact(action.accepterKeyHex) ||
            runCatching { radio.addContact(action.accepterKeyHex, action.accepterName) }.getOrDefault(false)
        if (!added) return "${action.accepterName} accepted, but the radio couldn't add them."
        outstanding = outstanding.answered(action.accepterName, nowSec)
        changed()
        return "${action.accepterName} accepted. You can message each other directly."
    }

    // --- How a request post is shown (Swift-friendly) -------------------

    /** The line a request / accept post shows instead of its wire text, or null for an ordinary post. */
    fun line(text: String, senderName: String?, outgoing: Boolean): String? {
        val parsed = DmRequest.parse(text) ?: return null
        return DmRequest.describe(parsed, senderName, outgoing, radio.myName, radio.myKeyHex.orEmpty())
    }

    /**
     * The requester's key when this post asks US and is still unanswered
     * — the card then offers Accept / Decline. Null otherwise. A request
     * received after we last answered that key is unanswered: see
     * [DmRequest.Handled]. [receivedAtMs] is when WE stored the post.
     */
    fun pendingAsker(text: String, senderName: String?, outgoing: Boolean, receivedAtMs: Long): String? {
        if (outgoing) return null
        val parsed = DmRequest.parse(text) ?: return null
        val action = DmRequest.decide(
            parsed, senderName, radio.myName, radio.myKeyHex ?: return null, DmRequest.Outstanding.NONE, 0,
        ) as? DmRequest.Action.AskedMe ?: return null
        return action.requesterKeyHex.takeIf { handled.isOpen(it, receivedAtMs / 1000) }
    }

    /** Whether a channel post's sender can be asked (a name we can address, and not already a contact by it). */
    fun canAsk(senderName: String?, contactNames: Collection<String>): Boolean {
        val name = senderName?.trim().orEmpty()
        if (DmRequest.request(name, radio.myKeyHex ?: return false) == null) return false
        return contactNames.count { it.trim().equals(name, ignoreCase = true) } != 1
    }

    companion object {
        const val CONFIRM_TEXT =
            "This posts a short request on this channel. Everyone here will see that you asked. " +
                "If they accept, you'll both be able to send direct messages."

        /**
         * Over the engine for identity and contacts, and over [session] for
         * posting — so a request or accept is an ordinary row in the
         * channel thread, shown as its line, like any post of ours.
         */
        fun over(engine: MeshCoreEngine, session: MessagingSession): DmRequests = DmRequests(object : Radio {
            override val myKeyHex: String? get() = engine.selfInfo.value?.publicKeyHex
            override val myName: String? get() = engine.selfInfo.value?.name
            override fun hasContact(keyHex: String): Boolean =
                engine.contacts.value.containsKey(keyHex.trim().lowercase())
            override suspend fun addContact(keyHex: String, name: String): Boolean {
                val key = hexToBytesOrNull(keyHex) ?: return false
                return engine.addContactFromCard(key, name, Codes.ADV_TYPE_CHAT)
            }
            override suspend fun sendChannel(index: Int, text: String): Boolean = session.sendChannel(index, text) != null
        })
    }
}
