package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.CommunityCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JVM: needs a real HMAC/SHA for the derived keys. */
class CommunityCodeTest {

    private val crypto = AndroidCryptoProvider()
    private val secret = ByteArray(32) { it.toByte() }
    // base64url of bytes 0..31, unpadded (43 chars) — Android's NO_WRAP|URL_SAFE form.
    private val k = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
    private fun code(name: String = "GR Mesh", key: String = k, type: String = "meshcore_community", v: Int = 1) =
        """{"type":"$type","v":$v,"name":"$name","k":"$key"}"""

    @Test
    fun aCommunityCodeParsesWithOrWithoutPadding() {
        val c = assertNotNull(CommunityCode.parse(code()))
        assertEquals("GR Mesh", c.name)
        assertContentEquals(secret, c.secret)
        assertNotNull(CommunityCode.parse(code(key = "$k=")))
        assertTrue(CommunityCode.isCommunity(code()))
    }

    @Test
    fun anythingWrongIsRefused() {
        assertNull(CommunityCode.parse(code(type = "meshcore_channel")))
        assertNull(CommunityCode.parse(code(v = 2)))
        assertNull(CommunityCode.parse(code(name = "")))
        assertNull(CommunityCode.parse(code(name = "x".repeat(40))), "longer than a radio name")
        assertNull(CommunityCode.parse(code(key = k.dropLast(4))), "not 32 bytes")
        assertNull(CommunityCode.parse(code(key = "!!!")))
        assertNull(CommunityCode.parse("not json"))
        assertNull(CommunityCode.parse("{" + " ".repeat(2000) + "}"))
    }

    @Test
    fun theDerivedKeysAreTheSharedCryptosOwn() {
        assertContentEquals(ChannelCrypto.communityPublicPsk(crypto, secret), CommunityCode.publicPsk(crypto, secret))
        assertEquals(64, CommunityCode.idHex(crypto, secret).length)
    }

    private class Radio : ChannelJoin.Radio {
        val writes = mutableListOf<Pair<Int, String>>()
        override val channels = emptyList<io.github.thatsfguy.meshcore.model.Channel>()
        override val channelsKnown = true
        override fun nextFreeIndex(): Int? = 1
        override suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean { writes += index to name; return true }
    }

    @Test
    fun aCommunityIsJoinedOnlyIfItsSecretIsKept() = kotlinx.coroutines.test.runTest {
        val refusedRadio = Radio()
        val refused = ChannelJoin(refusedRadio, crypto).joinCommunity(code()) { _, _ -> false }
        assertTrue(refused is ChannelJoin.Outcome.Refused)
        assertTrue(refusedRadio.writes.isEmpty(), "no slot spent on a community whose secret is lost")
        val radio = Radio()
        var kept: ByteArray? = null
        val out = ChannelJoin(radio, crypto).joinCommunity(code()) { _, s -> kept = s; true }
        assertEquals("Joined community \"GR Mesh\"", out.message)
        assertContentEquals(secret, kept)
        assertEquals(listOf(1 to "GR Mesh"), radio.writes)
    }
}
