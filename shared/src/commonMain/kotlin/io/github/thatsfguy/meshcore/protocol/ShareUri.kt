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
                params[key] = percentDecode(raw) ?: return null
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
     * escape. `+` is left literal: the mainstream app encodes spaces as
     * `%20`, so a `+` in a scanned name is a real plus sign.
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
