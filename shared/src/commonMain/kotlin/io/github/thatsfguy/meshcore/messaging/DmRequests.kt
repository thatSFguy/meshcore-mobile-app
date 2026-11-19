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
class DmRequests(private val radio: Radio) {

    interface Radio {
        val myKeyHex: String?
        val myName: String?
        suspend fun addContact(keyHex: String, name: String): Boolean
        suspend fun sendChannel(index: Int, text: String): Boolean
    }

    private var outstanding: DmRequest.Outstanding = DmRequest.Outstanding.NONE
    private var handled: Set<String> = emptySet()

    /** Called after any state change; save [outstandingRaw] and [handledRaw]. */
    var onStateChanged: (() -> Unit)? = null

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

    /** Requester keys whose request to us has been answered (accepted or declined). */
    var handledRaw: String
        get() = handled.sorted().joinToString("\n")
        set(value) {
            handled = value.lines().map { it.trim().lowercase() }.filter { it.length == 64 }.toSet()
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
     */
    suspend fun accept(index: Int, requesterName: String, requesterKeyHex: String): String {
        val me = radio.myKeyHex ?: return "Not connected to a radio."
        val reply = DmRequest.accept(requesterName, requesterKeyHex, me)
        if (reply == null || !DmRequest.fits(radio.myName.orEmpty(), reply)) return "Can't answer a request from that name"
        val name = requesterName.trim()
        val added = runCatching { radio.addContact(requesterKeyHex, name) }.getOrDefault(false)
        if (!added) return "Couldn't add $name — is the radio's contact list full?"
        runCatching { radio.sendChannel(index, reply) }
        markHandled(requesterKeyHex)
        return "Added $name. You can message each other directly."
    }

    fun decline(requesterKeyHex: String) = markHandled(requesterKeyHex)

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

    private fun markHandled(keyHex: String) {
        handled = handled + keyHex.lowercase()
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
        val added = runCatching { radio.addContact(action.accepterKeyHex, action.accepterName) }.getOrDefault(false)
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
     * — the card then offers Accept / Decline. Null otherwise.
     */
    fun pendingAsker(text: String, senderName: String?, outgoing: Boolean): String? {
        if (outgoing) return null
        val parsed = DmRequest.parse(text) ?: return null
        val action = DmRequest.decide(
            parsed, senderName, radio.myName, radio.myKeyHex ?: return null, DmRequest.Outstanding.NONE, 0,
        ) as? DmRequest.Action.AskedMe ?: return null
        return action.requesterKeyHex.takeIf { it !in handled }
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
            override suspend fun addContact(keyHex: String, name: String): Boolean {
                val key = hexToBytesOrNull(keyHex) ?: return false
                return engine.addContactFromCard(key, name, Codes.ADV_TYPE_CHAT)
            }
            override suspend fun sendChannel(index: Int, text: String): Boolean = session.sendChannel(index, text) != null
        })
    }
}
