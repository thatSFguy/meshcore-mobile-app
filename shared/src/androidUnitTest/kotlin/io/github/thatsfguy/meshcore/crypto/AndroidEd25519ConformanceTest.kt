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
    @Test fun signaturesVerify() = Ed25519Conformance.signaturesVerifyUnderThePublishedKey(crypto)

    /**
     * Bouncy Castle is RFC-deterministic, so it reproduces the example
     * signatures exactly. Asserted here and NOT on iOS, where CryptoKit
     * hedges — see Ed25519Conformance.signaturesAreRfcDeterministic.
     */
    @Test fun signaturesAreDeterministic() = Ed25519Conformance.signaturesAreRfcDeterministic(crypto)
    @Test fun verifyAccepts() = Ed25519Conformance.verifyAcceptsTheVectors(crypto)
    @Test fun verifyRejects() = Ed25519Conformance.verifyRejectsTampering(crypto)
}
