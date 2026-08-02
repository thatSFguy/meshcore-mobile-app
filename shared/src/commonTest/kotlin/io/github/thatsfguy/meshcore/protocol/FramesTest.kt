package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FramesTest {

    private val pubKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun appStartLayout() {
        val f = Frames.appStart(appName = "Test", appVersion = 1)
        assertEquals(Codes.CMD_APP_START, f[0].toInt())
        assertEquals(1, f[1].toInt())
        // 6 reserved zero bytes
        for (i in 2..7) assertEquals(0, f[i].toInt())
        assertContentEquals("Test".encodeToByteArray() + 0, f.copyOfRange(8, f.size))
    }

    @Test
    fun deviceQueryLayout() {
        val f = Frames.deviceQuery()
        assertContentEquals(byteArrayOf(22, 4), f)
    }

    @Test
    fun sendTextMessageLayout() {
        val f = Frames.sendTextMessage(pubKey, "hi", timestampSeconds = 0x01020304L)
        assertEquals(Codes.CMD_SEND_TXT_MSG, f[0].toInt())
        assertEquals(Codes.TXT_TYPE_PLAIN, f[1].toInt())
        assertEquals(0, f[2].toInt()) // attempt
        // timestamp LE
        assertContentEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), f.copyOfRange(3, 7))
        // 6-byte pubkey prefix
        assertContentEquals(pubKey.copyOfRange(0, 6), f.copyOfRange(7, 13))
        assertContentEquals("hi".encodeToByteArray() + 0, f.copyOfRange(13, f.size))
    }

    @Test
    fun cliCommandUsesCliTextType() {
        val f = Frames.sendCliCommand(pubKey, "ver", timestampSeconds = 1L)
        assertEquals(Codes.CMD_SEND_TXT_MSG, f[0].toInt())
        assertEquals(Codes.TXT_TYPE_CLI_DATA, f[1].toInt())
    }

    @Test
    fun sendChannelTextMessageLayout() {
        val f = Frames.sendChannelTextMessage(2, "yo", timestampSeconds = 5L)
        assertEquals(Codes.CMD_SEND_CHANNEL_TXT_MSG, f[0].toInt())
        assertEquals(Codes.TXT_TYPE_PLAIN, f[1].toInt())
        assertEquals(2, f[2].toInt())
        assertContentEquals(byteArrayOf(5, 0, 0, 0), f.copyOfRange(3, 7))
        assertContentEquals("yo".encodeToByteArray() + 0, f.copyOfRange(7, f.size))
    }

    @Test
    fun getContactsWithAndWithoutSince() {
        assertContentEquals(byteArrayOf(4), Frames.getContacts())
        assertContentEquals(byteArrayOf(4, 0x39, 0x30, 0, 0), Frames.getContacts(12345L))
    }

    @Test
    fun sendLoginLayout() {
        val f = Frames.sendLogin(pubKey, "pw")
        assertEquals(Codes.CMD_SEND_LOGIN, f[0].toInt())
        assertContentEquals(pubKey, f.copyOfRange(1, 33))
        assertContentEquals("pw".encodeToByteArray() + 0, f.copyOfRange(33, f.size))
    }

    @Test
    fun sendLoginRejectsShortKey() {
        assertFailsWith<IllegalArgumentException> { Frames.sendLogin(ByteArray(6), "pw") }
    }

    @Test
    fun setChannelLayout() {
        val psk = hexToBytes("8b3387e9c5cdea6ac9e5edbaa115cd72")
        val f = Frames.setChannel(1, "General", psk)
        assertEquals(Codes.CMD_SET_CHANNEL, f[0].toInt())
        assertEquals(1, f[1].toInt())
        assertEquals(2 + 32 + 16, f.size)
        // name null-padded to 32
        assertContentEquals("General".encodeToByteArray(), f.copyOfRange(2, 9))
        assertEquals(0, f[9].toInt())
        assertContentEquals(psk, f.copyOfRange(34, 50))
    }

    @Test
    fun setChannelNameTruncatedTo31Bytes() {
        val f = Frames.setChannel(0, "x".repeat(40), ByteArray(16))
        // Fixed field: 32 bytes with last byte forced NUL.
        assertEquals(0, f[2 + 31].toInt())
        assertEquals('x'.code, f[2 + 30].toInt())
    }

    @Test
    fun setRadioParamsLayout() {
        val f = Frames.setRadioParams(910_525L, 250_000L, 10, 5)
        assertEquals(Codes.CMD_SET_RADIO_PARAMS, f[0].toInt())
        assertEquals(11, f.size)
        val r = BufferReader(f)
        r.skipBytes(1)
        assertEquals(910_525L, r.readUInt32LE())   // kHz, not Hz
        assertEquals(250_000L, r.readUInt32LE())
        assertEquals(10, r.readByte())
        assertEquals(5, r.readByte())
        // v9+ variant appends client_repeat
        assertEquals(12, Frames.setRadioParams(1, 2, 3, 4, clientRepeat = true).size)
    }

    @Test
    fun setAdvertLatLonScales() {
        val f = Frames.setAdvertLatLon(37.7749, -122.4194)
        val r = BufferReader(f)
        r.skipBytes(1)
        assertEquals(37_774_900, r.readInt32LE())
        assertEquals(-122_419_400, r.readInt32LE())
    }

    @Test
    fun setAdvertNameCapsAt31Bytes() {
        val f = Frames.setAdvertName("n".repeat(64))
        assertEquals(1 + 31, f.size)
    }

    @Test
    fun addUpdateContactTailVariants() {
        val base = Frames.addUpdateContact(
            pubKey, type = 1, flags = 0, pathLen = 0, path = ByteArray(0),
            name = "bob", timestampSeconds = 7L,
        )
        assertEquals(1 + 32 + 1 + 1 + 1 + 64 + 32 + 4, base.size)

        val withLoc = Frames.addUpdateContact(
            pubKey, 1, 0, 0, ByteArray(0), "bob", 7L, lat = 1.0, lon = 2.0,
        )
        assertEquals(base.size + 8, withLoc.size)

        val withLocAndMod = Frames.addUpdateContact(
            pubKey, 1, 0, 0, ByteArray(0), "bob", 7L, lat = 1.0, lon = 2.0,
            lastModifiedSeconds = 9L,
        )
        assertEquals(base.size + 12, withLocAndMod.size)
    }

    @Test
    fun rebootPayload() {
        assertContentEquals(byteArrayOf(19) + "reboot".encodeToByteArray(), Frames.reboot())
    }

    @Test
    fun setFloodScopeResetAndSet() {
        assertContentEquals(byteArrayOf(54, 0), Frames.setFloodScope(null))
        val scope = ByteArray(16) { it.toByte() }
        val f = Frames.setFloodScope(scope)
        assertEquals(18, f.size)
        assertContentEquals(scope, f.copyOfRange(2, 18))
    }

    @Test
    fun messageSizeLimits() {
        assertTrue(Frames.maxContactMessageBytes() in 1..Codes.MAX_TEXT_PAYLOAD_BYTES)
        assertTrue(Frames.maxChannelMessageBytes("Al") > Frames.maxChannelMessageBytes(null))
    }

    @Test
    fun airtimeMatchesReferenceShape() {
        // Flood timeout must exceed a single direct-hop timeout for the
        // same radio params (structural sanity, not a golden value).
        val direct = Airtime.messageTimeoutMs(bwHz = 250_000, sf = 10, cr = 5, pathLength = 0)
        val flood = Airtime.messageTimeoutMs(bwHz = 250_000, sf = 10, cr = 5, pathLength = -1)
        assertTrue(direct > 500)
        assertTrue(flood > direct)
    }
}
