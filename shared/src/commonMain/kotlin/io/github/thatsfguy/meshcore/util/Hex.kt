package io.github.thatsfguy.meshcore.util

/** Lowercase hex encoding of [this]. */
fun ByteArray.toHex(): String = buildString(size * 2) {
    for (b in this@toHex) {
        val v = b.toInt() and 0xFF
        append(HEX_CHARS[v ushr 4])
        append(HEX_CHARS[v and 0x0F])
    }
}

private const val HEX_CHARS = "0123456789abcdef"

/**
 * Decode a hex string to bytes. Returns null (never throws) for odd
 * length, empty input, or non-hex characters — callers feed this
 * user-supplied QR/clipboard text.
 */
fun hexToBytesOrNull(hex: String): ByteArray? {
    val cleaned = hex.trim()
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
    val out = ByteArray(cleaned.length / 2)
    for (i in out.indices) {
        val hi = cleaned[i * 2].digitToIntOrNull(16) ?: return null
        val lo = cleaned[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** Strict variant for programmer-controlled input. */
fun hexToBytes(hex: String): ByteArray =
    hexToBytesOrNull(hex) ?: throw IllegalArgumentException("Invalid hex string")

/**
 * Strip control and bidi-format characters from an attacker-supplied
 * display name before it reaches a list row, notification, or map
 * label. Newlines and RTL overrides (U+202A–202E, U+2066–2069) let a
 * hostile node impersonate another contact visually; nothing legitimate
 * needs them in a 31-byte node name.
 */
fun sanitizeDisplayName(raw: String, maxChars: Int = 32): String =
    raw.filter { c ->
        val code = c.code
        // Drop C0 (incl. \n, \r, \t), DEL + C1, and bidi controls.
        code >= 0x20 && code != 0x7F && (code < 0x80 || code > 0x9F) &&
            code != 0x200E && code != 0x200F &&
            (code < 0x202A || code > 0x202E) &&
            (code < 0x2066 || code > 0x2069)
    }.trim().take(maxChars)
