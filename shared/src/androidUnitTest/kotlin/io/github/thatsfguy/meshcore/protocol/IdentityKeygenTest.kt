package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Generating a repeater identity that nobody else on the mesh answers
 * to ([IdentityKeygen]).
 *
 * Two things make this suite worth more than its length suggests.
 *
 * **It has a positive control.** Most of these assert that a generated
 * key does NOT collide, and every one of them would pass against a
 * generator that returned nothing at all, or that always returned the
 * same key, or that searched a prefix width of zero. So
 * [aSearchWithNothingToAvoidStillProducesAKey] and
 * [theWidthActuallyChangesWhatCounts] pin the cases where it must
 * answer, and the collision tests generate enough keys that a generator
 * ignoring its avoid-list would fail with overwhelming probability
 * rather than occasionally.
 *
 * **It is checked against the firmware's rules, not ours.** A public key
 * beginning `00` or `ff` is refused by `LocalIdentity::validatePrivateKey`
 * (`src/Identity.cpp:71-72`); a key is 64 bytes on the wire
 * (`CommonCLI.cpp:510-512`). Both are asserted on generated output here,
 * because a generator that produces keys the node rejects is worse than
 * no generator — it fails at the confirmation dialog, once in a while,
 * on the least recoverable screen in the app.
 */
class IdentityKeygenTest {

    private val crypto = AndroidCryptoProvider()

    /** A well-formed public key beginning with [prefixHex]. */
    private fun keyStartingWith(prefixHex: String): String =
        prefixHex.lowercase().padEnd(64, '7').take(64)

    // ---- the search does what it says --------------------------------

    @Test
    fun aSearchWithNothingToAvoidStillProducesAKey() {
        // The positive control: with an empty mesh every key is fine, so
        // anything but Generated here means the search cannot answer at
        // all and every "does not collide" test below is vacuous.
        val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, knownKeys = emptyList())
        val generated = assertIs<IdentityKeygen.Outcome.Generated>(outcome)
        assertEquals(2, generated.widthBytes)
        assertTrue(generated.clearOfFirstByte)
        assertEquals(1, generated.attempts, "an empty mesh should be first time lucky")
    }

    @Test
    fun aGeneratedKeyNeverLandsOnAPrefixSomebodyElseHolds() {
        for (width in 1..3) {
            // A busy mesh: 120 nodes, so at one byte per hop nearly half
            // the space is spoken for and a generator ignoring the list
            // would collide within a handful of draws.
            val known = List(120) { keyStartingWith("%02x".format(it + 1)) }
            val taken = IdentityKeygen.prefixesOf(known, width)
            repeat(25) {
                val outcome = IdentityKeygen.generate(crypto, width, known)
                val generated = assertIs<IdentityKeygen.Outcome.Generated>(outcome)
                assertFalse(
                    IdentityKeygen.collides(generated.candidate.publicKeyHex, taken, width),
                    "width $width produced ${generated.candidate.publicKeyHex}",
                )
            }
        }
    }

    /**
     * The width is load-bearing, shown the only way that cannot be
     * confused with the one-byte preference: the SAME mesh is full at
     * one byte per hop and has room at two.
     *
     * (The preference is why the obvious test does not work. With 254
     * first bytes free the search takes a clear one, so a single known
     * node bars its whole first byte whatever width was asked — which
     * looks exactly like the width being ignored.)
     */
    @Test
    fun theSameMeshIsFullAtOneByteAndHasRoomAtTwo() {
        val everyFirstByte = (0..255).map { keyStartingWith("%02x".format(it)) }

        val atOne = IdentityKeygen.generate(crypto, 1, everyFirstByte, maxAttempts = 50)
        assertIs<IdentityKeygen.Outcome.Exhausted>(atOne)

        val atTwo = IdentityKeygen.generate(crypto, 2, everyFirstByte)
        val generated = assertIs<IdentityKeygen.Outcome.Generated>(atTwo)
        assertEquals(2, generated.widthBytes)
    }

    /**
     * And it is applied at the width asked, not at a wider one that
     * would look like success while leaving a real clash in place.
     *
     * Every usable first byte is taken — so the one-byte preference is
     * off the table and the requirement is exposed — and three quarters
     * of the two-byte space with it. A search comparing three or four
     * bytes instead would hand back a two-byte clash on most draws.
     */
    @Test
    fun theRequirementIsAppliedAtTheWidthAskedAndNotAWiderOne() {
        val known = buildList {
            for (first in 1..254) {
                for (second in 0..191) {
                    add(keyStartingWith("%02x%02x".format(first, second)))
                }
            }
        }
        val taken = IdentityKeygen.prefixesOf(known, 2)
        assertEquals(254 * 192, taken.size)

        repeat(30) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, knownKeys = known)
            val generated = assertIs<IdentityKeygen.Outcome.Generated>(outcome)
            assertFalse(generated.clearOfFirstByte)
            assertFalse(
                IdentityKeygen.collides(generated.candidate.publicKeyHex, taken, 2),
                "${generated.candidate.publicKeyHex} clashes at two bytes",
            )
        }
    }

    @Test
    fun theNodesOwnCurrentPrefixIsAvoidedLikeAnyOther() {
        // The node being rekeyed is in the contact list, so its own old
        // prefix is barred too. Landing back on it is the worst outcome
        // available: every stale route on the mesh keeps matching, and
        // everything arriving that way then fails to decrypt.
        val current = keyStartingWith("5a5b5c")
        repeat(25) {
            val outcome = IdentityKeygen.generate(crypto, 2, listOf(current))
            val key = assertIs<IdentityKeygen.Outcome.Generated>(outcome).candidate.publicKeyHex
            assertNotEquals(current.take(4), key.take(4))
        }
    }

    // ---- the destination hash, which is always one byte --------------

    @Test
    fun aKeyClearAtTheConfiguredWidthIsPreferredClearAtOneByteToo() {
        // Every first byte the firmware will accept is taken, but only
        // 254 of the 65 536 two-byte prefixes are. So the search can
        // always satisfy the width and can never satisfy the
        // destination hash, and has to say which it got.
        val everyFirstByte = (1..254).map { keyStartingWith("%02x".format(it)) }
        val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, knownKeys = everyFirstByte)
        val generated = assertIs<IdentityKeygen.Outcome.Generated>(outcome)
        assertFalse(
            generated.clearOfFirstByte,
            "claimed a free first byte when all 254 usable ones are taken",
        )
        assertFalse(
            IdentityKeygen.collides(
                generated.candidate.publicKeyHex,
                IdentityKeygen.prefixesOf(everyFirstByte, 2),
                2,
            ),
        )
    }

    @Test
    fun aFreeFirstByteIsReportedWhenThereIsOne() {
        val known = List(10) { keyStartingWith("%02x".format(it + 1)) }
        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, knownKeys = known)
            val generated = assertIs<IdentityKeygen.Outcome.Generated>(outcome)
            // With 10 of 254 taken this is essentially always true, and
            // a search that never claimed it would be useless.
            assertTrue(generated.clearOfFirstByte || generated.attempts > 1)
            if (generated.clearOfFirstByte) {
                assertFalse(
                    IdentityKeygen.collides(
                        generated.candidate.publicKeyHex,
                        IdentityKeygen.prefixesOf(known, 1),
                        1,
                    ),
                )
            }
        }
    }

    @Test
    fun aMeshWithNoRoomLeftSaysSoInsteadOfSearchingForEver() {
        // All 256 first bytes claimed, one byte per hop: there is no key
        // left to find, and the answer is a fact about the mesh rather
        // than a retry. A wider path hash is the actual fix.
        val everything = (0..255).map { keyStartingWith("%02x".format(it)) }
        val outcome = IdentityKeygen.generate(
            crypto, widthBytes = 1, knownKeys = everything, maxAttempts = 50,
        )
        val exhausted = assertIs<IdentityKeygen.Outcome.Exhausted>(outcome)
        assertEquals(1, exhausted.widthBytes)
        assertEquals(50, exhausted.attempts)
        assertEquals(256, exhausted.takenPrefixes)
        assertEquals(256L, exhausted.totalPrefixes)
    }

    // ---- what it hands back ------------------------------------------

    @Test
    fun everyGeneratedKeyIsOneTheFirmwareWillTake() {
        // `if (pub[0] == 0x00 || pub[0] == 0xFF) return false`
        // (src/Identity.cpp:71-72). About one key in 128 fails it, so a
        // few hundred draws will find one if the rule is not applied.
        repeat(600) {
            val outcome = IdentityKeygen.generate(crypto, 1, emptyList())
            val candidate = assertIs<IdentityKeygen.Outcome.Generated>(outcome).candidate
            assertTrue(
                IdentityKey.isAcceptablePublicKey(candidate.publicKeyHex),
                "offered ${candidate.publicKeyHex}, which the node would refuse",
            )
        }
    }

    @Test
    fun theThreeFormsHandedBackAreAllTheSameKey() {
        val outcome = IdentityKeygen.generate(crypto, 2, emptyList())
        val candidate = assertIs<IdentityKeygen.Outcome.Generated>(outcome).candidate

        assertEquals(IdentityKey.SEED_HEX_LENGTH, candidate.seedHex.length)
        assertEquals(IdentityKey.PRIVATE_KEY_HEX_LENGTH, candidate.privateKeyHex.length)
        assertEquals(64, candidate.publicKeyHex.length)

        // The private key is the seed's expansion, and the public key is
        // what that seed derives — so writing down the seed really is
        // enough, and the previewed identity really is the one applied.
        assertEquals(IdentityKey.expandSeedHex(crypto, candidate.seedHex), candidate.privateKeyHex)
        assertEquals(IdentityKey.publicKeyHex(crypto, candidate.seedHex), candidate.publicKeyHex)
        // And it survives the command builder, at the length the
        // firmware reads.
        assertEquals(
            "set prv.key ${candidate.privateKeyHex}",
            IdentityKey.setCommand(crypto, candidate.privateKeyHex),
        )
        assertEquals(
            "set prv.key ${candidate.privateKeyHex}",
            IdentityKey.setCommand(crypto, candidate.seedHex),
        )
    }

    @Test
    fun successiveKeysAreDifferentKeys() {
        val keys = List(20) {
            assertIs<IdentityKeygen.Outcome.Generated>(
                IdentityKeygen.generate(crypto, 2, emptyList()),
            ).candidate.publicKeyHex
        }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun thePrefixIsTheLeadingBytesAtTheWidthAsked() {
        val outcome = IdentityKeygen.generate(crypto, 3, emptyList())
        val candidate = assertIs<IdentityKeygen.Outcome.Generated>(outcome).candidate
        assertEquals(candidate.publicKeyHex.take(2), candidate.prefixHex(1))
        assertEquals(candidate.publicKeyHex.take(4), candidate.prefixHex(2))
        assertEquals(candidate.publicKeyHex.take(6), candidate.prefixHex(3))
        // Out-of-range widths are clamped, never allowed to produce an
        // empty prefix that would match everything.
        assertEquals(candidate.publicKeyHex.take(2), candidate.prefixHex(0))
        assertEquals(candidate.publicKeyHex.take(2), candidate.prefixHex(-4))
        assertEquals(candidate.publicKeyHex.take(8), candidate.prefixHex(99))
    }

    // ---- the avoid-list is built from real keys only ------------------

    @Test
    fun malformedEntriesContributeNoPrefix() {
        // A truncated or non-hex entry would otherwise bar a prefix no
        // real node holds, which quietly shrinks the space and, at one
        // byte per hop, can make a solvable mesh look full.
        val prefixes = IdentityKeygen.prefixesOf(
            listOf(
                keyStartingWith("aabb"),
                "",
                "aabb",                       // a prefix, not a key
                "z".repeat(64),               // right length, not hex
                keyStartingWith("ccdd").dropLast(1),
                "  " + keyStartingWith("eeff").uppercase() + "  ",
            ),
            widthBytes = 2,
        )
        assertEquals(setOf("aabb", "eeff"), prefixes)
    }

    @Test
    fun collidesComparesTheLeadingBytesAndNothingElse() {
        val taken = setOf("abcd")
        assertTrue(IdentityKeygen.collides(keyStartingWith("abcd11"), taken, 2))
        assertTrue(IdentityKeygen.collides(keyStartingWith("ABCD11").uppercase(), taken, 2))
        assertFalse(IdentityKeygen.collides(keyStartingWith("abce11"), taken, 2))
        // A wider comparison than the set was built at matches nothing,
        // and must not be read as "clear" by accident elsewhere.
        assertFalse(IdentityKeygen.collides(keyStartingWith("abcd11"), taken, 3))
    }

    @Test
    fun theWidthIsClampedToSomethingAPrefixCanBe() {
        assertEquals(1, IdentityKeygen.clampWidth(0))
        assertEquals(1, IdentityKeygen.clampWidth(-1))
        assertEquals(3, IdentityKeygen.clampWidth(3))
        assertEquals(IdentityKeygen.MAX_WIDTH_BYTES, IdentityKeygen.clampWidth(9))
        // Every mode the firmware accepts survives the clamp unchanged:
        // "Error, must be 0,1, or 2" (CommonCLI.cpp:664).
        for (mode in PathHashMode.MODES) {
            val bytes = PathHashMode.bytesFor(mode)
            assertEquals(bytes, IdentityKeygen.clampWidth(bytes))
        }
    }

    @Test
    fun theSpaceIsSizedRightAtEveryWidth() {
        // 4 294 967 296 does not fit in an Int, and a capacity message
        // reading "-1" would be worse than no message.
        assertEquals(256L, IdentityKeygen.totalPrefixes(1))
        assertEquals(65_536L, IdentityKeygen.totalPrefixes(2))
        assertEquals(16_777_216L, IdentityKeygen.totalPrefixes(3))
        assertEquals(4_294_967_296L, IdentityKeygen.totalPrefixes(4))
        assertTrue(IdentityKeygen.totalPrefixes(4) > Int.MAX_VALUE)
    }
}
