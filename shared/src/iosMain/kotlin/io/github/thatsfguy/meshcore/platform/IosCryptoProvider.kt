package io.github.thatsfguy.meshcore.platform

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA512
import platform.CoreCrypto.CC_SHA512_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS implementation of [CryptoProvider].
 *
 * Phase status (mirrors reticulum-mobile-app's staged iOS bring-up):
 * SHA-2 / HMAC / AES-ECB / randomBytes are real (CommonCrypto +
 * SecRandom). Ed25519 needs CryptoKit, which is Swift-only — the
 * sibling repo bridges it via a small Swift static library
 * (iosCryptoBridge); until that lands here, the Ed25519 members throw.
 * Android is unaffected.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCryptoProvider : CryptoProvider {

    override fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { outPin ->
            if (data.isEmpty()) {
                CC_SHA256(null, 0u, outPin.addressOf(0).reinterpretUByte())
            } else {
                data.usePinned { dataPin ->
                    CC_SHA256(dataPin.addressOf(0), data.size.convert(), outPin.addressOf(0).reinterpretUByte())
                }
            }
        }
        return out
    }

    override fun sha512(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA512_DIGEST_LENGTH)
        out.usePinned { outPin ->
            if (data.isEmpty()) {
                CC_SHA512(null, 0u, outPin.addressOf(0).reinterpretUByte())
            } else {
                data.usePinned { dataPin ->
                    CC_SHA512(dataPin.addressOf(0), data.size.convert(), outPin.addressOf(0).reinterpretUByte())
                }
            }
        }
        return out
    }

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        out.usePinned { outPin ->
            key.usePinned { keyPin ->
                if (data.isEmpty()) {
                    CCHmac(
                        kCCHmacAlgSHA256, keyPin.addressOf(0), key.size.convert(),
                        null, 0u, outPin.addressOf(0),
                    )
                } else {
                    data.usePinned { dataPin ->
                        CCHmac(
                            kCCHmacAlgSHA256, keyPin.addressOf(0), key.size.convert(),
                            dataPin.addressOf(0), data.size.convert(), outPin.addressOf(0),
                        )
                    }
                }
            }
        }
        return out
    }

    override fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray): ByteArray =
        aesEcb(kCCDecrypt, key16, ciphertext)

    override fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray): ByteArray =
        aesEcb(kCCEncrypt, key16, plaintext)

    private fun aesEcb(op: UInt, key16: ByteArray, input: ByteArray): ByteArray {
        require(key16.size == 16) { "AES-128 key must be 16 bytes" }
        require(input.size % 16 == 0) { "ECB input must be block-aligned" }
        val out = ByteArray(input.size)
        if (input.isEmpty()) return out
        var status = 0
        out.usePinned { outPin ->
            key16.usePinned { keyPin ->
                input.usePinned { inPin ->
                    status = CCCrypt(
                        op,
                        kCCAlgorithmAES,
                        kCCOptionECBMode,
                        keyPin.addressOf(0), key16.size.convert(),
                        null,
                        inPin.addressOf(0), input.size.convert(),
                        outPin.addressOf(0), out.size.convert(),
                        null,
                    ).toInt()
                }
            }
        }
        check(status == kCCSuccess.toInt()) { "CCCrypt failed: $status" }
        return out
    }

    override fun generateEd25519Seed(): ByteArray = randomBytes(32)

    // Ed25519 needs the CryptoKit bridge. Until it lands these FAIL
    // CLOSED rather than throwing: a throw from ed25519Verify would
    // escape the RX collector (NotImplementedError is an Error, not an
    // Exception) and one hostile advert would deafen the app. Verify
    // returning false means adverts are simply never trusted on iOS.
    override fun ed25519PublicKey(seed: ByteArray): ByteArray = ByteArray(0)

    override fun ed25519Sign(message: ByteArray, seed: ByteArray): ByteArray = ByteArray(0)

    override fun ed25519Verify(signature: ByteArray, message: ByteArray, publicKey: ByteArray): Boolean =
        false

    override fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        if (length == 0) return out
        out.usePinned { pin ->
            val rc = SecRandomCopyBytes(kSecRandomDefault, length.convert(), pin.addressOf(0))
            check(rc == 0) { "SecRandomCopyBytes failed: $rc" }
        }
        return out
    }

    override val supportsAuthenticatedEncryption: Boolean get() = false

    // AES-GCM is Phase-2 work alongside the CryptoKit bridge. Sealing
    // returns nothing and opening fails closed, so an iOS build cannot
    // silently write an unencrypted "encrypted" backup, and cannot claim
    // to have read one. The config-backup UI must check
    // [supportsAuthenticatedEncryption] before offering the option.
    override fun aesGcmSeal(
        key32: ByteArray,
        nonce12: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw UnsupportedOperationException(
        "AES-GCM is not bridged on iOS yet — see iosApp/README.md Phase 2",
    )

    override fun aesGcmOpen(
        key32: ByteArray,
        nonce12: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray? = null
}

@OptIn(ExperimentalForeignApi::class)
private fun kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>.reinterpretUByte():
    kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar> =
    kotlinx.cinterop.interpretCPointer(this.rawValue)!!
