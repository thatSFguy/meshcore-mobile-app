package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.engine.MeshCoreEngine
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.model.ChannelList
import io.github.thatsfguy.meshcore.presentation.ChannelSetup
import io.github.thatsfguy.meshcore.protocol.ChannelCrypto
import io.github.thatsfguy.meshcore.protocol.ShareUri
import io.github.thatsfguy.meshcore.util.toHex

/**
 * Adding and removing channels, with the rules Android's ViewModel
 * applies (`addChannelWithKey`) moved where both platforms share them:
 *
 *  - **the key is the channel**: joining a key already held spends no
 *    slot and says which channel it already is, by its local name;
 *  - "no free slot" and "haven't read the slots yet" are different
 *    answers, because only one of them is fixed by trying again;
 *  - input is validated by [ChannelSetup] before anything is written.
 *
 * Region scopes are not handled: iOS has no region UI yet.
 */
class ChannelJoin(private val radio: Radio, private val crypto: CryptoProvider) {

    interface Radio {
        val channels: List<Channel>
        /** False until the radio's slots have been read at least once. */
        val channelsKnown: Boolean
        fun nextFreeIndex(): Int?
        suspend fun setChannel(index: Int, name: String, psk: ByteArray): Boolean
    }

    sealed class Outcome {
        abstract val message: String

        data class Added(val index: Int, override val message: String) : Outcome()
        data class AlreadyHeld(val index: Int, override val message: String) : Outcome()
        data class Refused(override val message: String) : Outcome()
    }

    /** `#name` channel: key derived from the public name (obfuscation only). */
    suspend fun joinHashtag(input: String): Outcome =
        when (val tag = ChannelSetup.hashtag(input)) {
            is ChannelSetup.Result.Problem -> Outcome.Refused(tag.message)
            is ChannelSetup.Result.Ok -> add(tag.value, ChannelCrypto.hashtagPsk(crypto, tag.value))
        }

    /** A new private channel under a fresh random key. */
    suspend fun createPrivate(nameInput: String): Outcome =
        when (val name = ChannelSetup.privateName(nameInput)) {
            is ChannelSetup.Result.Problem -> Outcome.Refused(name.message)
            is ChannelSetup.Result.Ok -> add(name.value, crypto.randomBytes(16))
        }

    /** A private channel someone shared: their name, their 32-hex key. */
    suspend fun joinPrivate(nameInput: String, keyInput: String): Outcome {
        val name = when (val n = ChannelSetup.privateName(nameInput)) {
            is ChannelSetup.Result.Problem -> return Outcome.Refused(n.message)
            is ChannelSetup.Result.Ok -> n.value
        }
        val key = when (val k = ChannelSetup.privateKey(keyInput)) {
            is ChannelSetup.Result.Problem -> return Outcome.Refused(k.message)
            is ChannelSetup.Result.Ok -> k.value
        }
        return add(name, key)
    }

    /**
     * Join from a `meshcore://channel?…` link someone shared (pasted, or
     * opened from another app). A link that is not a channel share is
     * refused with a sentence saying what it is instead.
     *
     * A share that names a flood region is joined UNSCOPED on iOS — there
     * is no region support yet — and the message says so out loud: an
     * unscoped channel floods wider than the person sharing intended.
     */
    suspend fun joinShared(link: String): Outcome {
        val share = when (val d = ShareUri.decode(link)) {
            is ShareUri.Decoded.ChannelShare -> d
            is ShareUri.Decoded.Contact, is ShareUri.Decoded.Advert ->
                return Outcome.Refused("That is a contact, not a channel.")
            is ShareUri.Decoded.RadioConfig ->
                return Outcome.Refused("That is a radio-settings code, not a channel.")
            ShareUri.Decoded.NotAContactCode ->
                return Outcome.Refused("That is not a MeshCore link.")
            else -> return Outcome.Refused("That link could not be read.")
        }
        val key = when (val k = ChannelSetup.privateKey(share.pskHex)) {
            is ChannelSetup.Result.Problem -> return Outcome.Refused(k.message)
            is ChannelSetup.Result.Ok -> k.value
        }
        // A shared hashtag channel keeps its #name; anything else is a
        // private channel under the name it was shared with.
        val name = if (share.name.startsWith("#")) {
            when (val t = ChannelSetup.hashtag(share.name)) {
                is ChannelSetup.Result.Problem -> return Outcome.Refused(t.message)
                is ChannelSetup.Result.Ok -> t.value
            }
        } else {
            when (val n = ChannelSetup.privateName(share.name)) {
                is ChannelSetup.Result.Problem -> return Outcome.Refused(n.message)
                is ChannelSetup.Result.Ok -> n.value
            }
        }
        val out = add(name, key)
        val scope = share.rawRegionScope.ifBlank { share.regionScope }
        if (scope.isBlank()) return out
        val note = " — this link asks for region #$scope, which iOS cannot apply yet, so it floods unscoped"
        return when (out) {
            is Outcome.Added -> out.copy(message = out.message + note)
            is Outcome.AlreadyHeld -> out.copy(message = out.message + note)
            is Outcome.Refused -> out
        }
    }

    /** The link that shares [channel], for a person you trust with every message on it. */
    fun shareLink(channel: Channel): String = ShareUri.encodeChannel(channel.name, channel.pskHex)

    /** Clear a slot on the radio. */
    suspend fun leave(index: Int): Outcome {
        val ch = radio.channels.firstOrNull { it.index == index }
            ?: return Outcome.Refused("That channel is no longer on the radio.")
        val ok = runCatching { radio.setChannel(index, "", ByteArray(16)) }.getOrDefault(false)
        return if (ok) Outcome.Added(index, "Left ${ch.name.ifBlank { "channel $index" }}")
        else Outcome.Refused("The radio did not clear the channel.")
    }

    private suspend fun add(name: String, psk: ByteArray): Outcome {
        ChannelList.findByPsk(radio.channels, psk.toHex())?.let { existing ->
            val label = if (existing.name.equals(name, ignoreCase = true)) {
                "\"${existing.name}\""
            } else {
                "this channel — you call it \"${existing.name}\""
            }
            return Outcome.AlreadyHeld(existing.index, "Already in $label")
        }
        val idx = radio.nextFreeIndex() ?: return Outcome.Refused(
            if (!radio.channelsKnown) {
                "Still reading the radio's channels — try again in a moment"
            } else {
                "No free channel slots on the radio"
            },
        )
        val ok = runCatching { radio.setChannel(idx, name, psk) }.getOrDefault(false)
        return if (ok) Outcome.Added(idx, "Channel added") else Outcome.Refused("Channel write failed")
    }

    companion object {
        /** Over the real engine. */
        fun over(engine: MeshCoreEngine, crypto: CryptoProvider): ChannelJoin = ChannelJoin(
            object : Radio {
                override val channels: List<Channel> get() = engine.channels.value
                override val channelsKnown: Boolean get() = engine.channelsKnown
                override fun nextFreeIndex(): Int? = engine.nextFreeChannelIndex()
                override suspend fun setChannel(index: Int, name: String, psk: ByteArray) =
                    engine.setChannel(index, name, psk)
            },
            crypto,
        )
    }
}
