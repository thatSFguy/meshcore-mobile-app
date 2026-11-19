package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.util.hexToBytesOrNull

/**
 * The channel datagram developer tool (Settings → Diagnostics), parsed and
 * worded once for both apps (2026-11-18). A datagram names an application
 * by its 16-bit data type, not a person — the firmware's
 * docs/number_allocations.md; FF00–FFFF needs no registration.
 */
object DatagramTool {
    const val TITLE = "Channel datagram (developer)"
    const val SUMMARY = "Sends raw bytes to a channel. Nothing in this app reads them."
    const val HINT = "A datagram names an application by its 16-bit data type, not a person — see the " +
        "firmware's docs/number_allocations.md. FF00-FFFF needs no registration and is the range to test " +
        "with. Received datagrams appear in the diagnostics log with their type and payload in hex; they " +
        "are never shown as text, because the bytes belong to another application and are chosen by " +
        "whoever sent them.\n\nChange the payload every time. A datagram carries no timestamp, so the same " +
        "type and payload make an identical packet, and the mesh drops it as one it has already seen. It " +
        "still says \"Sent\" — the radio did transmit it — but nothing receives it."
    const val DEFAULT_TYPE = "FF00"
    const val BAD_PAYLOAD = "Payload must be an even number of hex digits."
    const val REFUSED = "Refused. Check the channel exists, the type is not 0000, and the payload is 1-163 bytes."

    class Request(val channel: Int, val dataType: Int, val payload: ByteArray)

    /** The send, or null with [problem] saying why not. */
    fun parse(channel: String, typeHex: String, payloadHex: String): Request? {
        val idx = channel.trim().toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        val type = typeHex.trim().takeIf { it.length in 1..4 && it.all { c -> c.isHexDigit() } }?.toInt(16) ?: return null
        val bytes = hexToBytesOrNull(payloadHex.trim())?.takeIf { it.isNotEmpty() } ?: return null
        return Request(idx, type, bytes)
    }

    fun problem(channel: String, typeHex: String, payloadHex: String): String? = when {
        channel.trim().toIntOrNull()?.takeIf { it in 0..255 } == null -> "Channel is a slot number."
        typeHex.trim().let { it.isEmpty() || it.length > 4 || !it.all { c -> c.isHexDigit() } } -> "Type is up to 4 hex digits."
        hexToBytesOrNull(payloadHex.trim()).let { it == null || it.isEmpty() } -> BAD_PAYLOAD
        else -> null
    }

    fun result(ok: Boolean, request: Request): String =
        if (ok) "Sent ${request.payload.size} bytes to channel ${request.channel}." else REFUSED

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
