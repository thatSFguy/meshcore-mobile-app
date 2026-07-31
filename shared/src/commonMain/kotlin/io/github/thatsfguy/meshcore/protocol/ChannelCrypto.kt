package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.hexToBytes

/**
 * Channel (group) message crypto — MESHCORE_PROTOCOL.md §10.
 *
 * ⚠️ Protocol-inherent weaknesses (cannot be fixed without breaking
 * interop): AES-**ECB** and a **2-byte** truncated HMAC. Decryption
 * success is NOT authentication; present channels as *obfuscated*, never
 * as secure. The client only ever decrypts — encryption is done by the
 * radio firmware.
 */
object ChannelCrypto {

    /** The well-known "Public" channel PSK — world-readable by design. */
    val PUBLIC_CHANNEL_PSK: ByteArray get() = hexToBytes("8b3387e9c5cdea6ac9e5edbaa115cd72")

    /** One-byte channel id: SHA256(psk)[0]. Colliding channels must ALL be tried. */
    fun channelHash(crypto: CryptoProvider, psk: ByteArray): Int =
        crypto.sha256(psk)[0].toInt() and 0xFF

    /**
     * Decrypt an encrypted channel blob (`[mac x2][ciphertext…]`, the
     * bytes following the channel-hash byte of a GRP_TXT payload).
     * Returns the plaintext or null on MAC mismatch / malformed input.
     */
    fun decrypt(crypto: CryptoProvider, psk: ByteArray, encrypted: ByteArray): ByteArray? {
        // A short/corrupt PSK would silently become a zero-padded weak
        // key; refuse it instead.
        if (psk.size != Codes.CIPHER_BLOCK_SIZE) return null
        if (encrypted.size <= Codes.CIPHER_MAC_SIZE) return null
        val mac = encrypted.copyOfRange(0, Codes.CIPHER_MAC_SIZE)
        val ciphertext = encrypted.copyOfRange(Codes.CIPHER_MAC_SIZE, encrypted.size)
        if (ciphertext.isEmpty() || ciphertext.size % Codes.CIPHER_BLOCK_SIZE != 0) return null

        val expected = crypto.hmacSha256(hmacKey(psk), ciphertext)
        // Only 2 MAC bytes cross the wire — a ~1-in-65536 forgery bound,
        // inherent to the protocol. Compared without short-circuiting.
        var diff = 0
        for (i in 0 until Codes.CIPHER_MAC_SIZE) {
            diff = diff or (expected[i].toInt() xor mac[i].toInt())
        }
        if (diff != 0) return null

        return try {
            crypto.aesEcbDecrypt(aesKey(psk), ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse decrypted channel plaintext:
     * `u32 timestamp | [1] txt_type | text cstr` where text is
     * "<name>: <message>". Null when txt_type is not plain (per the
     * reference client, (txt_type>>2) != 0 frames are dropped).
     */
    fun parsePlaintext(plaintext: ByteArray): ChannelPlaintext? {
        return try {
            val r = BufferReader(plaintext)
            val timestamp = r.readUInt32LE()
            val txtType = r.readByte()
            if ((txtType shr 2) != 0) return null
            val text = r.readCString()
            val (sender, body) = ResponseParser.splitSenderText(text)
            ChannelPlaintext(timestamp, txtType, sender, body)
        } catch (_: TruncatedFrameException) {
            null
        }
    }

    /**
     * Encrypt a channel plaintext — ONLY for building test vectors; the
     * app never sends OTA channel ciphertext itself (firmware does).
     */
    fun encryptForTest(crypto: CryptoProvider, psk: ByteArray, plaintext: ByteArray): ByteArray {
        val padded = if (plaintext.size % Codes.CIPHER_BLOCK_SIZE == 0) plaintext else
            plaintext + ByteArray(Codes.CIPHER_BLOCK_SIZE - plaintext.size % Codes.CIPHER_BLOCK_SIZE)
        val ciphertext = crypto.aesEcbEncrypt(aesKey(psk), padded)
        val mac = crypto.hmacSha256(hmacKey(psk), ciphertext)
        return byteArrayOf(mac[0], mac[1]) + ciphertext
    }

    // --- PSK derivation (§10) ---

    /** Hashtag channel: SHA256("#name")[0..15]. Obfuscation only — the key
     *  is derivable from the (public) name. */
    fun hashtagPsk(crypto: CryptoProvider, hashtag: String): ByteArray {
        val name = if (hashtag.startsWith("#")) hashtag else "#$hashtag"
        return crypto.sha256(name.encodeToByteArray()).copyOfRange(0, 16)
    }

    /** Community public channel: HMAC_SHA256(K, "channel:v1:__public__")[0..15]. */
    fun communityPublicPsk(crypto: CryptoProvider, communitySecret: ByteArray): ByteArray =
        crypto.hmacSha256(communitySecret, "channel:v1:__public__".encodeToByteArray())
            .copyOfRange(0, 16)

    /** Community hashtag channel: HMAC_SHA256(K, "channel:v1:" + normalize(name))[0..15]. */
    fun communityHashtagPsk(
        crypto: CryptoProvider,
        communitySecret: ByteArray,
        hashtag: String,
    ): ByteArray =
        crypto.hmacSha256(
            communitySecret,
            ("channel:v1:" + normalizeCommunityHashtag(hashtag)).encodeToByteArray(),
        ).copyOfRange(0, 16)

    /** Community ID: SHA256("community:v1" ‖ K). */
    fun communityId(crypto: CryptoProvider, communitySecret: ByteArray): ByteArray =
        crypto.sha256("community:v1".encodeToByteArray() + communitySecret)

    /** Flood scope tag: SHA256("#region")[0..15] (CMD_SET_FLOOD_SCOPE). */
    fun floodScopeHash(crypto: CryptoProvider, region: String): ByteArray {
        val name = if (region.startsWith("#")) region else "#$region"
        return crypto.sha256(name.encodeToByteArray()).copyOfRange(0, 16)
    }

    fun normalizeCommunityHashtag(hashtag: String): String =
        hashtag.removePrefix("#").lowercase().trim()

    private fun aesKey(psk: ByteArray): ByteArray {
        val key = ByteArray(16)
        psk.copyInto(key, 0, 0, minOf(psk.size, 16))
        return key
    }

    private fun hmacKey(psk: ByteArray): ByteArray {
        val key = ByteArray(32)
        psk.copyInto(key, 0, 0, minOf(psk.size, 32))
        return key
    }
}

data class ChannelPlaintext(
    val timestamp: Long,
    val txtType: Int,
    val senderName: String,
    val text: String,
)
