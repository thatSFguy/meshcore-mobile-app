package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A channel's kind comes from its KEY, never its label. Ground truth
 * computed outside this codebase: SHA256("#test")[0..15].
 */
class ChannelKindTest {

    private val crypto = AndroidCryptoProvider()
    private val testKey = hexToBytes("9cd8fcf22a47333b591d96a2b848b73f")
    private val randomKey = hexToBytes("0123456789abcdef0123456789abcdef")

    @Test
    fun thePublicKeyIsPublicWhateverItIsCalled() {
        for (name in listOf("Public", "whatever", "#public", "")) {
            assertEquals(ChannelKind.Public, ChannelKinds.of(name, ChannelCrypto.PUBLIC_CHANNEL_PSK, crypto), name)
        }
    }

    @Test
    fun aHashtagKeyUnderItsNameIsHashtag() {
        // Positive control: the classifier must say Hashtag when it is one,
        // or every other assertion here passes by saying Private always.
        assertEquals(ChannelKind.Hashtag, ChannelKinds.of("#test", testKey, crypto))
    }

    @Test
    fun aRenamedHashtagSlotIsStillHashtag() {
        // The key is the channel. A slot relabelled "#Test" or "test"
        // after joining #test still holds the #test key.
        assertEquals(ChannelKind.Hashtag, ChannelKinds.of("#Test", testKey, crypto))
        assertEquals(ChannelKind.Hashtag, ChannelKinds.of("test", testKey, crypto))
    }

    @Test
    fun aHashLabelOnAPrivateKeyIsPrivate() {
        // The case this exists for: '#' in the label is a claim, and a key
        // that isn't the hash of that name doesn't back it.
        assertEquals(ChannelKind.Private, ChannelKinds.of("#test", randomKey, crypto))
        assertEquals(ChannelKind.Private, ChannelKinds.of("#", randomKey, crypto))
    }

    @Test
    fun anythingElseIsPrivate() {
        assertEquals(ChannelKind.Private, ChannelKinds.of("Channel 1", randomKey, crypto))
        assertEquals(ChannelKind.Private, ChannelKinds.of("", randomKey, crypto))
        // A #test key under a different name isn't recognised as #test —
        // the classifier doesn't search names it wasn't given.
        assertEquals(ChannelKind.Private, ChannelKinds.of("#other", testKey, crypto))
    }
}
