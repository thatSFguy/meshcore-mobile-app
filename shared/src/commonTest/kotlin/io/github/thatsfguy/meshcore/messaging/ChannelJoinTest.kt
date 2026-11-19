package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import kotlinx.coroutines.test.runTest
import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelJoinTest {

    /**
     * Stand-in digest: deterministic and input-dependent, which is all
     * the join rules need. The real hashtag derivation is pinned by
     * ChannelCryptoTest against Bouncy Castle.
     */
    private class FakeCrypto : CryptoProvider {
        var counter = 0
        override fun sha256(data: ByteArray) = ByteArray(32) { (data.sum() + it).toByte() }
        override fun randomBytes(length: Int) = ByteArray(length) { (++counter).toByte() }
        override fun sha512(data: ByteArray) = no()
        override fun hmacSha256(key: ByteArray, data: ByteArray) = no()
        override fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray) = no()
        override fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray) = no()
        override fun generateEd25519Seed() = no()
        override fun ed25519PublicKey(seed: ByteArray) = no()
        override fun ed25519Sign(message: ByteArray, seed: ByteArray) = no()
        override fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray) = false
        override fun aesGcmSeal(key32: ByteArray, nonce12: ByteArray, plaintext: ByteArray, aad: ByteArray) = no()
        override fun aesGcmOpen(key32: ByteArray, nonce12: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray? = no()
        private fun no(): Nothing = throw UnsupportedOperationException()
        private fun ByteArray.sum() = fold(0) { a, b -> a * 31 + b }
    }

    private class FakeRadio(
        override var channels: List<Channel> = listOf(Channel(0, "Public", ChannelCrypto.PUBLIC_CHANNEL_PSK)),
        override var channelsKnown: Boolean = true,
        var slots: Int = 8,
        var accept: Boolean = true,
    ) : ChannelJoin.Radio {
        val writes = mutableListOf<Triple<Int, String, ByteArray>>()
        override fun nextFreeIndex(): Int? {
            if (!channelsKnown) return null
            val used = channels.map { it.index }.toSet()
            return (0 until slots).firstOrNull { it !in used }
        }
        override suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean {
            writes += Triple(index, name, psk)
            if (!accept) return false
            channels = (channels.filter { it.index != index } + Channel(index, name, psk))
                .filterNot { it.name.isEmpty() && psk.all { b -> b.toInt() == 0 } }
            return true
        }
    }

    private val crypto = FakeCrypto()

    @Test
    fun aHashtagIsNormalisedAndWrittenUnderItsDerivedKey() = runTest {
        val radio = FakeRadio()
        val out = ChannelJoin(radio, crypto).joinHashtag("  #Rescue-Ops ")
        assertIs<ChannelJoin.Outcome.Added>(out)
        val (idx, name, psk) = radio.writes.single()
        assertEquals(1, idx, "the first free slot")
        assertEquals("#rescue-ops", name)
        assertContentEquals(ChannelCrypto.hashtagPsk(crypto, "#rescue-ops"), psk)
    }

    @Test
    fun joiningAKeyAlreadyHeldSpendsNoSlot() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        join.joinHashtag("rescue")
        val again = join.joinHashtag("#RESCUE")
        assertIs<ChannelJoin.Outcome.AlreadyHeld>(again)
        assertEquals("Already in \"#rescue\"", again.message)
        assertEquals(1, radio.writes.size)
    }

    @Test
    fun theSameKeyUnderAnotherNameSaysWhichChannelItIs() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        val hex = "00112233445566778899aabbccddeeff"
        join.joinPrivate("Team", hex)
        val again = join.joinPrivate("Crew", hex.uppercase())
        assertEquals("Already in this channel — you call it \"Team\"", again.message)
    }

    @Test
    fun aFullRadioAndAnUnreadRadioAreDifferentAnswers() = runTest {
        val full = FakeRadio(slots = 1)
        assertEquals("No free channel slots on the radio", ChannelJoin(full, crypto).joinHashtag("x").message)
        val unread = FakeRadio(channelsKnown = false)
        assertEquals(
            "Still reading the radio's channels — try again in a moment",
            ChannelJoin(unread, crypto).joinHashtag("x").message,
        )
        assertTrue(full.writes.isEmpty() && unread.writes.isEmpty())
    }

    @Test
    fun badInputIsRefusedBeforeAnythingIsWritten() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        for (out in listOf(
            join.joinHashtag("no spaces"),
            join.joinHashtag(""),
            join.createPrivate("#looks-like-a-hashtag"),
            join.joinPrivate("Team", "00112233"),
            join.joinPrivate("Team", "zz112233445566778899aabbccddeeff"),
            join.joinPrivate("Team", "00000000000000000000000000000000"),
        )) assertIs<ChannelJoin.Outcome.Refused>(out)
        assertTrue(radio.writes.isEmpty())
    }

    @Test
    fun aNewPrivateChannelGetsAFreshRandomKey() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        join.createPrivate("Team A")
        join.createPrivate("Team B")
        val (a, b) = radio.writes.map { it.third }
        assertEquals(16, a.size)
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun aRefusedWriteIsReported() = runTest {
        val out = ChannelJoin(FakeRadio(accept = false), crypto).joinHashtag("x")
        assertEquals("Channel write failed", out.message)
    }

    @Test
    fun leavingClearsTheSlotWithAZeroKey() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        join.joinHashtag("rescue")
        assertIs<ChannelJoin.Outcome.Added>(join.leave(1))
        val (idx, name, psk) = radio.writes.last()
        assertEquals(1, idx); assertEquals("", name); assertContentEquals(ByteArray(16), psk)
        assertIs<ChannelJoin.Outcome.Refused>(join.leave(5))
    }

    @Test
    fun aSharedLinkRoundTripsIntoTheSameChannel() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        val key = "00112233445566778899aabbccddeeff"
        val link = io.github.thatsfguy.meshcore.protocol.ShareUri.encodeChannel("Team", key)
        val out = join.joinShared(link)
        assertIs<ChannelJoin.Outcome.Added>(out)
        assertEquals("Team", radio.writes.single().second)
        assertEquals(key, radio.writes.single().third.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
        // And the link we would hand out for it is the one we read.
        assertEquals(link, join.shareLink(radio.channels.first { it.name == "Team" }))
    }

    @Test
    fun aScopedLinkSaysItWasJoinedUnscoped() = runTest {
        val radio = FakeRadio()
        val link = io.github.thatsfguy.meshcore.protocol.ShareUri.encodeChannel(
            "Team", "00112233445566778899aabbccddeeff", regionScope = "grand-rapids",
        )
        val out = ChannelJoin(radio, crypto).joinShared(link)
        assertIs<ChannelJoin.Outcome.Added>(out)
        assertTrue("floods unscoped" in out.message, out.message) // no scope store: said out loud
        assertTrue("grand-rapids" in out.message, out.message)
    }

    @Test
    fun aLinkThatIsNotAChannelIsRefusedByName() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        val contact = io.github.thatsfguy.meshcore.protocol.ShareUri.encodeContact("Al", "aa".repeat(32), 1)
        assertEquals("That is a contact, not a channel.", join.joinShared(contact).message)
        assertEquals("That is not a MeshCore link.", join.joinShared("https://example.com").message)
        assertIs<ChannelJoin.Outcome.Refused>(join.joinShared("meshcore://channel?name=x&secret=zz"))
        assertTrue(radio.writes.isEmpty())
    }

    private class Scopes(val current: Map<Int, String> = emptyMap(), val known: List<String> = emptyList()) : ChannelScopeStore {
        val applied = mutableListOf<Triple<Int, String, Boolean>>()
        override fun channelRegion(index: Int) = current[index]
        override fun knownRegions() = known
        override fun applyRegion(index: Int, region: String, newRegion: Boolean) { applied += Triple(index, region, newRegion) }
    }

    private val scopedLink = io.github.thatsfguy.meshcore.protocol.ShareUri.encodeChannel(
        "Team", "00112233445566778899aabbccddeeff", regionScope = "grand-rapids",
    )

    @Test
    fun withAScopeStoreTheLinksRegionIsAppliedToTheSlot() = runTest {
        val radio = FakeRadio()
        val scopes = Scopes()
        val join = ChannelJoin(radio, crypto).also { it.scopes = scopes }
        val out = assertIs<ChannelJoin.Outcome.Added>(join.joinShared(scopedLink))
        assertEquals(listOf(Triple(out.index, "grand-rapids", true)), scopes.applied)
        assertTrue(out.message.endsWith(", scoped to #grand-rapids — new region added"), out.message)
    }

    @Test
    fun aRegionThePersonChoseIsNotOverwrittenByALink() = runTest {
        val radio = FakeRadio()
        val first = ChannelJoin(radio, crypto).joinShared(scopedLink) as ChannelJoin.Outcome.Added
        val scopes = Scopes(current = mapOf(first.index to "mi"), known = listOf("mi", "grand-rapids"))
        val again = ChannelJoin(radio, crypto).also { it.scopes = scopes }.joinShared(scopedLink)
        assertIs<ChannelJoin.Outcome.AlreadyHeld>(again)
        assertTrue(scopes.applied.isEmpty(), "a QR is not authority to undo a routing choice")
        assertTrue("#mi" in again.message, again.message)
    }

    @Test
    fun anEditWritesTheSlotAndRefusesABadKeyOrName() = runTest {
        val radio = FakeRadio()
        val join = ChannelJoin(radio, crypto)
        // A captured 16-byte PSK, typed with a space in it.
        assertEquals("Channel updated", join.edit(1, " Ops ", "8b3387e9c5cdea6a c9e5edbaa115cd72"))
        val (idx, name, psk) = radio.writes.single()
        assertEquals(1, idx)
        assertEquals("Ops", name)
        assertEquals("8b3387e9c5cdea6ac9e5edbaa115cd72", psk.toHex())
        assertEquals("The key must be 32 hex characters.", join.edit(1, "Ops", "8b3387e9c5cdea6a"))
        assertEquals("The key must be 32 hex characters.", join.edit(1, "Ops", "zz".repeat(16)))
        assertTrue(join.edit(1, "", "00".repeat(16)).startsWith("The name must be"))
        assertTrue(join.edit(1, "x".repeat(40), "00".repeat(16)).startsWith("The name must be"))
        assertEquals(1, radio.writes.size, "nothing refused reached the radio")
        radio.accept = false
        assertEquals("Channel write failed", join.edit(1, "Ops", "00".repeat(16)))
    }
}
