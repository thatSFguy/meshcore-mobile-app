package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.ShareUri

/**
 * A `meshcore://` link tapped somewhere outside the app — a text
 * message, an email, a chat in another app.
 *
 * The point is that someone can be handed a contact or a channel as a
 * link and just tap it, instead of copying it and finding "Paste a code".
 *
 * Anything that arrives this way is attacker-controlled: any installed
 * app can fire a VIEW intent at us, with no user involved. So a link is
 * only ever DECODED here and handed to the same confirmation dialogs a
 * scanned QR code gets. Nothing is added, joined or applied without the
 * user pressing a button that says what will happen.
 */
object IncomingLink {

    /**
     * The link to act on, or null when this is not one of ours: the wrong
     * scheme, blank, or longer than any code the decoder accepts (which
     * would be refused anyway, but should not be held in memory waiting
     * for a radio).
     */
    fun accept(raw: String?): String? {
        val link = raw?.trim() ?: return null
        if (link.length > ShareUri.MAX_URI_LENGTH) return null
        if (!link.startsWith(ShareUri.SCHEME, ignoreCase = true)) return null
        if (link.length == ShareUri.SCHEME.length) return null
        return link
    }
}

/**
 * Holds a tapped link until the radio is connected.
 *
 * Adding a contact or joining a channel writes to the RADIO, and the
 * message database only opens once one connects. The commonest way to
 * open a link is cold, from a text message, seconds before the radio has
 * reconnected. Before this existed, that link went to a decoder guarded
 * by "no radio → return", and vanished without a word.
 *
 * Only the newest link is kept: someone who taps two links before the
 * radio connects meant the second.
 */
class LinkInbox {

    sealed class Offer {
        /** Act on it now. */
        data class Open(val link: String) : Offer()

        /** Kept until [LinkInbox.onReady]; say so. */
        data object Held : Offer()

        /** Not a MeshCore link. */
        data object Ignored : Offer()
    }

    private var held: String? = null

    fun offer(raw: String?, ready: Boolean): Offer {
        val link = IncomingLink.accept(raw) ?: return Offer.Ignored
        if (ready) {
            held = null
            return Offer.Open(link)
        }
        held = link
        return Offer.Held
    }

    /** The radio is ready: the held link, at most once. */
    fun onReady(): String? = held.also { held = null }
}
