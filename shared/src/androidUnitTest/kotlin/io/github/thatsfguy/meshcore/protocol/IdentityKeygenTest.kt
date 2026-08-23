package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.IdentityKeygen.ClashLevel
import io.github.thatsfguy.meshcore.protocol.IdentityKeygen.KnownNode
import io.github.thatsfguy.meshcore.protocol.IdentityKeygen.Remoteness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generating a repeater identity that nobody else on the mesh answers
 * to, and — when there is no such identity left — choosing which node to
 * collide with rather than accepting whichever turned up first
 * ([IdentityKeygen]).
 *
 * Three things make this suite worth more than its length suggests.
 *
 * **It has positive controls.** Most of these assert that a generated
 * key does NOT collide, and every one of them would pass against a
 * generator that returned nothing, or always returned the same key, or
 * compared a prefix of zero bytes. So
 * [aSearchWithNothingToAvoidIsCleanFirstTime] and
 * [theSameMeshIsFullAtOneByteAndHasRoomAtTwo] pin the cases where it
 * must answer, and the collision tests generate enough keys that a
 * generator ignoring its inputs would fail with overwhelming probability
 * rather than occasionally.
 *
 * **The fallback is tested as a choice, not as a fallback.** A search
 * that settles for any clash passes every "does not clash cleanly" test
 * there is. [theUnavoidableClashIsWithTheMostDistantNode] is the one
 * that fails it.
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

    /** A known node at a chosen remoteness; the label is display text. */
    private fun node(prefixHex: String, remoteness: Int, label: String = prefixHex) =
        KnownNode(keyStartingWith(prefixHex), label, remoteness)

    /** Every usable first byte claimed, so no clean key can exist. */
    private fun everyFirstByte(remoteness: Int): List<KnownNode> =
        (0..255).map { node("%02x".format(it), remoteness, "node-%02x".format(it)) }

    // ---- the search does what it says --------------------------------

    @Test
    fun aSearchWithNothingToAvoidIsCleanFirstTime() {
        // The positive control: with an empty mesh every key is fine, so
        // anything but a clean first draw here means the search cannot
        // answer at all and every "does not clash" test below is vacuous.
        val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, known = emptyList())
        assertNotNull(outcome)
        assertTrue(outcome.isClean)
        assertNull(outcome.clash)
        assertEquals(2, outcome.widthBytes)
        assertEquals(1, outcome.attempts, "an empty mesh should be first time lucky")
        assertEquals(0, outcome.takenPrefixes)
    }

    @Test
    fun aGeneratedKeyNeverLandsOnAPrefixSomebodyElseHolds() {
        for (width in 1..3) {
            // A busy mesh: 120 nodes, so at one byte per hop nearly half
            // the space is spoken for and a generator ignoring the list
            // would collide within a handful of draws.
            val known = (1..120).map { node("%02x".format(it), Remoteness.INFRASTRUCTURE_MAX) }
            val taken = IdentityKeygen.prefixesOf(known.map { it.publicKeyHex }, width)
            repeat(25) {
                val outcome = IdentityKeygen.generate(crypto, width, known)
                assertNotNull(outcome)
                assertTrue(outcome.isClean, "settled for a clash with room to spare")
                assertFalse(
                    IdentityKeygen.collides(outcome.candidate.publicKeyHex, taken, width),
                    "width $width produced ${outcome.candidate.publicKeyHex}",
                )
            }
        }
    }

    /**
     * The width is load-bearing, shown the only way that cannot be
     * confused with the one-byte preference: the SAME mesh has no clean
     * key at one byte per hop and plenty at two.
     */
    @Test
    fun theSameMeshIsFullAtOneByteAndHasRoomAtTwo() {
        val known = everyFirstByte(Remoteness.INFRASTRUCTURE_MAX)

        val atOne = IdentityKeygen.generate(crypto, 1, known, maxAttempts = 50)
        assertNotNull(atOne)
        assertEquals(ClashLevel.ROUTE, atOne.clash?.level)
        assertEquals(256, atOne.takenPrefixes)

        val atTwo = IdentityKeygen.generate(crypto, 2, known)
        assertNotNull(atTwo)
        assertEquals(2, atTwo.widthBytes)
        assertEquals(ClashLevel.DESTINATION, atTwo.clash?.level)
    }

    /**
     * And it is applied at the width asked, not at a wider one that
     * would look like success while leaving a real clash in place.
     *
     * Every usable first byte is taken — so no clean key exists and the
     * route requirement is what is left — along with three quarters of
     * the two-byte space. A search comparing three or four bytes instead
     * would report a destination-level clash on most draws while
     * actually sitting on a two-byte one.
     */
    @Test
    fun theRequirementIsAppliedAtTheWidthAskedAndNotAWiderOne() {
        val known = buildList {
            for (first in 1..254) {
                for (second in 0..191) {
                    add(
                        node(
                            "%02x%02x".format(first, second),
                            Remoteness.INFRASTRUCTURE_MAX,
                        ),
                    )
                }
            }
        }
        val taken = IdentityKeygen.prefixesOf(known.map { it.publicKeyHex }, 2)
        assertEquals(254 * 192, taken.size)

        repeat(30) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, known = known)
            assertNotNull(outcome)
            assertEquals(ClashLevel.DESTINATION, outcome.clash?.level)
            assertFalse(
                IdentityKeygen.collides(outcome.candidate.publicKeyHex, taken, 2),
                "${outcome.candidate.publicKeyHex} clashes at two bytes",
            )
        }
    }

    @Test
    fun theNodesOwnCurrentPrefixIsAvoidedLikeAnyOther() {
        // The node being rekeyed is in the contact list, so its own old
        // prefix is barred too. Landing back on it is the worst outcome
        // available: every stale route on the mesh keeps matching, and
        // everything arriving that way then fails to decrypt.
        val current = node("5a5b5c", Remoteness.UNKNOWN)
        repeat(25) {
            val outcome = IdentityKeygen.generate(crypto, 2, listOf(current))
            assertNotNull(outcome)
            assertNotEquals(current.publicKeyHex.take(4), outcome.candidate.publicKeyHex.take(4))
        }
    }

    // ---- choosing the clash when there is no clean key ----------------

    /**
     * The test the fallback exists for.
     *
     * Every first byte is taken, so a shared destination hash is
     * certain; one node is far away and the other 253 are neighbours. A
     * search that took the first key that fit would land on a neighbour
     * 253 times out of 254. This one has to find the distant node.
     */
    @Test
    fun theUnavoidableClashIsWithTheMostDistantNode() {
        val distant = node("42", Remoteness.INFRASTRUCTURE_MAX, "Far Ridge, 240 km away")
        val known = everyFirstByte(Remoteness.UNKNOWN)
            .filterNot { it.publicKeyHex.startsWith("42") } + distant

        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 1, known = known)
            assertNotNull(outcome)
            val clash = assertNotNull(outcome.clash)
            assertEquals(ClashLevel.ROUTE, clash.level)
            assertEquals(
                "Far Ridge, 240 km away",
                clash.with.label,
                "settled for a neighbour when a distant node was available",
            )
            assertEquals("42", outcome.candidate.publicKeyHex.take(2))
        }
    }

    @Test
    fun aRoutingClashIsNeverPreferredToADestinationOne() {
        // The far node clashes at the routing width; a near one only
        // shares a destination hash. Distance must not buy a route
        // clash: the level decides first, always.
        val known = everyFirstByte(Remoteness.UNKNOWN).filterNot {
            it.publicKeyHex.startsWith("42")
        } + node("4200", Remoteness.INFRASTRUCTURE_MAX, "Far Ridge") +
            node("42", Remoteness.UNKNOWN, "The Next Hill")

        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 2, known = known)
            assertNotNull(outcome)
            assertEquals(
                ClashLevel.DESTINATION,
                outcome.clash?.level,
                "took a route clash with a distant node over a destination clash",
            )
        }
    }

    /**
     * A candidate is only as good as the WORST node answering to its
     * name. Two nodes share the prefix `42` — one distant, one next
     * door — so `42` must be judged by the neighbour and lose to `43`,
     * which only the distant node holds.
     */
    @Test
    fun aPrefixIsJudgedByTheNearestNodeHoldingIt() {
        val known = everyFirstByte(Remoteness.UNKNOWN)
            .filterNot { it.publicKeyHex.take(2) in setOf("42", "43") } +
            node("42", Remoteness.INFRASTRUCTURE_MAX, "Far Ridge") +
            node("4200", Remoteness.UNKNOWN, "The Next Hill") +
            node("43", Remoteness.INFRASTRUCTURE_MAX, "Far Ridge Two")

        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 1, known = known)
            assertNotNull(outcome)
            assertEquals("Far Ridge Two", outcome.clash?.with?.label)
            assertEquals("43", outcome.candidate.publicKeyHex.take(2))
        }
    }

    @Test
    fun anOrdinaryNodeIsPreferredToARepeaterToCollideWith() {
        // Only repeaters appear in a path, so sharing a prefix with a
        // chat node next door beats sharing one with a repeater across
        // the state. Ranked by Remoteness; asserted here because it is
        // the search that has to act on it.
        val chatNext = Remoteness.of(300.0, null, isInfrastructure = false)
        val repeaterFar = Remoteness.of(400_000.0, null, isInfrastructure = true)
        val known = everyFirstByte(Remoteness.UNKNOWN)
            .filterNot { it.publicKeyHex.take(2) in setOf("42", "43") } +
            node("42", repeaterFar, "Distant Repeater") +
            node("43", chatNext, "Someone's Handheld")

        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, widthBytes = 1, known = known)
            assertNotNull(outcome)
            assertEquals("Someone's Handheld", outcome.clash?.with?.label)
        }
    }

    @Test
    fun aCleanKeyBeatsEveryClashHoweverDistant() {
        // One node, as remote as the scale goes, and 65 535 free
        // two-byte names. Settling for the clash would be absurd; a
        // scoring bug that treated a big remoteness as "good enough"
        // would do exactly that.
        val known = listOf(node("ab", Int.MAX_VALUE, "As Far As It Gets"))
        repeat(20) {
            val outcome = IdentityKeygen.generate(crypto, 2, known)
            assertNotNull(outcome)
            assertTrue(outcome.isClean)
        }
    }

    // ---- what it hands back ------------------------------------------

    @Test
    fun everyGeneratedKeyIsOneTheFirmwareWillTake() {
        // `if (pub[0] == 0x00 || pub[0] == 0xFF) return false`
        // (src/Identity.cpp:71-72). About one key in 128 fails it, so a
        // few hundred draws will find one if the rule is not applied.
        repeat(600) {
            val outcome = IdentityKeygen.generate(crypto, 1, emptyList())
            assertNotNull(outcome)
            assertTrue(
                IdentityKey.isAcceptablePublicKey(outcome.candidate.publicKeyHex),
                "offered ${outcome.candidate.publicKeyHex}, which the node would refuse",
            )
        }
    }

    @Test
    fun theThreeFormsHandedBackAreAllTheSameKey() {
        val outcome = IdentityKeygen.generate(crypto, 2, emptyList())
        assertNotNull(outcome)
        val candidate = outcome.candidate

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
            assertNotNull(IdentityKeygen.generate(crypto, 2, emptyList())).candidate.publicKeyHex
        }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun thePrefixIsTheLeadingBytesAtTheWidthAsked() {
        val outcome = assertNotNull(IdentityKeygen.generate(crypto, 3, emptyList()))
        val candidate = outcome.candidate
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
    fun aMalformedKnownNodeCannotBarAName() {
        // Same rule where it matters most: the search must not treat a
        // junk contact record as occupying a prefix.
        val junk = listOf(
            KnownNode("", "empty", Remoteness.UNKNOWN),
            KnownNode("not a key", "junk", Remoteness.UNKNOWN),
            KnownNode("z".repeat(64), "not hex", Remoteness.UNKNOWN),
        )
        repeat(10) {
            val outcome = IdentityKeygen.generate(crypto, 1, junk)
            assertNotNull(outcome)
            assertTrue(outcome.isClean)
        }
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
