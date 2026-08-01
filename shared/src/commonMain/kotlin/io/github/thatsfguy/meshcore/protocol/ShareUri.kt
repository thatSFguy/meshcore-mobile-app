package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.hexToBytesOrNull
import io.github.thatsfguy.meshcore.util.toHex

/**
 * The `meshcore://<hex>` contact-share encoding used by QR codes and
 * pasted links.
 *
 * The payload is an advert blob the *radio* exported, so it still carries
 * the node's Ed25519 signature; the importing device re-verifies it. This
 * object only does the transport encoding — it deliberately does not
 * validate the blob's contents, since that check belongs to the firmware
 * that owns the key material.
 *
 * Decoding runs on scanned QR data, i.e. wholly attacker-controlled
 * input, so every failure mode returns a typed error rather than
 * throwing.
 */
object ShareUri {

    const val SCHEME = "meshcore://"

    /**
     * Upper bound on the hex payload. An advert blob is ~100 bytes; the
     * cap is generous but keeps a hostile QR from making us allocate
     * megabytes before any parse happens.
     */
    const val MAX_HEX_LENGTH = 4096

    /** Shortest blob the radio will accept back as a contact import. */
    const val MIN_BLOB_BYTES = 32

    fun encode(blob: ByteArray): String = SCHEME + blob.toHex()

    sealed interface Decoded {
        data class Ok(val blob: ByteArray) : Decoded
        /** Not a contact code at all (wrong scheme, or plain text). */
        data object NotAContactCode : Decoded
        /** Correct scheme but oversized — rejected before decoding. */
        data object TooLarge : Decoded
        /** Correct scheme, unusable payload (odd length, non-hex, stub). */
        data object Malformed : Decoded
    }

    fun decode(text: String): Decoded {
        val trimmed = text.trim()
        // Case-insensitive: some QR generators upper-case the scheme.
        if (!trimmed.lowercase().startsWith(SCHEME)) return Decoded.NotAContactCode
        val hex = trimmed.substring(SCHEME.length)
        if (hex.length > MAX_HEX_LENGTH) return Decoded.TooLarge
        val blob = hexToBytesOrNull(hex) ?: return Decoded.Malformed
        if (blob.size < MIN_BLOB_BYTES) return Decoded.Malformed
        return Decoded.Ok(blob)
    }
}
