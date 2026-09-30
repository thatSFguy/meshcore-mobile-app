package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.ChannelKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelSetupTest {

    private fun ok(r: ChannelSetup.Result<String>) = assertIs<ChannelSetup.Result.Ok<String>>(r).value

    // --- hashtag ------------------------------------------------------------

    @Test
    fun hashtagNamesAreStoredWithTheirHashAndLowercased() {
        // The key is the hash of the exact text, so "#Test" and "#test" are
        // two channels; lowercasing keeps people in the same one.
        assertEquals("#test", ok(ChannelSetup.hashtag("test")))
        assertEquals("#test", ok(ChannelSetup.hashtag("#Test")))
        assertEquals("#grand-rapids", ok(ChannelSetup.hashtag("  grand-rapids ")))
    }

    @Test
    fun hashtagRefusesWhatOthersCouldNotType() {
        for (bad in listOf("", "#", "two words", "under_score", "café", "a.b", "emoji😀")) {
            assertIs<ChannelSetup.Result.Problem>(ChannelSetup.hashtag(bad), bad)
        }
    }

    @Test
    fun hashtagFitsTheRadiosNameField() {
        // 31 bytes including the '#' (32-byte field, NUL-terminated).
        assertEquals(31, ok(ChannelSetup.hashtag("a".repeat(30))).length)
        assertIs<ChannelSetup.Result.Problem>(ChannelSetup.hashtag("a".repeat(31)))
    }

    // --- private name -------------------------------------------------------

    @Test
    fun aPrivateNameCannotStartWithHash() {
        // It would read as a hashtag channel anyone can join.
        assertIs<ChannelSetup.Result.Problem>(ChannelSetup.privateName("#friends"))
        assertEquals("Friends & family", ok(ChannelSetup.privateName("  Friends & family ")))
    }

    @Test
    fun aPrivateNameIsMeasuredInBytes() {
        // Four-byte emoji: 7 of them are 28 bytes, 8 are 32.
        assertEquals("😀".repeat(7), ok(ChannelSetup.privateName("😀".repeat(7))))
        assertIs<ChannelSetup.Result.Problem>(ChannelSetup.privateName("😀".repeat(8)))
        assertIs<ChannelSetup.Result.Problem>(ChannelSetup.privateName("   "))
    }

    @Test
    fun controlCharactersAreDroppedFromAPrivateName() {
        assertEquals("ab", ok(ChannelSetup.privateName("a\u0007b")))
    }

    // --- private key --------------------------------------------------------

    @Test
    fun aKeyIs32HexCharactersSpacesAllowed() {
        val key = assertIs<ChannelSetup.Result.Ok<ByteArray>>(
            ChannelSetup.privateKey("0123 4567 89AB CDEF 0123 4567 89ab cdef"),
        ).value
        assertContentEquals(
            byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
                0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()),
            key,
        )
    }

    @Test
    fun badKeysAreRefusedWithAReason() {
        for (bad in listOf("", "0".repeat(31), "0".repeat(33), "g".repeat(32), "0".repeat(32), "0x" + "1".repeat(30))) {
            assertIs<ChannelSetup.Result.Problem>(ChannelSetup.privateKey(bad), bad)
        }
        val zeros = assertIs<ChannelSetup.Result.Problem>(ChannelSetup.privateKey("0".repeat(32)))
        assertTrue(zeros.message.contains("zeros"))
    }

    // --- subtitle -----------------------------------------------------------

    @Test
    fun theSubtitleSaysWhoCanReadIt() {
        assertEquals("Public · anyone can read it", channelSubtitle(ChannelKind.Public, null))
        assertEquals("Hashtag · anyone can read it · #mi", channelSubtitle(ChannelKind.Hashtag, "mi"))
        assertEquals("Private · not secure", channelSubtitle(ChannelKind.Private, null))
        assertEquals("Obfuscated, not secure", channelSubtitle(null, null))
        // Never "secure" on its own, and short enough for the app bar
        // (about 30 characters fit beside the radio chip on a 384dp phone).
        for (k in listOf(ChannelKind.Public, ChannelKind.Hashtag, ChannelKind.Private, null)) {
            val s = channelSubtitle(k, null)
            assertTrue(!s.contains("secure") || s.contains("not secure"), s)
            assertTrue(s.length <= 30, "'$s' is ${s.length} characters")
        }
    }
}
