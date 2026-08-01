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
 * Every test here is about refusing something. Applying a wrong key to
 * a repeater is not recoverable from the app, from the mesh, or at all:
 * the node becomes a stranger to everyone who knew it, and there is no
 * revocation in the protocol.
 */
class IdentityKeyTest {

    private val crypto = AndroidCryptoProvider()
    // A varied key on purpose: "4f".repeat(32) is 64 valid hex
    // characters AND all one byte, so it trips the degenerate check —
    // which is exactly the trap this constant used to fall into.
    private val valid = "0123456789abcdef".repeat(4)

    @Test
    fun acceptsAWellFormedKey() {
        assertEquals(valid, IdentityKey.canonicalHex(valid))
        assertTrue(IdentityKey.isValidHex(valid))
    }

    @Test
    fun toleratesThePastingArtefactsPeopleActuallyProduce() {
        assertEquals(valid, IdentityKey.canonicalHex(valid.uppercase()))
        assertEquals(valid, IdentityKey.canonicalHex("  $valid  "))
        assertEquals(valid, IdentityKey.canonicalHex("0x$valid"))
        assertEquals(valid, IdentityKey.canonicalHex("0X$valid"))
        // Grouped with spaces or colons, as key material often is.
        assertEquals(valid, IdentityKey.canonicalHex(valid.chunked(4).joinToString(" ")))
        assertEquals(valid, IdentityKey.canonicalHex(valid.chunked(2).joinToString(":")))
    }

    @Test
    fun aShortKeyIsNeverPaddedIntoAValidOne() {
        // Silent padding would hand the node an identity nobody chose.
        for (n in listOf(0, 1, 2, 31, 62, 63)) {
            assertNull(IdentityKey.canonicalHex("a".repeat(n)), "accepted $n chars")
        }
        assertNull(IdentityKey.canonicalHex(valid.dropLast(2)))
        assertNull(IdentityKey.canonicalHex(valid + "ab"))
    }

    @Test
    fun nonHexIsRefused() {
        for (bad in listOf(
            "z".repeat(64), "g".repeat(64), null, "", "   ",
            valid.dropLast(1) + "!", valid.dropLast(1) + "g",
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
        assertFalse(IdentityKey.isDegenerate(valid.dropLast(2) + "ab"))
        assertFalse(IdentityKey.isDegenerate(IdentityKey.generate(crypto)))
    }

    @Test
    fun theSetCommandRefusesEverythingItShould() {
        assertEquals("set prv.key $valid", IdentityKey.setCommand(valid))
        assertEquals("set prv.key $valid", IdentityKey.setCommand(valid.uppercase()))

        for (bad in listOf("", "short", "z".repeat(64), valid.dropLast(1))) {
            assertFailsWith<IllegalArgumentException>("accepted: $bad") {
                IdentityKey.setCommand(bad)
            }
        }
        // And the degenerate ones, which are the sneaky case: they pass
        // every structural check.
        for (bad in listOf("00".repeat(32), "ff".repeat(32))) {
            assertFailsWith<IllegalArgumentException>("accepted degenerate: $bad") {
                IdentityKey.setCommand(bad)
            }
        }
    }

    @Test
    fun theSetCommandIsASingleLine() {
        // A newline would let a pasted key append a second command.
        val command = IdentityKey.setCommand(valid)
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
        IdentityKey.setCommand(a)
    }

    @Test
    fun thePublicKeyIsDerivedSoAChangeCanBePreviewed() {
        val seed = IdentityKey.generate(crypto)
        val pub = IdentityKey.publicKeyHex(crypto, seed)
        assertTrue(pub != null && pub.length == 64, "bad public key: $pub")
        // Deterministic: the same seed always names the same node.
        assertEquals(pub, IdentityKey.publicKeyHex(crypto, seed))
        // A different seed is a different node.
        assertNotEquals(pub, IdentityKey.publicKeyHex(crypto, IdentityKey.generate(crypto)))
        // Garbage in, null out — never a wrong answer.
        assertNull(IdentityKey.publicKeyHex(crypto, "nonsense"))
    }

    @Test
    fun theWarningsSayWhatTheyMustSay() {
        // The UI shows these verbatim; if they stop naming the
        // consequences, the screen is misleading.
        val all = IdentityKey.CHANGE_CONSEQUENCES.joinToString(" ").lowercase()
        assertTrue("re-add" in all || "add it again" in all)
        assertTrue("no revocation" in all || "no undo" in all)
        assertTrue(IdentityKey.REVEAL_CAVEAT.lowercase().contains("impersonate"))
    }
}
