package io.github.thatsfguy.meshcore.crypto

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test

/**
 * Bouncy Castle against the RFC 8032 vectors.
 *
 * The reference side of the pair: iOS runs the identical assertions in
 * `IosEd25519ConformanceTest`, so the two platforms cannot drift from
 * each other, and neither can drift from the RFC.
 */
class AndroidEd25519ConformanceTest {
    private val crypto = AndroidCryptoProvider()

    @Test fun publicKeys() = Ed25519Conformance.publicKeysMatchTheVectors(crypto)
    @Test fun signatures() = Ed25519Conformance.signaturesMatchTheVectors(crypto)
    @Test fun verifyAccepts() = Ed25519Conformance.verifyAcceptsTheVectors(crypto)
    @Test fun verifyRejects() = Ed25519Conformance.verifyRejectsTampering(crypto)
}
