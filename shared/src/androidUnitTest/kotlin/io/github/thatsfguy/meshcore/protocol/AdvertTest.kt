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

/**
 * The two optional "feature" fields in an advert's app_data.
 *
 * `docs/payloads.md` ("Node advertisement" → Appdata) puts `feature1`
 * and `feature2` — 2 bytes each, flags 0x20 and 0x40 — BETWEEN the
 * coordinates and the name. Both are reserved for future use, so
 * nothing sets them, so a parser that skips straight from the
 * coordinates to the name is correct right up until the day firmware
 * starts using them. Then every name from such a node gains two or
 * four bytes of binary at the front, and it looks like a broken mesh
 * rather than a broken client.
 *
 * Found 2026-09-22 cross-checking against docs that have said this
 * since May 2025.
 */
class AdvertFeatureFieldsTest {

    private val crypto = AndroidCryptoProvider()

    private fun parsed(flags: Int, body: ByteArray): AdvertInfo? {
        val id = MeshIdentity.generate(crypto)
        val appData = byteArrayOf(flags.toByte()) + body
        val payload = Advert.build(crypto, id.seed, 1_700_000_000L, appData)
        return Advert.parseVerified(crypto, payload)
    }

    private fun latLon(lat: Int, lon: Int): ByteArray {
        val w = BufferWriter()
        w.writeUInt32LE(lat.toLong() and 0xFFFFFFFFL)
        w.writeUInt32LE(lon.toLong() and 0xFFFFFFFFL)
        return w.toBytes()
    }

    @Test
    fun aNameAfterFeature1IsReadFromTheRightOffset() {
        // THE REGRESSION. Before the fix the name came back with two
        // bytes of feature data eaten as its first characters.
        val info = parsed(
            flags = Codes.ADV_TYPE_REPEATER or 0x20 or 0x80,
            body = byteArrayOf(0x01, 0x02) + "Sparta".encodeToByteArray(),
        )
        assertNotNull(info)
        assertEquals("Sparta", info.name)
        assertEquals(Codes.ADV_TYPE_REPEATER, info.type)
    }

    @Test
    fun bothFeatureFieldsAreSkippedInOrder() {
        val info = parsed(
            flags = Codes.ADV_TYPE_CHAT or 0x20 or 0x40 or 0x80,
            body = byteArrayOf(0x01, 0x02, 0x03, 0x04) + "Both".encodeToByteArray(),
        )
        assertNotNull(info)
        assertEquals("Both", info.name)
    }

    @Test
    fun featuresSitAfterTheCoordinatesNotBeforeThem() {
        // Order matters: lat/lon, THEN features, THEN name. Skipping in
        // the wrong order reads the coordinates as feature bytes and
        // moves the node.
        val info = parsed(
            flags = Codes.ADV_TYPE_CHAT or 0x10 or 0x40 or 0x80,
            body = latLon(43_160_040, -85_639_590) + byteArrayOf(0x09, 0x09) +
                "Placed".encodeToByteArray(),
        )
        assertNotNull(info)
        assertEquals("Placed", info.name)
        assertEquals(43.16004, info.latitude!!, 1e-5)
        assertEquals(-85.63959, info.longitude!!, 1e-5)
    }

    @Test
    fun anAdvertStillParsesWhenNoFeatureFlagsAreSet() {
        // The positive control: the ordinary advert, which is every
        // advert on the air today, must be unaffected.
        val info = parsed(
            flags = Codes.ADV_TYPE_CHAT or 0x10 or 0x80,
            body = latLon(43_160_040, -85_639_590) + "Plain".encodeToByteArray(),
        )
        assertNotNull(info)
        assertEquals("Plain", info.name)
        assertEquals(43.16004, info.latitude!!, 1e-5)
    }

    @Test
    fun aClaimedFeatureFieldWithNoBytesLeftIsRejected() {
        // Same rule the coordinates already follow: a claimed field the
        // advert does not carry is malformed, not a licence to read the
        // next field as this one.
        assertNull(parsed(flags = Codes.ADV_TYPE_CHAT or 0x20 or 0x80, body = byteArrayOf(0x01)))
        assertNull(parsed(flags = Codes.ADV_TYPE_CHAT or 0x40, body = ByteArray(0)))
    }

    @Test
    fun featureBytesAreNotLeakedIntoAnyField() {
        // They are skipped, never surfaced: this app has nothing to say
        // about reserved contents.
        val info = parsed(
            flags = Codes.ADV_TYPE_CHAT or 0x20 or 0x80,
            body = byteArrayOf(0x7F, 0x7F) + "Clean".encodeToByteArray(),
        )
        assertNotNull(info)
        assertFalse(info.name.any { it.code == 0x7F })
    }
}
