package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the app is allowed to claim after replacing a node's identity.
 *
 * This is a report about an irreversible change to someone's hardware,
 * assembled from four steps that each half-succeed, and the expensive
 * failure is not a step failing — it is the wording overstating. A user
 * told "rebooted" who then walks away from a node that never restarted
 * has a repeater running an old key and a contact list that says
 * otherwise, and the mesh will not tell them for up to 47 hours.
 *
 * Nothing acknowledges a reboot (`IdentityKey.REBOOT_HAS_NO_ANSWER`),
 * so "confirmed" has exactly one source: a session granted by the new
 * identity. These tests exist to keep it that way.
 */
class RekeyFlowTest {

    private val newKey = "b389c985" + "7a1f0c42".repeat(7)

    private fun lines(r: RekeyFlow.Report) = RekeyFlow.describe(r).joinToString(" ")

    @Test
    fun `a refusal is the whole report`() {
        // Nothing was changed, so nothing else may be described — an
        // adoption line under a refusal reads as partial success.
        val r = RekeyFlow.Report(refusal = "The node refused the key: Error, bad key")
        assertEquals(1, RekeyFlow.describe(r).size)
        assertFalse(RekeyFlow.succeeded(r))
    }

    @Test
    fun `silence says the change may or may not have applied`() {
        // The honest answer. A node keeps its old key until it reboots,
        // so there is nothing to read back that would settle it.
        val text = lines(RekeyFlow.Report())
        assertTrue(text.contains("may or may not"), text)
        assertFalse(RekeyFlow.succeeded(RekeyFlow.Report()))
    }

    @Test
    fun `a mismatched public key stops everything and says so`() {
        // The node reported an identity that is not the one this phone
        // computed for the key it sent. One of the two is not the node
        // we think we are talking to.
        val expected = "ffffffff" + "11223344".repeat(7)
        val r = RekeyFlow.Report(newPublicKeyHex = newKey, mismatchedWith = expected)
        val text = lines(r)
        assertEquals(1, RekeyFlow.describe(r).size)
        assertTrue(text.contains("Stop."), text)
        assertTrue(text.contains(expected), text)
        assertTrue(text.contains("Nothing has been changed"), text)
        assertFalse(RekeyFlow.succeeded(r))
    }

    @Test
    fun `the new public key is always named when there is one`() {
        // It is the only copy the app gets without waiting for a flood
        // advert. Whatever else went wrong it belongs on screen.
        val r = RekeyFlow.Report(newPublicKeyHex = newKey, adopted = true)
        assertTrue(lines(r).contains(newKey))
        assertTrue(RekeyFlow.succeeded(r))
    }

    @Test
    fun `a reboot that was only sent is never described as done`() {
        // THE test in this file. Nothing acknowledges a reboot, so a
        // sent one is exactly that — and the wording has to hold even
        // though the sequence usually did work.
        for (probe in listOf(RekeyFlow.Probe.SILENT, RekeyFlow.Probe.NOT_ATTEMPTED)) {
            val text = lines(
                RekeyFlow.Report(
                    newPublicKeyHex = newKey,
                    adopted = true,
                    rebootRequested = true,
                    rebootSent = true,
                    probe = probe,
                ),
            )
            assertFalse(text.contains("Rebooted:"), "claimed a reboot on $probe: $text")
            assertTrue(text.contains("not yet confirmed"), text)
            // And it must not read as a failure either: silence is the
            // expected outcome, and a user who reads it as breakage will
            // send the command again.
            assertTrue(text.contains("not yet a failure"), text)
        }
    }

    @Test
    fun `a granted session is the one thing that confirms a reboot`() {
        // The positive control. Every other case in this file asserts
        // that the app declines to claim a reboot; without this one they
        // would all pass if it never claimed anything.
        val text = lines(
            RekeyFlow.Report(
                newPublicKeyHex = newKey,
                adopted = true,
                rebootRequested = true,
                rebootSent = true,
                probe = RekeyFlow.Probe.CONFIRMED,
            ),
        )
        assertTrue(text.contains("Rebooted:"), text)
        assertTrue(text.contains("answered a sign-in"), text)
    }

    @Test
    fun `a rejected password still proves the node restarted`() {
        // Something answered as an identity that did not exist before,
        // which cannot happen unless the reboot happened. The password
        // is a separate problem and is described as one.
        val text = lines(
            RekeyFlow.Report(
                newPublicKeyHex = newKey,
                adopted = true,
                rebootRequested = true,
                rebootSent = true,
                probe = RekeyFlow.Probe.REJECTED,
            ),
        )
        assertTrue(text.contains("has rebooted"), text)
        assertTrue(text.contains("sign in again"), text)
    }

    @Test
    fun `no password means unchecked rather than unconfirmed`() {
        // Different problem with a different fix: there was nothing to
        // probe with, so telling the user to wait would be wrong.
        val text = lines(
            RekeyFlow.Report(
                newPublicKeyHex = newKey,
                adopted = true,
                rebootRequested = true,
                rebootSent = true,
                probe = RekeyFlow.Probe.NO_PASSWORD,
            ),
        )
        assertTrue(text.contains("no saved password"), text)
    }

    @Test
    fun `declining the reboot says the node still runs the old key`() {
        val text = lines(RekeyFlow.Report(newPublicKeyHex = newKey, adopted = true))
        assertTrue(text.contains("still running its old key"), text)
        assertFalse(text.contains("Reboot sent"), text)
    }

    @Test
    fun `a reboot this radio would not transmit is not silence`() {
        // Our own radio refusing to send is a local failure with a local
        // fix. Reporting it as an unconfirmed reboot would send the user
        // looking at the repeater.
        val text = lines(
            RekeyFlow.Report(
                newPublicKeyHex = newKey,
                adopted = true,
                rebootRequested = true,
                rebootSent = false,
            ),
        )
        assertTrue(text.contains("NOT sent"), text)
        assertTrue(text.contains("still running its old key"), text)
    }

    @Test
    fun `a failed adoption is stated rather than glossed over`() {
        // The adoption is the entire reason the user is not waiting for
        // an advert. If it did not happen they are waiting after all and
        // need to know now.
        val text = lines(RekeyFlow.Report(newPublicKeyHex = newKey, adopted = false))
        assertTrue(text.contains("could NOT be written"), text)
        assertFalse(text.contains("The old entry is left alone"), text)
    }

    @Test
    fun `the old entry is explained whenever a new one was added`() {
        // It is deliberately kept — until the node restarts it is still
        // the live one — and an unexplained duplicate in the node list
        // looks like a bug.
        val text = lines(RekeyFlow.Report(newPublicKeyHex = newKey, adopted = true))
        assertTrue(text.contains("old entry is left alone"), text)
        assertTrue(text.contains("Delete it once"), text)
    }
}
