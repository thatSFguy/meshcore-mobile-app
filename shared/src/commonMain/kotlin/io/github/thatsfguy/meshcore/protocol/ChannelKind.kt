package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider

/**
 * What kind of channel a slot holds, decided by its KEY.
 *
 * A MeshCore channel is its 16-byte key; the name is a label each radio
 * keeps for itself and never goes on air (the channel hash is
 * SHA256(key)[0], BaseChatMesh.cpp:875). So the kind cannot come from the
 * name. A private channel someone labelled "#test" is still private: the
 * '#' is a claim, and only a key that IS the hash of that name backs it.
 *
 *  - [Public]: the well-known public key. Anyone has it.
 *  - [Hashtag]: key = SHA256("#name")[0..15]. Anyone who types the name
 *    has it, so it is public too — just not the Public channel.
 *  - [Private]: anything else. Only people given the key have it.
 */
enum class ChannelKind { Public, Hashtag, Private }

object ChannelKinds {

    fun of(name: String, psk: ByteArray, crypto: CryptoProvider): ChannelKind {
        if (psk.contentEquals(ChannelCrypto.PUBLIC_CHANNEL_PSK)) return ChannelKind.Public
        val tag = name.trim().removePrefix("#")
        if (tag.isNotEmpty()) {
            // The label as stored, and lowercased: a slot renamed "#Test"
            // after joining "#test" still holds the #test key.
            val candidates = linkedSetOf("#$tag", "#${tag.lowercase()}")
            if (candidates.any { ChannelCrypto.hashtagPsk(crypto, it).contentEquals(psk) }) {
                return ChannelKind.Hashtag
            }
        }
        return ChannelKind.Private
    }
}
