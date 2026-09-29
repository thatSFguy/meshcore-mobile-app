package io.github.thatsfguy.meshcore.protocol

/**
 * `CMD_SEND_BINARY_REQ` / `REQ_TYPE_GET_OWNER_INFO` — the one piece of a
 * repeater's configuration a GUEST session can read.
 *
 * Why it exists here: every `get …` the Settings form sends is CLI text,
 * and the repeater only runs CLI text from an admin —
 * `simple_repeater/MyMesh.cpp:689`,
 * `else if (type == PAYLOAD_TYPE_TXT_MSG && len > 5 && client->isAdmin())`.
 * A guest's `get` is dropped without a reply or even an ACK. Binary
 * requests go through `handleRequest` instead, and this one carries no
 * permission check at all (`MyMesh.cpp:375`).
 *
 * Wire form, from the firmware's reader and writer:
 * ```
 *   request : [0x07]                                  (MyMesh.cpp:51, :375)
 *   reply   : "<FIRMWARE_VERSION>\n<node_name>\n<owner_info>"   (MyMesh.cpp:376)
 * ```
 * The reply is `sprintf`'d and sent at `4 + strlen`, so it has no length
 * field and arrives zero-padded to the cipher block. `owner_info` is the
 * one field that may itself hold newlines — `set owner.info` turns `|`
 * into `\n` on the way in (`CommonCLI.cpp:655`) — so the split is into
 * at most three parts and everything after the second newline is owner.
 *
 * Repeater-only: `REQ_TYPE_GET_OWNER_INFO` is marked
 * `FIRMWARE_VER_LEVEL >= 2`, and the room server and sensor do not
 * handle it. An older repeater returns 0 from `handleRequest`, which
 * sends nothing — so silence has to read as "no answer", never as
 * "no owner".
 */
object OwnerInfo {

    /** Request payload. The firmware reads `payload[0]` and nothing else. */
    fun requestPayload(): ByteArray = byteArrayOf(Codes.REQ_TYPE_GET_OWNER_INFO.toByte())

    /**
     * Longest body worth reading. The firmware's `owner_info` is 120 bytes
     * (`CommonCLI.h:62`) and a node name 32; the reply buffer is smaller
     * than this. Anything longer is not a reply this code understands.
     */
    const val MAX_BODY_BYTES = 256

    data class Reply(
        val firmwareVersion: String,
        val nodeName: String,
        /** Newlines preserved; empty when the operator never set it. */
        val ownerInfo: String,
    )

    /**
     * Parse the reply body (after the 4-byte echoed tag). Null when the
     * body cannot be this reply: oversized, empty, or missing the
     * version/name separator.
     */
    fun parse(body: ByteArray): Reply? {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) return null
        // Text ends at the first NUL; the rest is cipher padding.
        val end = body.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) body.size else it }
        val text = body.copyOfRange(0, end).decodeToString()
        val parts = text.split('\n', limit = 3)
        if (parts.size < 2) return null
        val version = clean(parts[0])
        if (version.isEmpty()) return null
        return Reply(
            firmwareVersion = version,
            nodeName = clean(parts[1]),
            ownerInfo = if (parts.size == 3) cleanMultiline(parts[2]) else "",
        )
    }

    /** Drop control characters — this is text off the mesh. */
    private fun clean(s: String): String =
        s.filter { !it.isISOControl() }.trim()

    private fun cleanMultiline(s: String): String =
        s.split('\n').joinToString("\n") { clean(it) }.trim()
}
