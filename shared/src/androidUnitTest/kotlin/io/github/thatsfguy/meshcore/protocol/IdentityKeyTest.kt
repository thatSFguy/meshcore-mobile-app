package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity-key handling (PARITY §6 — the highest-consequence screen).
 *
 * Most of this is about refusing something. Applying a wrong key to a
 * repeater is not recoverable from the app, from the mesh, or at all:
 * the node becomes a stranger to everyone who knew it, and there is no
 * revocation in the protocol.
 *
 * The exception, and the tests that carry the file, are the ones about
 * **length**. The screen shipped sending a 32-byte seed to a firmware
 * that reads 64 bytes, so every key it generated came back "Error, bad
 * key" and every key it read came back unrecognised. Both directions
 * had tests. Both tests asserted 64 hex characters, because both were
 * written against this file rather than against the firmware's reader —
 * the same shape as the trace-flags and neighbours defects in CLAUDE.md.
 * So the length assertions here cite `CommonCLI.cpp` and `Utils.cpp`,
 * and there is one that fails if a seed is ever sent unexpanded again.
 */
class IdentityKeyTest {

    private val crypto = AndroidCryptoProvider()

    // A varied key on purpose: "4f".repeat(32) is 64 valid hex
    // characters AND all one byte, so it trips the degenerate check —
    // which is exactly the trap this constant used to fall into.
    private val seed = "0123456789abcdef".repeat(4)
    private val expanded = "0123456789abcdef".repeat(8)

    @Test
    fun acceptsBothFormsAKeyIsWrittenDownIn() {
        // 32-byte seed (the short form), and the 64-byte private key the
        // node itself stores and reports.
        assertEquals(seed, IdentityKey.canonicalHex(seed))
        assertEquals(expanded, IdentityKey.canonicalHex(expanded))
        assertTrue(IdentityKey.isValidHex(seed))
        assertTrue(IdentityKey.isValidHex(expanded))
        assertEquals(seed, IdentityKey.canonicalSeedHex(seed))
        assertNull(IdentityKey.canonicalSeedHex(expanded))
        assertEquals(expanded, IdentityKey.canonicalPrivateKeyHex(expanded))
        assertNull(IdentityKey.canonicalPrivateKeyHex(seed))
    }

    @Test
    fun toleratesThePastingArtefactsPeopleActuallyProduce() {
        assertEquals(seed, IdentityKey.canonicalHex(seed.uppercase()))
        assertEquals(seed, IdentityKey.canonicalHex("  $seed  "))
        assertEquals(seed, IdentityKey.canonicalHex("0x$seed"))
        assertEquals(seed, IdentityKey.canonicalHex("0X$seed"))
        // Grouped with spaces or colons, as key material often is.
        assertEquals(seed, IdentityKey.canonicalHex(seed.chunked(4).joinToString(" ")))
        assertEquals(seed, IdentityKey.canonicalHex(seed.chunked(2).joinToString(":")))
        // And the same for the long form, which is the one a node emits.
        assertEquals(expanded, IdentityKey.canonicalHex(expanded.uppercase()))
        assertEquals(expanded, IdentityKey.canonicalHex(expanded.chunked(2).joinToString(":")))
    }

    @Test
    fun aShortKeyIsNeverPaddedIntoAValidOne() {
        // Silent padding would hand the node an identity nobody chose.
        for (n in listOf(0, 1, 2, 31, 62, 63, 65, 126, 127, 129, 256)) {
            assertNull(IdentityKey.canonicalHex("a".repeat(n)), "accepted $n chars")
        }
        assertNull(IdentityKey.canonicalHex(seed.dropLast(2)))
        assertNull(IdentityKey.canonicalHex(seed + "ab"))
        assertNull(IdentityKey.canonicalHex(expanded.dropLast(2)))
    }

    @Test
    fun nonHexIsRefused() {
        for (bad in listOf(
            "z".repeat(64), "g".repeat(64), "z".repeat(128), null, "", "   ",
            seed.dropLast(1) + "!", seed.dropLast(1) + "g", expanded.dropLast(1) + "!",
        )) {
            assertNull(IdentityKey.canonicalHex(bad), "accepted: $bad")
        }
    }

    @Test
    fun degenerateKeysAreRecognised() {
        // Structurally valid, cryptographically worthless: anyone can
        // reproduce them, so a node using one has no identity at all.
        assertTrue(IdentityKey.isDegenerate("00".repeat(32)))
        assertTrue(IdentityKey.isDegenerate("ff".repeat(32)))
        assertTrue(IdentityKey.isDegenerate("aa".repeat(32)))
        assertTrue(IdentityKey.isDegenerate("aa".repeat(64)))
        assertFalse(IdentityKey.isDegenerate(seed.dropLast(2) + "ab"))
        assertFalse(IdentityKey.isDegenerate(IdentityKey.generate(crypto)))
    }

    // ---- length: the defect this file exists to keep fixed -----------

    /**
     * `set prv.key` reads `PRV_KEY_SIZE` bytes — 64, `src/MeshCore.h:9` —
     * through `Utils::fromHex(prv_key, PRV_KEY_SIZE, &config[8])`
     * (`CommonCLI.cpp:510-512`), and `fromHex` starts with
     * `if (len != dest_size*2) return false` (`Utils.cpp:206-208`).
     *
     * So the argument is 128 characters or the node answers "Error, bad
     * key" without looking at a single digit. Asserted against those
     * lines, not against our own parser.
     */
    @Test
    fun theSetCommandSendsTheSixtyFourByteFormTheFirmwareReads() {
        val argument = IdentityKey.setCommand(crypto, seed).removePrefix("set prv.key ")
        assertEquals(IdentityKey.PRIVATE_KEY_HEX_LENGTH, argument.length)
        assertEquals(128, argument.length)
        assertEquals(IdentityKey.expandSeedHex(crypto, seed), argument)
    }

    /**
     * The regression itself, stated as the thing that was wrong: the
     * seed must never reach the wire.
     */
    @Test
    fun aSeedIsNeverSentAsThoughItWereTheKey() {
        val argument = IdentityKey.setCommand(crypto, seed).removePrefix("set prv.key ")
        assertNotEquals(seed, argument)
        assertFalse(argument.startsWith(seed), "the seed was padded rather than expanded")
    }

    @Test
    fun theSixtyFourByteFormIsSentThroughUnchanged() {
        // Someone restoring a node has whatever `get prv.key` gave them,
        // which is already the wire form. Expanding it again would be a
        // different key entirely.
        assertEquals(
            "set prv.key $expanded",
            IdentityKey.setCommand(crypto, expanded),
        )
        assertEquals(expanded, IdentityKey.wireKeyHex(crypto, expanded.uppercase()))
    }

    /**
     * `get prv.key` replies with the same 64 bytes:
     * `getSelfId().writeTo(prv_key, PRV_KEY_SIZE)` (`CommonCLI.cpp:832-836`)
     * over `LocalIdentity::writeTo` (`Identity.cpp:128-138`), which
     * copies `PRV_KEY_SIZE` bytes when that is all the buffer holds.
     *
     * A validator that only accepted 64 characters rejected every real
     * reply, so "Read key…" could only ever report a refusal.
     */
    @Test
    fun aRealReplyFromTheNodeIsRecognisedAsAKey() {
        val reply = IdentityKey.expandSeedHex(crypto, IdentityKey.generate(crypto))!!
        assertEquals(128, reply.length)
        assertEquals(reply, IdentityKey.canonicalHex(reply))
        assertEquals(reply, IdentityKey.canonicalHex(reply.uppercase()))
    }

    @Test
    fun expandingIsTheClampedSha512AndNothingElse() {
        val expandedHex = IdentityKey.expandSeedHex(crypto, seed)!!
        val bytes = expandedHex.chunked(2).map { it.toInt(16) }
        assertEquals(64, bytes.size)
        assertEquals(0, bytes[0] and 0x07)
        assertEquals(0x40, bytes[31] and 0xC0)
        assertNull(IdentityKey.expandSeedHex(crypto, expandedHex), "expanded twice")
        assertNull(IdentityKey.expandSeedHex(crypto, "nonsense"))
    }

    // ---- what the firmware refuses -----------------------------------

    /**
     * `if (pub[0] == 0x00 || pub[0] == 0xFF) return false`
     * (`LocalIdentity::validatePrivateKey`, `src/Identity.cpp:71-72`).
     */
    @Test
    fun theFirmwaresPublicKeyRuleIsEnforcedHere() {
        assertFalse(IdentityKey.isAcceptablePublicKey("00" + "11".repeat(31)))
        assertFalse(IdentityKey.isAcceptablePublicKey("ff" + "11".repeat(31)))
        assertFalse(IdentityKey.isAcceptablePublicKey("FF" + "11".repeat(31)))
        assertTrue(IdentityKey.isAcceptablePublicKey("01" + "11".repeat(31)))
        assertTrue(IdentityKey.isAcceptablePublicKey("fe" + "11".repeat(31)))
        // Only 0x00 and 0xFF whole bytes — not every key with an f or a
        // zero in it, which a substring test would have caught wrongly.
        assertTrue(IdentityKey.isAcceptablePublicKey("0f" + "11".repeat(31)))
        assertTrue(IdentityKey.isAcceptablePublicKey("f0" + "11".repeat(31)))
        // And nothing malformed is ever "acceptable".
        assertFalse(IdentityKey.isAcceptablePublicKey(null))
        assertFalse(IdentityKey.isAcceptablePublicKey(""))
        assertFalse(IdentityKey.isAcceptablePublicKey("01" + "11".repeat(30)))
        assertFalse(IdentityKey.isAcceptablePublicKey("zz" + "11".repeat(31)))
    }

    @Test
    fun theSetCommandRefusesEverythingItShould() {
        for (bad in listOf("", "short", "z".repeat(64), seed.dropLast(1), seed + "ab")) {
            assertFailsWith<IllegalArgumentException>("accepted: $bad") {
                IdentityKey.setCommand(crypto, bad)
            }
        }
        // And the degenerate ones, which are the sneaky case: they pass
        // every structural check.
        for (bad in listOf("00".repeat(32), "ff".repeat(32), "aa".repeat(64))) {
            assertFailsWith<IllegalArgumentException>("accepted degenerate: $bad") {
                IdentityKey.setCommand(crypto, bad)
            }
        }
    }

    @Test
    fun theSetCommandIsASingleLine() {
        // A newline would let a pasted key append a second command.
        val command = IdentityKey.setCommand(crypto, seed)
        assertFalse('\n' in command)
        assertFalse('\r' in command)
        assertTrue(command.startsWith("set prv.key "))
    }

    @Test
    fun generatedKeysAreDistinctAndUsable() {
        val a = IdentityKey.generate(crypto)
        val b = IdentityKey.generate(crypto)
        assertNotEquals(a, b)
        assertTrue(IdentityKey.isValidHex(a))
        assertFalse(IdentityKey.isDegenerate(a))
        // And a generated key survives the command builder.
        IdentityKey.setCommand(crypto, a)
    }

    @Test
    fun thePublicKeyIsDerivedSoAChangeCanBePreviewed() {
        val fresh = IdentityKey.generate(crypto)
        val pub = IdentityKey.publicKeyHex(crypto, fresh)
        assertTrue(pub != null && pub.length == 64, "bad public key: $pub")
        // Deterministic: the same seed always names the same node.
        assertEquals(pub, IdentityKey.publicKeyHex(crypto, fresh))
        // A different seed is a different node.
        assertNotEquals(pub, IdentityKey.publicKeyHex(crypto, IdentityKey.generate(crypto)))
        // Garbage in, null out — never a wrong answer.
        assertNull(IdentityKey.publicKeyHex(crypto, "nonsense"))
        // And the 64-byte form is honestly unanswerable rather than
        // wrongly answered: deriving from it needs a scalar
        // multiplication neither platform's Ed25519 exposes.
        assertNull(IdentityKey.publicKeyHex(crypto, expanded))
    }

    @Test
    fun theWarningsSayWhatTheyMustSay() {
        // The UI shows these verbatim; if they stop naming the
        // consequences, the screen is misleading.
        val all = IdentityKey.CHANGE_CONSEQUENCES.joinToString(" ").lowercase()
        assertTrue("re-add" in all || "add it again" in all)
        assertTrue("no revocation" in all || "no undo" in all)
        assertTrue("reboot" in all, "the change does not take effect until a reboot")
        assertTrue(IdentityKey.REVEAL_CAVEAT.lowercase().contains("impersonate"))
        // A remote read always fails, by design (CommonCLI.cpp:832), and
        // the screen has to say so or it reads as a connection problem.
        assertTrue(IdentityKey.READS_ARE_SERIAL_ONLY.lowercase().contains("serial"))
    }
}
