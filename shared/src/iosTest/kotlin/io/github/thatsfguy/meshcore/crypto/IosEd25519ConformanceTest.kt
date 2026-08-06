package io.github.thatsfguy.meshcore.crypto

import io.github.thatsfguy.meshcore.platform.IosCryptoProvider
import kotlin.test.Test

/**
 * The CryptoKit bridge against the RFC 8032 vectors.
 *
 * This is the test the iOS work has been missing. A green CI run proved
 * `shared/iosCryptoBridge` compiles and links; it proved nothing about
 * the bytes. Advert verification is the gate on contact import, so a
 * bridge that links and returns garbage fails in the quietest possible
 * way: every advert on the mesh looks forged, nobody is ever imported,
 * and nothing is logged.
 *
 * TEST 1 signs an EMPTY message, which exercises the zero-length pin
 * path in `IosCryptoProvider` — Kotlin/Native cannot take the address
 * of an empty array, so that call passes a one-byte scratch buffer with
 * length 0. That was written blind and is asserted here.
 */
class IosEd25519ConformanceTest {
    private val crypto = IosCryptoProvider()

    @Test fun publicKeys() = Ed25519Conformance.publicKeysMatchTheVectors(crypto)
    /**
     * NOT byte-for-byte against the RFC: CryptoKit's Ed25519 is hedged,
     * so it emits a different valid signature every time. What must
     * hold — and what the mesh actually relies on — is that what we
     * produce verifies under the published key.
     */
    @Test fun signaturesVerify() = Ed25519Conformance.signaturesVerifyUnderThePublishedKey(crypto)
    @Test fun verifyAccepts() = Ed25519Conformance.verifyAcceptsTheVectors(crypto)
    @Test fun verifyRejects() = Ed25519Conformance.verifyRejectsTampering(crypto)
}
