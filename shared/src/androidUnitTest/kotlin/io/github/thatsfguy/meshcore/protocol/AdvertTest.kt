package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvertTest {

    private val crypto = AndroidCryptoProvider()

    @Test
    fun advertSignatureRoundTrip() {
        val id = MeshIdentity.generate(crypto)
        val appData = Advert.buildAppData(
            type = Codes.ADV_TYPE_CHAT, name = "TestNode", lat = 37.0, lon = -122.0,
        )
        val payload = Advert.build(crypto, id.seed, timestamp = 1_700_000_000L, appData = appData)

        assertTrue(Advert.verifySignature(crypto, payload))
        val info = Advert.parseVerified(crypto, payload)
        assertNotNull(info)
        assertContentEquals(id.publicKey, info.publicKey)
        assertEquals("TestNode", info.name)
        assertEquals(Codes.ADV_TYPE_CHAT, info.type)
        assertEquals(37.0, info.latitude!!, 1e-5)
        assertEquals(-122.0, info.longitude!!, 1e-5)
        assertEquals(1_700_000_000L, info.timestamp)
    }

    @Test
    fun tamperedNameFailsVerification() {
        val id = MeshIdentity.generate(crypto)
        val payload = Advert.build(
            crypto, id.seed, 1L,
            Advert.buildAppData(Codes.ADV_TYPE_CHAT, "RealName", null, null),
        )
        // Flip a byte inside the (signed) name region.
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        assertFalse(Advert.verifySignature(crypto, payload))
        assertNull(Advert.parseVerified(crypto, payload))
    }

    @Test
    fun tamperedLocationFailsVerification() {
        val id = MeshIdentity.generate(crypto)
        val payload = Advert.build(
            crypto, id.seed, 1L,
            Advert.buildAppData(Codes.ADV_TYPE_REPEATER, "R", 10.0, 20.0),
        )
        payload[101] = (payload[101].toInt() xor 0x40).toByte() // inside lat
        assertFalse(Advert.verifySignature(crypto, payload))
    }

    @Test
    fun forgedKeyFailsVerification() {
        // Sign with one key, claim another — must not verify.
        val real = MeshIdentity.generate(crypto)
        val imposter = MeshIdentity.generate(crypto)
        val payload = Advert.build(
            crypto, real.seed, 1L,
            Advert.buildAppData(Codes.ADV_TYPE_CHAT, "X", null, null),
        )
        imposter.publicKey.copyInto(payload, 0)
        assertFalse(Advert.verifySignature(crypto, payload))
    }

    @Test
    fun shortPayloadNeverVerifiesOrThrows() {
        for (len in 0 until 100) {
            assertFalse(Advert.verifySignature(crypto, ByteArray(len)))
        }
        assertNull(Advert.parse(ByteArray(10)))
    }

    @Test
    fun nameOnlyAdvertHasNoLocation() {
        val id = MeshIdentity.generate(crypto)
        val payload = Advert.build(
            crypto, id.seed, 2L,
            Advert.buildAppData(Codes.ADV_TYPE_ROOM, "RoomSrv", null, null),
        )
        val info = Advert.parseVerified(crypto, payload)!!
        assertEquals("RoomSrv", info.name)
        assertNull(info.latitude)
        assertEquals(Codes.ADV_TYPE_ROOM, info.type)
    }

    @Test
    fun zeroLocationTreatedAsAbsent() {
        val id = MeshIdentity.generate(crypto)
        val payload = Advert.build(
            crypto, id.seed, 2L,
            Advert.buildAppData(Codes.ADV_TYPE_CHAT, "Z", 0.0, 0.0),
        )
        val info = Advert.parseVerified(crypto, payload)!!
        assertNull(info.latitude)
        assertNull(info.longitude)
    }
}
