package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex

/**
 * The `meshcore://` contact-share encoding used by QR codes and pasted
 * links.
 *
 * Two forms exist in the wild and both are accepted on scan:
 *
 *  1. **Contact card** — `meshcore://contact/add?name=…&public_key=…&type=…`,
 *     what the mainstream MeshCore app emits. This is what we *emit* too,
 *     so a code from this app scans in theirs and vice versa.
 *  2. **Advert blob** — `meshcore://<hex>`, used by MeshCore Open and by
 *     this app's earlier releases. Decoded for backward compatibility.
 *
 * The security difference between them is not cosmetic. An advert blob
 * carries the node's Ed25519 signature, so the radio can verify it on
 * import. A contact card carries **only a name, a public key and a type
 * byte — nothing is signed**, and the name is whatever the sender typed.
 * The trust in form 1 comes entirely from the out-of-band channel the QR
 * travelled over (someone's screen, in person). Callers must keep the
 * two apart and must not present a card-imported contact as verified;
 * [Decoded] makes the distinction impossible to ignore.
 *
 * Decoding runs on scanned QR data — wholly attacker-controlled input —
 * so every failure returns a typed error rather than throwing.
 */
object ShareUri {

    const val SCHEME = "meshcore://"

    /** Path of the contact-card form, after the scheme. */
    const val CONTACT_PATH = "contact/add"

    /** Path of the channel-share form. */
    const val CHANNEL_PATH = "channel/add"

    /**
     * Path of the mesh-settings form — the radio parameters an area has
     * agreed on, so joining is a scan rather than four numbers typed
     * correctly.
     *
     * NEW HERE. Nothing else in the ecosystem emits this: the firmware
     * has no QR at all, the mainstream app ships only `contact/add` and
     * `channel/add`, and MeshCore Open's "community" code is a JSON blob
     * carrying a channel secret, not radio settings. So this is our
     * format, and scanners elsewhere will not understand it yet.
     *
     * Deliberately NOT carried:
     *
     *  - **TX power.** Every other field is "match this or you are not
     *    on the mesh". Power is not — it is the local legal limit and
     *    what the hardware can do. Shipping it in a code means one
     *    person's jurisdiction propagates to everyone who scans.
     *  - **Channel keys.** Adding a PSK turns a config code into a
     *    secret: it would need keystore handling, log redaction, and a
     *    warning that photographing it gives away the channel forever.
     *    `channel/add` already exists for that, with those protections.
     */
    const val RADIO_PATH = "radio/set"

    /** Format version, so a later field can be added without ambiguity. */
    const val RADIO_VERSION = 1

    /**
     * Upper bound on the whole URI. A contact card is ~150 bytes and an
     * advert blob ~100 bytes of payload; the cap is generous but keeps a
     * hostile QR from making us allocate before any parse happens.
     */
    const val MAX_URI_LENGTH = 4096

    /** Shortest blob the radio will accept back as a contact import. */
    const val MIN_BLOB_BYTES = 32

    /** Public keys are Ed25519 — always exactly 32 bytes. */
    const val PUB_KEY_BYTES = 32

    /** Channel pre-shared keys are 16 bytes. */
    const val CHANNEL_PSK_BYTES = 16

    /** Firmware stores the name as a 32-byte C string, so 31 usable. */
    const val MAX_NAME_BYTES = 31

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * Build the contact card other MeshCore apps expect. [name] is
     * truncated to what the firmware can store, and [pubKeyHex] is
     * emitted upper-case to match the mainstream app byte for byte.
     */
    fun encodeContact(name: String, pubKeyHex: String, type: Int): String =
        SCHEME + CONTACT_PATH +
            "?name=" + percentEncode(truncateToBytes(name, MAX_NAME_BYTES)) +
            "&public_key=" + pubKeyHex.trim().uppercase() +
            "&type=" + type

    /** Legacy signed-advert form, still emitted by MeshCore Open. */
    fun encodeAdvert(blob: ByteArray): String = SCHEME + blob.toHex()

    /**
     * Build a channel share link.
     *
     * SECURITY: [pskHex] IS the channel. Anyone who scans this can read
     * every message on it — including ones sent before they scanned,
     * since the key is static and the cipher has no forward secrecy.
     * There is no revoking it short of changing the key everywhere. UI
     * that produces this must say so before it puts the code on screen.
     */
    fun encodeChannel(name: String, pskHex: String): String =
        SCHEME + CHANNEL_PATH +
            "?name=" + percentEncode(truncateToBytes(name, MAX_NAME_BYTES)) +
            "&channel_secret=" + pskHex.trim().uppercase()

    /**
     * Build a mesh-settings code. Units are the ones a person reads:
     * MHz and kHz, matching what both settings screens now show.
     *
     * [region] is the flood-scope name and is optional — blank means
     * global. It is a ROUTING setting, not a radio one: getting the
     * radio fields wrong makes a node deaf, getting the region wrong
     * leaves it audible but unable to propagate.
     */
    fun encodeRadio(
        name: String,
        frequencyKhz: Long,
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
        pathHashMode: Int,
        region: String? = null,
    ): String = buildString {
        append(SCHEME).append(RADIO_PATH)
        append("?v=").append(RADIO_VERSION)
        append("&name=").append(percentEncode(truncateToBytes(name, MAX_NAME_BYTES)))
        append("&freq=").append(RadioUnits.khzToMhzText(frequencyKhz))
        append("&bw=").append(RadioUnits.hzToKhzText(bandwidthHz))
        append("&sf=").append(spreadingFactor)
        append("&cr=").append(codingRate)
        append("&hash=").append(pathHashMode)
        region?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("&region=").append(percentEncode(truncateToBytes(it, MAX_NAME_BYTES)))
        }
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    sealed interface Decoded {
        /**
         * An unsigned contact card. Nothing here is authenticated: treat
         * [name] as display text supplied by whoever made the QR, and
         * [pubKeyHex] as the only field that identifies anyone.
         */
        data class Contact(
            val name: String,
            val pubKeyHex: String,
            val type: Int,
        ) : Decoded

        /**
         * A shared channel key. Nothing here is authenticated either —
         * scanning one means trusting whoever showed it to you, and it
         * grants read access to everything on that channel.
         */
        data class ChannelShare(val name: String, val pskHex: String) : Decoded

        /**
         * Radio parameters for an area, off a QR nobody vouched for.
         *
         * NOTHING HERE IS AUTHENTICATED. A code that retunes a radio is
         * a config-injection vector, and a nastier one than a contact
         * card: the worst a bad contact does is add a row, whereas these
         * four values decide whether the node is on a mesh at all — and
         * which frequency it transmits on, which is a legal question
         * wherever the scanner happens to be standing.
         *
         * So this type carries data, never an action. It must be shown
         * and confirmed before anything is applied, with the same
         * regulatory caveat the preset sheet uses. Ranges are checked
         * here so an out-of-band value cannot reach a radio at all, but
         * "in range" is not "correct for you".
         */
        data class RadioConfig(
            val name: String,
            /** kHz, the unit CMD_SET_RADIO_PARAMS wants. */
            val frequencyKhz: Long,
            /** Hz — the wire really is asymmetric. */
            val bandwidthHz: Long,
            val spreadingFactor: Int,
            val codingRate: Int,
            val pathHashMode: Int,
            /** Flood scope; null or blank means global. */
            val region: String?,
        ) : Decoded {
            /** MHz · kHz · SF · CR, for a confirmation dialog. */
            fun summary(): String = buildString {
                append(RadioUnits.khzToMhzText(frequencyKhz)).append(" MHz · ")
                append(RadioUnits.hzToKhzText(bandwidthHz)).append("kHz · ")
                append("SF").append(spreadingFactor).append(" · ")
                append("CR4/").append(codingRate).append(" · ")
                append(PathHashMode.bytesFor(pathHashMode)).append("B hash")
                region?.takeIf { it.isNotBlank() }?.let { append(" · region ").append(it) }
            }
        }

        /**
         * A settings code from a newer app than this one. Distinct from
         * [Malformed] on purpose: the code is fine, we are old. Applying
         * a partial understanding of it could half-tune a radio.
         */
        data class UnsupportedVersion(val version: Int) : Decoded

        /** A signed advert blob; the radio verifies it on import. */
        data class Advert(val blob: ByteArray) : Decoded {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Advert && blob.contentEquals(other.blob))

            override fun hashCode(): Int = blob.contentHashCode()
        }

        /** Not a contact code at all (wrong scheme, or plain text). */
        data object NotAContactCode : Decoded

        /** Correct scheme but oversized — rejected before decoding. */
        data object TooLarge : Decoded

        /** Correct scheme, unusable payload. */
        data object Malformed : Decoded
    }

    fun decode(text: String): Decoded {
        val trimmed = text.trim()
        // Case-insensitive: some generators upper-case the scheme.
        if (!trimmed.lowercase().startsWith(SCHEME)) return Decoded.NotAContactCode
        if (trimmed.length > MAX_URI_LENGTH) return Decoded.TooLarge
        val body = trimmed.substring(SCHEME.length)
        val path = body.substringBefore('?').lowercase()
        return when (path) {
            CONTACT_PATH -> decodeContactCard(body.substringAfter('?', ""))
            CHANNEL_PATH -> decodeChannelShare(body.substringAfter('?', ""))
            RADIO_PATH -> decodeRadioConfig(body.substringAfter('?', ""))
            else -> decodeAdvert(body)
        }
    }

    /**
     * Parse a query string. First occurrence of a key wins: a duplicated
     * `public_key` must not let a trailing copy override the one a human
     * read off the screen. Null if any escape is malformed.
     */
    private fun parseQuery(query: String): Map<String, String>? {
        val params = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=').lowercase()
            val raw = pair.substringAfter('=', "")
            if (key.isNotEmpty() && key !in params) {
                // `+` is a space HERE and only here. A query string is
                // x-www-form-urlencoded, and the firmware's own
                // docs/qr_codes.md shows it that way:
                //   meshcore://contact/add?name=Example+Contact&…
                // Leaving it literal — which this did, on a comment
                // asserting the mainstream app always uses %20 — imports
                // that contact as "Example+Contact".
                //
                // The cost is a node genuinely named "A+B", which
                // arrives as "A B". That is the trade the encoding
                // makes, not one this app gets to opt out of: a decoder
                // cannot tell the two apart, and the published example
                // settles which reading is meant. Our own encoder still
                // emits %20, which every decoder reads correctly.
                params[key] = percentDecode(raw.replace('+', ' ')) ?: return null
            }
        }
        return params
    }

    private fun decodeContactCard(query: String): Decoded {
        if (query.isEmpty()) return Decoded.Malformed
        val params = parseQuery(query) ?: return Decoded.Malformed

        val pubKeyHex = params["public_key"]?.trim()?.lowercase() ?: return Decoded.Malformed
        val pubKey = hexToBytesOrNull(pubKeyHex) ?: return Decoded.Malformed
        if (pubKey.size != PUB_KEY_BYTES) return Decoded.Malformed

        // A type byte is what the firmware stores; anything wider is
        // malformed rather than silently masked down to a byte.
        val type = params["type"]?.let { it.trim().toIntOrNull() ?: return Decoded.Malformed }
            ?: Codes.ADV_TYPE_CHAT
        if (type !in 0..255) return Decoded.Malformed

        val name = sanitizeName(params["name"].orEmpty())
        return Decoded.Contact(name = name, pubKeyHex = pubKeyHex, type = type)
    }

    private fun decodeChannelShare(query: String): Decoded {
        if (query.isEmpty()) return Decoded.Malformed
        val params = parseQuery(query) ?: return Decoded.Malformed
        val psk = params["channel_secret"]?.trim()?.lowercase() ?: return Decoded.Malformed
        val bytes = hexToBytesOrNull(psk) ?: return Decoded.Malformed
        if (bytes.size != CHANNEL_PSK_BYTES) return Decoded.Malformed
        // An all-zero key is what a broken generator emits; it would look
        // like a working channel while protecting nothing.
        if (bytes.all { it.toInt() == 0 }) return Decoded.Malformed
        return Decoded.ChannelShare(
            name = sanitizeName(params["name"].orEmpty()),
            pskHex = psk,
        )
    }

    /**
     * Parse a settings code, refusing anything a radio would reject.
     *
     * Every bound here is the firmware's, not a guess: SF and CR are
     * protocol-bounded, the frequency range is what `set radio` accepts,
     * the bandwidth must be a real LoRa step, and the hash mode stops at
     * 2 because mode 3 is reserved. A value outside these cannot be a
     * mesh anyone is on, so it is refused rather than shown to the user
     * as something they could choose to apply.
     */
    private fun decodeRadioConfig(query: String): Decoded {
        if (query.isEmpty()) return Decoded.Malformed
        val params = parseQuery(query) ?: return Decoded.Malformed

        // Version first: an unknown one must not be parsed on a guess.
        val version = params["v"]?.trim()?.toIntOrNull() ?: return Decoded.Malformed
        if (version != RADIO_VERSION) return Decoded.UnsupportedVersion(version)

        val frequencyKhz = params["freq"]?.let { RadioUnits.mhzTextToKhz(it) }
            ?: return Decoded.Malformed
        if (frequencyKhz !in 300_000L..2_500_000L) return Decoded.Malformed

        val bandwidthHz = params["bw"]?.let { RadioUnits.khzTextToHz(it) }
            ?: return Decoded.Malformed
        if (bandwidthHz !in LORA_BANDWIDTHS_HZ) return Decoded.Malformed

        val sf = params["sf"]?.trim()?.toIntOrNull() ?: return Decoded.Malformed
        if (sf !in 5..12) return Decoded.Malformed

        val cr = params["cr"]?.trim()?.toIntOrNull() ?: return Decoded.Malformed
        if (cr !in 5..8) return Decoded.Malformed

        // Absent hash means "whatever this mesh already uses" — older
        // meshes predate the setting entirely, so it is not required.
        val hash = params["hash"]?.let {
            it.trim().toIntOrNull() ?: return Decoded.Malformed
        } ?: PathHashMode.MIN_MODE
        if (!PathHashMode.isValid(hash)) return Decoded.Malformed

        val region = sanitizeName(params["region"].orEmpty()).takeIf { it.isNotBlank() }

        return Decoded.RadioConfig(
            name = sanitizeName(params["name"].orEmpty()),
            frequencyKhz = frequencyKhz,
            bandwidthHz = bandwidthHz,
            spreadingFactor = sf,
            codingRate = cr,
            pathHashMode = hash,
            region = region,
        )
    }

    /** The LoRa bandwidth steps, in Hz. Anything else is not a radio. */
    private val LORA_BANDWIDTHS_HZ = setOf(
        7_800L, 10_400L, 15_600L, 20_800L, 31_250L, 62_500L, 125_000L, 250_000L, 500_000L,
    )

    private fun decodeAdvert(hex: String): Decoded {
        val blob = hexToBytesOrNull(hex) ?: return Decoded.Malformed
        if (blob.size < MIN_BLOB_BYTES) return Decoded.Malformed
        return Decoded.Advert(blob)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Strip control characters from a scanned name before it reaches the
     * UI or a `cstr` field: an embedded NUL would truncate the record on
     * the radio, and newlines let a hostile QR fake extra lines of UI.
     */
    fun sanitizeName(raw: String): String =
        truncateToBytes(
            raw.filter { it.code >= 0x20 && it.code != 0x7F }.trim(),
            MAX_NAME_BYTES,
        )

    /** Truncate on a code-point boundary so UTF-8 never splits mid-char. */
    fun truncateToBytes(text: String, maxBytes: Int): String {
        if (text.encodeToByteArray().size <= maxBytes) return text
        var end = text.length
        while (end > 0) {
            // Don't cut between a surrogate pair.
            if (end < text.length && text[end - 1].isHighSurrogate()) {
                end--
                continue
            }
            val candidate = text.substring(0, end)
            if (candidate.encodeToByteArray().size <= maxBytes) return candidate
            end--
        }
        return ""
    }

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"

    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val ch = value.toChar()
            if (value < 0x80 && ch in UNRESERVED) {
                append(ch)
            } else {
                append('%').append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
            }
        }
    }

    /**
     * Percent-decode to UTF-8. Returns null on a truncated or non-hex
     * escape.
     *
     * Strictly RFC 3986: `+` is left alone here, because this decodes
     * whole URI components and a `+` is only a space inside a *query*.
     * [parseQuery] substitutes before calling this — see the note there
     * for why, and for what it costs.
     */
    fun percentDecode(text: String): String? {
        val out = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hi = hexDigit(text[i + 1]) ?: return null
                val lo = hexDigit(text[i + 2]) ?: return null
                out.add(((hi shl 4) or lo).toByte())
                i += 3
            } else {
                for (b in c.toString().encodeToByteArray()) out.add(b)
                i++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun hexDigit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
