package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.RadioPresets
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
class LinkImport(
    private val radio: Radio,
    private val channels: ChannelJoin,
    private val retune: RadioRetune,
) {

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

        // Settings-code fields, on the base type so Swift never has to
        // name the three-deep `Preview.Radio` (IOS_DECISIONS interop rules).
        /** True for a settings code, which retunes the radio. */
        open val isRadio: Boolean get() = false
        /** Red text to show above the confirm button. */
        open val warnings: List<String> get() = emptyList()
        open val regionHeadline: String? get() = null
        open val regionChoices: List<String> get() = emptyList()
        open val initialRegion: String? get() = null
        open val regionStartsTicked: Boolean get() = false

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

        /**
         * A settings code: retunes the radio. Never applied without the
         * person's confirmation, and the region only if they leave it
         * ticked ([regionChoices], [defaultRegion]).
         */
        data class Radio(
            val config: ShareUri.Decoded.RadioConfig,
            override val title: String,
            override val detail: String,
            /** Red text: unsigned, regulatory, and anything the code got wrong. */
            override val warnings: List<String>,
            override val regionHeadline: String?,
            override val regionChoices: List<String>,
            /** The choice selected first; meaningful only when [regionChoices] is non-empty. */
            override val initialRegion: String?,
            /** Whether "also set the region" starts ticked: only when the code names a default. */
            override val regionStartsTicked: Boolean,
        ) : Preview() {
            override val canApply: Boolean get() = true
            override val isRadio: Boolean get() = true
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
        is ShareUri.Decoded.RadioConfig -> radioPreview(d)
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

    private fun radioPreview(c: ShareUri.Decoded.RadioConfig): Preview.Radio {
        val warnings = buildList {
            add("Anyone can make one of these codes; nothing in it is signed. " + RadioPresets.REGULATORY_CAVEAT)
            c.rejectedRegionTree?.let {
                add(
                    "It also lists regions \"$it\", which isn't a list this app can use " +
                        "(up to ${ShareUri.MAX_REGION_TREE} valid names, each once, including the default), so it is ignored.",
                )
            }
            c.rejectedRegion?.let {
                add("It also names region \"$it\", which isn't a valid region name (lowercase letters, digits and dashes), so it won't be set.")
            }
        }
        val choices = c.regionChoices()
        return Preview.Radio(
            config = c,
            title = "Apply these radio settings?",
            detail = "${ShareUri.sanitizeName(c.name).ifBlank { "(unnamed mesh)" }}\n${c.summary()}\n\n" +
                "This retunes your radio. If these values are wrong you will not see errors — you will see an empty mesh.",
            warnings = warnings,
            regionHeadline = c.regionHeadline(),
            regionChoices = choices,
            initialRegion = c.region ?: choices.lastOrNull(),
            regionStartsTicked = c.region != null,
        )
    }

    /**
     * Apply a confirmed settings code; [pickedRegion] null = leave the
     * region alone. Any other preview is applied as [apply] would.
     */
    suspend fun applyRadio(preview: Preview, pickedRegion: String?): String = when (preview) {
        is Preview.Radio -> retune.applyScanned(preview.config, pickedRegion).message
        else -> apply(preview)
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
        // The region is a choice the confirmation makes; this path takes none.
        is Preview.Radio -> retune.applyScanned(preview.config, null).message
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
            RadioRetune.over(engine),
        )
    }
}
