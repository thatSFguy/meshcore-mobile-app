package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `REQ_TYPE_GET_OWNER_INFO`, against the firmware's writer:
 * `sprintf(&reply_data[4], "%s\n%s\n%s", FIRMWARE_VERSION, node_name, owner_info)`
 * sent at `4 + strlen` (simple_repeater/MyMesh.cpp:375-377), then padded
 * to the cipher block.
 */
class OwnerInfoTest {

    private fun body(text: String, padding: Int = 0) =
        text.encodeToByteArray() + ByteArray(padding)

    @Test
    fun requestIsTheBareTypeByte() {
        // MyMesh.cpp:51 — `#define REQ_TYPE_GET_OWNER_INFO 0x07`. The
        // reader looks at payload[0] only.
        assertContentEquals(byteArrayOf(0x07), OwnerInfo.requestPayload())
        assertEquals(0x07, Codes.REQ_TYPE_GET_OWNER_INFO)
    }

    // --- positive controls -------------------------------------------------

    @Test
    fun readsVersionNameAndOwner() {
        val r = assertNotNull(OwnerInfo.parse(body("v1.16.0\nSpartaMI\nRob, Grand Rapids", padding = 13)))
        assertEquals("v1.16.0", r.firmwareVersion)
        assertEquals("SpartaMI", r.nodeName)
        assertEquals("Rob, Grand Rapids", r.ownerInfo)
    }

    @Test
    fun ownerTextKeepsItsOwnNewlines() {
        // `set owner.info a|b` stores "a\nb" (CommonCLI.cpp:655), so the
        // reply has more than two newlines and only the first two split.
        val r = assertNotNull(OwnerInfo.parse(body("v1.16.0\nNode\nline one\nline two\nline three")))
        assertEquals("line one\nline two\nline three", r.ownerInfo)
        assertEquals("Node", r.nodeName)
    }

    @Test
    fun unsetOwnerIsEmptyNotMissing() {
        // owner_info defaults to "", so the reply ends at the second
        // newline. That is an answer — "no owner set" — not a failure.
        val r = assertNotNull(OwnerInfo.parse(body("v1.16.0\nNode\n", padding = 3)))
        assertEquals("", r.ownerInfo)
    }

    @Test
    fun paddingIsNotText() {
        val r = assertNotNull(OwnerInfo.parse(body("v1.16.0\nNode\nowner", padding = 15)))
        assertEquals("owner", r.ownerInfo)
        assertTrue(r.ownerInfo.none { it.code == 0 })
    }

    // --- hostile and malformed ---------------------------------------------

    @Test
    fun emptyBodyIsNoReply() {
        assertNull(OwnerInfo.parse(ByteArray(0)))
        assertNull(OwnerInfo.parse(ByteArray(16)))   // all padding
    }

    @Test
    fun aBodyWithoutTheSeparatorIsNotThisReply() {
        // e.g. a status or neighbours body that slipped through.
        assertNull(OwnerInfo.parse(body("v1.16.0")))
        assertNull(OwnerInfo.parse(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun aMissingVersionIsRefused() {
        assertNull(OwnerInfo.parse(body("\nNode\nowner")))
    }

    @Test
    fun oversizedBodyIsRefused() {
        val big = body("v1\nN\n" + "x".repeat(OwnerInfo.MAX_BODY_BYTES))
        assertNull(OwnerInfo.parse(big))
    }

    @Test
    fun controlCharactersAreStripped() {
        // Text off the mesh: no terminal escapes, no carriage returns.
        val r = assertNotNull(OwnerInfo.parse(body("v1.16.0\r\nNo\u001B[2Jde\nown\u0007er\r\nline2")))
        assertEquals("v1.16.0", r.firmwareVersion)
        assertEquals("No[2Jde", r.nodeName)
        assertEquals("owner\nline2", r.ownerInfo)
    }

    @Test
    fun invalidUtf8DoesNotThrow() {
        val bytes = "v1\nN\n".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())
        assertNotNull(OwnerInfo.parse(bytes))
    }
}
