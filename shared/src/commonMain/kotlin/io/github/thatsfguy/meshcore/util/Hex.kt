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
