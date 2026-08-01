package io.github.thatsfguy.meshcore.platform

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android implementation of [CryptoProvider]. JCA for SHA-2, HMAC and
 * AES-ECB; Bouncy Castle for Ed25519 (same split as the sibling
 * reticulum-mobile-app's AndroidCryptoProvider).
 *
 * AES-ECB (no padding) exists solely because the MeshCore channel
 * protocol mandates it — see ChannelCrypto's warning.
 */
class AndroidCryptoProvider : CryptoProvider {

    private val random = SecureRandom()

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    override fun sha512(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key16.size == 16) { "AES-128 key must be 16 bytes, got ${key16.size}" }
        require(ciphertext.size % 16 == 0) { "ECB ciphertext must be block-aligned" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key16, "AES"))
        return cipher.doFinal(ciphertext)
    }

    override fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray): ByteArray {
        require(key16.size == 16) { "AES-128 key must be 16 bytes, got ${key16.size}" }
        require(plaintext.size % 16 == 0) { "ECB plaintext must be block-aligned" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"))
        return cipher.doFinal(plaintext)
    }

    override fun generateEd25519Seed(): ByteArray =
        Ed25519PrivateKeyParameters(random).encoded

    override fun ed25519PublicKey(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    override fun ed25519Sign(message: ByteArray, seed: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        random.nextBytes(out)
        return out
    }

    override fun aesGcmSeal(
        key32: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key32.size == 32) { "AES-256 key must be 32 bytes, got ${key32.size}" }
        require(nonce12.size == GCM_NONCE_LEN) { "GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key32, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce12),
        )
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    override fun aesGcmOpen(
        key32: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray? {
        if (key32.size != 32 || nonce12.size != GCM_NONCE_LEN) return null
        if (ciphertextAndTag.size < GCM_TAG_BITS / 8) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key32, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, nonce12),
            )
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            cipher.doFinal(ciphertextAndTag)
            // A bad tag throws AEADBadTagException; a wrong passphrase and
            // a tampered file are the same answer to the caller — null.
        }.getOrNull()
    }

    private companion object {
        const val GCM_NONCE_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}

/** Convenience factory. */
fun androidCryptoProvider(): CryptoProvider = AndroidCryptoProvider()
