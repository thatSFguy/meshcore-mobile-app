package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Room-server posts arrive as TXT_TYPE_SIGNED contact messages whose
 * "signature" field is really the original author's 4-byte pubkey
 * prefix. The parser has to hand that prefix back — without it a room
 * thread can't say who wrote anything — while still starting the text
 * after those bytes.
 */
class RoomPostParseTest {

    private fun frame(
        txtType: Int,
        text: String,
        senderPrefix: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6),
        authorPrefix: ByteArray? = null,
        pathLen: Int = 0,
        timestamp: Long = 1_700_000_000L,
    ): ByteArray {
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV)
        w.writeBytes(senderPrefix)
        w.writeByte(pathLen)
        w.writeByte(txtType)
        w.writeUInt32LE(timestamp)
        authorPrefix?.let { w.writeBytes(it) }
        w.writeBytes(text.encodeToByteArray())
        w.writeByte(0)
        return w.toBytes()
    }

    private fun parse(frame: ByteArray) =
        ResponseParser.parse(frame) as? DeviceEvent.ContactMessage

    // ---- room posts ------------------------------------------------------

    @Test
    fun `a signed post exposes the author prefix and the text after it`() {
        val author = byteArrayOf(0x9D.toByte(), 0x00, 0x12, 0x5A)
        val ev = parse(frame(Codes.TXT_TYPE_SIGNED, "NetBank", authorPrefix = author))
        assertTrue(ev != null)
        assertContentEquals(author, ev.roomAuthorPrefix)
        assertEquals("NetBank", ev.text)
    }

    @Test
    fun `the signed flag is also recognised when shifted`() {
        // Firmware sends the type either raw or shifted left by two.
        val author = byteArrayOf(1, 2, 3, 4)
        val ev = parse(
            frame(Codes.TXT_TYPE_SIGNED shl 2, "hi", authorPrefix = author),
        )
        assertTrue(ev != null)
        assertContentEquals(author, ev.roomAuthorPrefix)
        assertEquals("hi", ev.text)
    }

    @Test
    fun `a plain direct message has no author prefix`() {
        val ev = parse(frame(Codes.TXT_TYPE_PLAIN, "hello"))
        assertTrue(ev != null)
        assertNull(ev.roomAuthorPrefix)
        assertEquals("hello", ev.text)
    }

    @Test
    fun `a truncated signed frame does not crash the RX path`() {
        // Signed, but the author prefix is cut short — must degrade, not
        // throw: this is attacker-reachable input.
        val w = BufferWriter()
        w.writeByte(Codes.RESP_CODE_CONTACT_MSG_RECV)
        w.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        w.writeByte(0)
        w.writeByte(Codes.TXT_TYPE_SIGNED)
        w.writeUInt32LE(1_700_000_000L)
        w.writeBytes(byteArrayOf(9, 9))   // only 2 of 4 prefix bytes
        val ev = ResponseParser.parse(w.toBytes())
        assertTrue(ev is DeviceEvent.ContactMessage || ev is DeviceEvent.Unknown)
    }

    // ---- message classification -----------------------------------------

    @Test
    fun `CLI replies are distinguishable from room posts and plain text`() {
        // The conversation view hides txt_type 1 (remote-admin console
        // output) and shows everything else. A room post is type 2, so a
        // "plain only" filter would empty every room thread — which is
        // exactly the regression this pins.
        assertEquals(1, Codes.TXT_TYPE_CLI_DATA)
        assertEquals(2, Codes.TXT_TYPE_SIGNED)
        assertEquals(0, Codes.TXT_TYPE_PLAIN)

        val cli = parse(frame(Codes.TXT_TYPE_CLI_DATA, "> get radio"))
        assertEquals(Codes.TXT_TYPE_CLI_DATA, cli?.txtType)

        val room = parse(
            frame(Codes.TXT_TYPE_SIGNED, "post", authorPrefix = byteArrayOf(1, 2, 3, 4)),
        )
        assertTrue(room!!.txtType != Codes.TXT_TYPE_CLI_DATA)
    }

    // ---- hops on received messages ---------------------------------------

    @Test
    fun `a message's path byte decodes to hops, not bytes`() {
        // Same packing as a contact record: 0x44 is 4 hops at 2-byte
        // hashes, not 68 of anything.
        val ev = parse(frame(Codes.TXT_TYPE_PLAIN, "x", pathLen = 0x44))
        assertEquals(4, PathCodec.decodePathLen(ev!!.pathLen).hops)
    }

    @Test
    fun `a flooded message reports flood rather than a hop count`() {
        val ev = parse(frame(Codes.TXT_TYPE_PLAIN, "x", pathLen = PathCodec.PATH_LEN_FLOOD))
        assertTrue(PathCodec.decodePathLen(ev!!.pathLen).isFlood)
    }
}
