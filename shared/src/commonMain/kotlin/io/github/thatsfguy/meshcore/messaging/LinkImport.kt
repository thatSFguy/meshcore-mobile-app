package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * A `meshcore://` link, shown before it is applied and applied only on
 * confirmation — the same two-step Android's ViewModel runs for scanned
 * codes and opened links, here as one tested class for iOS.
 *
 * Everything in a link is attacker-controlled: [preview] decides what to
 * SAY about it, including the one fact that matters most for a contact —
 * whether its name was signed by the key holder or merely typed by
 * whoever made the code. An advert link whose signature does not verify
 * is refused outright, never offered as an unsigned card.
 */
class LinkImport(private val radio: Radio, private val channels: ChannelJoin) {

    interface Radio {
        val selfKeyHex: String?
        /** The local name of a contact the radio already holds, or null. */
        fun heldContactName(keyHex: String): String?
        /** A verified advert as a signed card; null when it does not verify. */
        fun cardFromAdvert(blob: ByteArray): ShareUri.Decoded.Contact?
        suspend fun addCard(keyHex: String, name: String, type: Int): Boolean
    }

    sealed class Preview {
        abstract val title: String
        abstract val detail: String
        /** True when there is something to confirm. */
        abstract val canApply: Boolean

        data class Contact(
            val card: ShareUri.Decoded.Contact,
            override val title: String,
            override val detail: String,
        ) : Preview() {
            override val canApply: Boolean get() = true
        }

        data class Channel(
            val link: String,
            override val title: String,
            override val detail: String,
        ) : Preview() {
            override val canApply: Boolean get() = true
        }

        data class Unusable(override val title: String, override val detail: String) : Preview() {
            override val canApply: Boolean get() = false
        }
    }

    fun preview(link: String): Preview = when (val d = ShareUri.decode(link)) {
        is ShareUri.Decoded.Contact -> contactPreview(d)
        is ShareUri.Decoded.Advert -> radio.cardFromAdvert(d.blob)?.let(::contactPreview)
            ?: Preview.Unusable(
                "Not added",
                "This code's signature doesn't check out, so nothing in it can be trusted.",
            )
        is ShareUri.Decoded.ChannelShare -> {
            val scope = d.rawRegionScope.ifBlank { d.regionScope }
            Preview.Channel(
                link,
                "Join channel ${d.name.ifBlank { "(unnamed)" }}",
                "Whoever shared this can read everything on it, and so will you." +
                    if (scope.isBlank()) "" else " It asks for region #$scope, which iOS cannot apply yet.",
            )
        }
        is ShareUri.Decoded.RadioConfig -> Preview.Unusable(
            "Radio settings",
            "Settings codes retune the radio; iOS cannot apply them yet. Use the Android app.",
        )
        is ShareUri.Decoded.UnsupportedVersion -> Preview.Unusable(
            "Newer code",
            "This code needs a newer version of the app (format v${d.version}).",
        )
        ShareUri.Decoded.NotAContactCode -> Preview.Unusable(
            "Not a MeshCore link",
            "Expected a contact, channel or settings code.",
        )
        else -> Preview.Unusable("Unreadable link", "This link is damaged or too large.")
    }

    private fun contactPreview(card: ShareUri.Decoded.Contact): Preview {
        val key = card.pubKeyHex.lowercase()
        if (hexToBytesOrNull(key)?.size != Codes.PUB_KEY_SIZE) {
            return Preview.Unusable("Unreadable contact", "The key in this code is not a MeshCore key.")
        }
        if (key == radio.selfKeyHex?.lowercase()) {
            return Preview.Unusable("That's you", "This code is your own radio.")
        }
        val name = ShareUri.sanitizeName(card.name).ifBlank { key.take(12) }
        radio.heldContactName(key)?.let { held ->
            return Preview.Unusable(
                "Already a contact",
                if (held.equals(name, ignoreCase = true)) "You already have $held." else "You already have this node — you call it \"$held\".",
            )
        }
        val what = when (card.type) {
            Codes.ADV_TYPE_REPEATER -> "repeater"
            Codes.ADV_TYPE_ROOM -> "room server"
            Codes.ADV_TYPE_SENSOR -> "sensor"
            else -> "contact"
        }
        val trust = if (card.signed) {
            "The name was signed by whoever holds this key."
        } else {
            "The name is whatever the person who made this code typed — anyone can claim any name."
        }
        return Preview.Contact(
            card.copy(name = name, pubKeyHex = key),
            "Add $what $name",
            "Key ${key.take(16)}…. $trust",
        )
    }

    /** Apply a preview the person confirmed. Returns the sentence to show. */
    suspend fun apply(preview: Preview): String = when (preview) {
        is Preview.Contact -> {
            val ok = runCatching {
                radio.addCard(preview.card.pubKeyHex, preview.card.name, preview.card.type)
            }.getOrDefault(false)
            if (ok) "Added ${preview.card.name}" else "Couldn't add ${preview.card.name}: the radio didn't answer."
        }
        is Preview.Channel -> channels.joinShared(preview.link).message
        is Preview.Unusable -> preview.detail
    }

    companion object {
        fun over(engine: MeshCoreEngine, channels: ChannelJoin): LinkImport = LinkImport(
            object : Radio {
                override val selfKeyHex: String? get() = engine.selfInfo.value?.publicKeyHex
                override fun heldContactName(keyHex: String): String? =
                    engine.contacts.value[keyHex]?.let { it.name.ifBlank { keyHex.take(12) } }
                override fun cardFromAdvert(blob: ByteArray) = engine.contactCardFromAdvert(blob)
                override suspend fun addCard(keyHex: String, name: String, type: Int): Boolean {
                    val key = hexToBytesOrNull(keyHex) ?: return false
                    return engine.addContactFromCard(key, name, type)
                }
            },
            channels,
        )
    }
}
