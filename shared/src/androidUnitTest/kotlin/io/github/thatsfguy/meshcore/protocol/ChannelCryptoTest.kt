package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.util.hexToBytes
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChannelCryptoTest {

    private val crypto = AndroidCryptoProvider()

    private fun channelPlaintext(timestamp: Long, txtType: Int, text: String): ByteArray {
        val w = BufferWriter()
        w.writeUInt32LE(timestamp)
        w.writeByte(txtType)
        w.writeString(text)
        w.writeByte(0)
        return w.toBytes()
    }

    @Test
    fun decryptRoundTrip() {
        val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK
        val plain = channelPlaintext(1234L, Codes.TXT_TYPE_PLAIN, "alice: hello mesh")
        val encrypted = ChannelCrypto.encryptForTest(crypto, psk, plain)

        val decrypted = ChannelCrypto.decrypt(crypto, psk, encrypted)
        assertNotNull(decrypted)
        val parsed = ChannelCrypto.parsePlaintext(decrypted)
        assertNotNull(parsed)
        assertEquals(1234L, parsed.timestamp)
        assertEquals("alice", parsed.senderName)
        assertEquals("hello mesh", parsed.text)
    }

    @Test
    fun decryptFailsOnMacTamper() {
        val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK
        val encrypted = ChannelCrypto.encryptForTest(
            crypto, psk, channelPlaintext(1L, 0, "x: y"),
        )
        encrypted[0] = (encrypted[0].toInt() xor 0xFF).toByte()
        assertNull(ChannelCrypto.decrypt(crypto, psk, encrypted))
    }

    @Test
    fun decryptFailsOnCiphertextTamper() {
        val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK
        val encrypted = ChannelCrypto.encryptForTest(
            crypto, psk, channelPlaintext(1L, 0, "x: y"),
        )
        encrypted[5] = (encrypted[5].toInt() xor 0x01).toByte()
        assertNull(ChannelCrypto.decrypt(crypto, psk, encrypted))
    }

    @Test
    fun decryptFailsOnWrongKey() {
        val encrypted = ChannelCrypto.encryptForTest(
            crypto, ChannelCrypto.PUBLIC_CHANNEL_PSK, channelPlaintext(1L, 0, "x: y"),
        )
        assertNull(ChannelCrypto.decrypt(crypto, ByteArray(16) { 1 }, encrypted))
    }

    @Test
    fun decryptGuardsMalformedInput() {
        val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK
        assertNull(ChannelCrypto.decrypt(crypto, psk, ByteArray(0)))
        assertNull(ChannelCrypto.decrypt(crypto, psk, ByteArray(2)))       // MAC only
        assertNull(ChannelCrypto.decrypt(crypto, psk, ByteArray(2 + 15))) // not block-aligned
    }

    @Test
    fun channelHashIsFirstShaByte() {
        val psk = ChannelCrypto.PUBLIC_CHANNEL_PSK
        val expected = crypto.sha256(psk)[0].toInt() and 0xFF
        assertEquals(expected, ChannelCrypto.channelHash(crypto, psk))
    }

    @Test
    fun hashtagPskDerivation() {
        // SHA256("#test")[0..15] — both spellings normalize to the same key.
        val a = ChannelCrypto.hashtagPsk(crypto, "test")
        val b = ChannelCrypto.hashtagPsk(crypto, "#test")
        assertContentEquals(a, b)
        assertContentEquals(crypto.sha256("#test".encodeToByteArray()).copyOfRange(0, 16), a)
    }

    @Test
    fun communityDerivations() {
        val secret = ByteArray(32) { (it * 3).toByte() }
        val publicPsk = ChannelCrypto.communityPublicPsk(crypto, secret)
        assertContentEquals(
            crypto.hmacSha256(secret, "channel:v1:__public__".encodeToByteArray()).copyOfRange(0, 16),
            publicPsk,
        )

        // Hashtag normalization: strip '#', lowercase, trim.
        val h1 = ChannelCrypto.communityHashtagPsk(crypto, secret, "#Rescue ")
        val h2 = ChannelCrypto.communityHashtagPsk(crypto, secret, "rescue")
        assertContentEquals(h1, h2)

        val cid = ChannelCrypto.communityId(crypto, secret)
        assertContentEquals(crypto.sha256("community:v1".encodeToByteArray() + secret), cid)
        assertEquals(32, cid.size)
    }

    @Test
    fun publicPskConstant() {
        assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", ChannelCrypto.PUBLIC_CHANNEL_PSK.toHex())
        assertContentEquals(hexToBytes("8b3387e9c5cdea6ac9e5edbaa115cd72"), ChannelCrypto.PUBLIC_CHANNEL_PSK)
    }

    @Test
    fun nonPlainTxtTypeDropped() {
        val plain = channelPlaintext(9L, 0x08, "cli: data") // (txt_type >> 2) != 0
        assertNull(ChannelCrypto.parsePlaintext(plain))
    }

    @Test
    fun grpTxtEndToEndViaRawPacket() {
        // Full RX-log path: raw GRP_TXT packet → RawPacket.parse →
        // channel-hash match → decrypt → plaintext.
        val psk = ChannelCrypto.hashtagPsk(crypto, "#offgrid")
        val plain = channelPlaintext(555L, 0, "bob: over the air")
        val encrypted = ChannelCrypto.encryptForTest(crypto, psk, plain)
        val header = (Codes.PAYLOAD_TYPE_GRP_TXT shl 2) or 0x01
        val packet = byteArrayOf(header.toByte(), 0x00) + // no path
            byteArrayOf(ChannelCrypto.channelHash(crypto, psk).toByte()) + encrypted

        val parsed = RawPacket.parse(packet)
        assertNotNull(parsed)
        assertEquals(Codes.PAYLOAD_TYPE_GRP_TXT, parsed.payloadType)
        val channelHash = parsed.payload[0].toInt() and 0xFF
        assertEquals(ChannelCrypto.channelHash(crypto, psk), channelHash)
        val decrypted = ChannelCrypto.decrypt(
            crypto, psk, parsed.payload.copyOfRange(1, parsed.payload.size),
        )
        assertNotNull(decrypted)
        val msg = ChannelCrypto.parsePlaintext(decrypted)!!
        assertEquals("bob", msg.senderName)
        assertEquals("over the air", msg.text)
    }
}
