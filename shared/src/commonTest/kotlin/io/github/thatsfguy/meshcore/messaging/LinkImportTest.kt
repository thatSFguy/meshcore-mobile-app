package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.ShareUri
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkImportTest {

    private val me = "11".repeat(32)
    private val al = "aa".repeat(32)
    private val bo = "bb".repeat(32)

    private class Radio(
        override val selfKeyHex: String?,
        val held: Map<String, String> = emptyMap(),
        var verifies: ShareUri.Decoded.Contact? = null,
    ) : LinkImport.Radio {
        val added = mutableListOf<Triple<String, String, Int>>()
        override fun heldContactName(keyHex: String) = held[keyHex]
        override fun cardFromAdvert(blob: ByteArray) = verifies
        override suspend fun addCard(keyHex: String, name: String, type: Int): Boolean {
            added += Triple(keyHex, name, type); return true
        }
    }

    private object NoChannels : ChannelJoin.Radio {
        override val channels = emptyList<Channel>()
        override val channelsKnown = true
        override fun nextFreeIndex(): Int? = 1
        override suspend fun setChannel(index: Int, name: String, psk: ByteArray) = true
    }

    private object NoCrypto : CryptoProvider {
        override fun sha256(data: ByteArray) = ByteArray(32)
        override fun randomBytes(length: Int) = ByteArray(length)
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
    }

    private object NoRetune : RadioRetune.Radio {
        val calls = mutableListOf<String>()
        override suspend fun setRadioParams(freqKhz: Long, bwHz: Long, sf: Int, cr: Int): Boolean { calls += "params"; return true }
        override suspend fun setTxPower(dbm: Int): Boolean { calls += "power"; return true }
        override suspend fun setPathHashMode(mode: Int): Boolean { calls += "width $mode"; return true }
        override suspend fun setFloodScope(region: String?): Boolean { calls += "scope $region"; return true }
    }

    private fun import(radio: Radio, retune: RadioRetune = RadioRetune(NoRetune)) =
        LinkImport(radio, ChannelJoin(NoChannels, NoCrypto), retune)

    @Test
    fun aContactCardIsPreviewedAsUnsignedThenAddedOnConfirm() = runTest {
        val radio = Radio(me)
        val li = import(radio)
        val p = li.preview(ShareUri.encodeContact("Al", al, Codes.ADV_TYPE_CHAT))
        assertIs<LinkImport.Preview.Contact>(p)
        assertEquals("Add contact Al", p.title)
        assertTrue("anyone can claim any name" in p.detail, p.detail)
        assertTrue(radio.added.isEmpty(), "a preview writes nothing")
        assertEquals("Added Al", li.apply(p))
        assertEquals(Triple(al, "Al", Codes.ADV_TYPE_CHAT), radio.added.single())
    }

    @Test
    fun aRepeaterCardSaysWhatItIs() {
        val p = import(Radio(me)).preview(ShareUri.encodeContact("Hill", al, Codes.ADV_TYPE_REPEATER))
        assertEquals("Add repeater Hill", p.title)
    }

    @Test
    fun aVerifiedAdvertSaysTheNameWasSigned() {
        val radio = Radio(me, verifies = ShareUri.Decoded.Contact("Bo", bo, Codes.ADV_TYPE_CHAT, signed = true))
        val p = import(radio).preview(ShareUri.encodeAdvert(ByteArray(200)))
        assertIs<LinkImport.Preview.Contact>(p)
        assertTrue("signed by whoever holds this key" in p.detail, p.detail)
    }

    @Test
    fun anAdvertThatDoesNotVerifyIsRefusedNotDowngraded() {
        val p = import(Radio(me, verifies = null)).preview(ShareUri.encodeAdvert(ByteArray(200)))
        assertIs<LinkImport.Preview.Unusable>(p)
        assertFalse(p.canApply)
    }

    @Test
    fun ourOwnKeyAndAContactWeHoldAreNotOffered() {
        val li = import(Radio(me, held = mapOf(al to "Alfred")))
        assertEquals("That's you", li.preview(ShareUri.encodeContact("Me", me.uppercase(), 1)).title)
        val held = li.preview(ShareUri.encodeContact("Al", al, 1))
        assertIs<LinkImport.Preview.Unusable>(held)
        assertEquals("You already have this node — you call it \"Alfred\".", held.detail)
    }

    @Test
    fun aHostileNameIsSanitisedBeforeItIsShownOrStored() = runTest {
        val radio = Radio(me)
        val li = import(radio)
        val p = li.preview("meshcore://contact/add?name=Al%0AFake%20UI%00&public_key=$al&type=1")
        assertIs<LinkImport.Preview.Contact>(p)
        assertFalse(p.title.any { it == '\n' || it == '\u0000' }, p.title)
        li.apply(p)
        assertFalse(radio.added.single().second.any { it.isISOControl() })
    }

    @Test
    fun aChannelLinkIsPreviewedWithItsWarning() = runTest {
        val li = import(Radio(me))
        val p = li.preview(ShareUri.encodeChannel("Team", "00112233445566778899aabbccddeeff"))
        assertIs<LinkImport.Preview.Channel>(p)
        assertTrue("read everything" in p.detail)
        assertEquals("Channel added", li.apply(p))
    }

    @Test
    fun otherLinksExplainThemselvesAndApplyNothing() = runTest {
        val radio = Radio(me)
        val li = import(radio)
        for (link in listOf("https://example.com", "meshcore://contact/add?public_key=zz", "meshcore://")) {
            val p = li.preview(link)
            assertFalse(p.canApply, link)
            li.apply(p)
        }
        assertTrue(radio.added.isEmpty())
    }

    // --- settings codes (iOS Phase 3) --------------------------------------

    @Test
    fun aSettingsCodeIsOfferedWithItsWarningsAndAppliedOnlyAsTicked() = runTest {
        val link = io.github.thatsfguy.meshcore.presentation.RadioShare.uriFor(
            910_525, 62_500, 7, 5, 2, "bayarea", "GR Mesh",
        )!!
        val p = assertIs<LinkImport.Preview.Radio>(import(Radio(me)).preview(link))
        assertTrue(p.canApply)
        assertTrue("910.525" in p.detail, p.detail)
        assertTrue(p.warnings.first().startsWith("Anyone can make one of these codes"))
        assertEquals(listOf("bayarea"), p.regionChoices)
        assertEquals("bayarea", p.initialRegion)
        assertTrue(p.regionStartsTicked)

        NoRetune.calls.clear()
        val unticked = import(Radio(me)).applyRadio(p, null)
        assertEquals("Applied GR Mesh", unticked)
        assertEquals(listOf("params", "width 1"), NoRetune.calls, "never TX power, never an unticked region")

        NoRetune.calls.clear()
        assertEquals("Applied GR Mesh · region #bayarea", import(Radio(me)).applyRadio(p, "bayarea"))
        assertEquals("scope bayarea", NoRetune.calls.last())
    }

    @Test
    fun aSettingsCodeWithoutARegionStartsUnticked() = runTest {
        val link = io.github.thatsfguy.meshcore.presentation.RadioShare.uriFor(
            910_525, 62_500, 7, 5, 2, null, null,
        )!!
        val p = assertIs<LinkImport.Preview.Radio>(import(Radio(me)).preview(link))
        assertFalse(p.regionStartsTicked)
        assertEquals(emptyList(), p.regionChoices)
        assertTrue(p.detail.startsWith("(unnamed mesh)"))
    }
}
