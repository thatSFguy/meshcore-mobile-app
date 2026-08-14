package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Entering update mode as a sequence that has to prove each step.
 *
 * The defect these are written against: the app decided a node was in
 * update mode from things that were not evidence of it — a stored BLE
 * address, and then a persisted console row. Both were permanent, and
 * both were wrong about a node that had been reflashed over USB.
 *
 * The rule now is that only the node's own behaviour counts, and each
 * step is confirmed before the next one is taken:
 *
 * 1. it answers `ver` — proof the application firmware is running, and
 *    the version is recorded while it can still be asked for;
 * 2. only then is `start ota` sent;
 * 3. it answers with `OK - mac: …` — proof it is now advertising.
 *
 * Correlation is positional and strict: the command has to be the
 * second-to-last row and its answer the last one. Anything looser reads
 * an answer from an earlier session as a fresh one, which is the whole
 * family of bugs this replaces.
 */
class OtaEntryTest {

    private var clock = 1_000_000L

    private fun sent(text: String, at: Long = clock) = ConsoleRow(true, text, at)
    private fun heard(text: String, at: Long = clock) = ConsoleRow(false, text, at)

    // A real reply from the 13 Mile repeater, not an invented one.
    private val realVer = "v1.16.0-07a3ca9 (Build: 06-Jun-2026)"
    private val realOta = "OK - mac: FF:5C:EF:28:2A:92"

    // --- the positional rule ---------------------------------------------

    @Test
    fun `the answer is the last row and the command the one before it`() {
        val rows = listOf(
            sent("board", 900_000L),
            heard("ProMicro DIY", 900_100L),
            sent("ver", 1_000_000L),
            heard(realVer, 1_000_500L),
        )
        assertEquals(realVer, OtaEvidence.answerTo("ver", rows, sentAfter = 999_999L))
    }

    @Test
    fun `a command with no answer yet is not confirmed`() {
        val rows = listOf(sent("ver"))
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 0L))
    }

    @Test
    fun `an answer that is no longer the last row is not confirmed`() {
        // Anything at all can land in the thread afterwards. The moment
        // it does the pair is history, and history is what this whole
        // mechanism exists to stop trusting.
        val rows = listOf(
            sent("ver", 1_000_000L),
            heard(realVer, 1_000_500L),
            heard("Hello from the mesh", 1_001_000L),
        )
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 999_999L))
    }

    @Test
    fun `a ver from an earlier session does not count as this one`() {
        // The pair is well formed and in the right place; it is simply
        // days old. Without the timestamp gate this is exactly the
        // "reply re-read from history" defect, one command along.
        val rows = listOf(sent("ver", 500_000L), heard(realVer, 500_400L))
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 1_000_000L))
        // ...and the same rows do confirm for the send they belong to.
        assertEquals(realVer, OtaEvidence.answerTo("ver", rows, sentAfter = 499_999L))
    }

    @Test
    fun `an outgoing row cannot be the answer`() {
        val rows = listOf(sent("ver"), sent("ver"))
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 0L))
    }

    @Test
    fun `another command in between takes the answer with it`() {
        val rows = listOf(sent("ver"), sent("board"), heard("ProMicro DIY"))
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 0L))
    }

    @Test
    fun `case and surrounding space in the command do not matter`() {
        val rows = listOf(sent("  VER "), heard(realVer))
        assertEquals(realVer, OtaEvidence.answerTo("ver", rows, sentAfter = 0L))
    }

    @Test
    fun `a thread too short to hold a pair is not confirmed`() {
        assertNull(OtaEvidence.answerTo("ver", emptyList(), sentAfter = 0L))
        assertNull(OtaEvidence.answerTo("ver", listOf(heard(realVer)), sentAfter = 0L))
    }

    @Test
    fun `the command has to be the one that was asked about`() {
        val rows = listOf(sent("start ota"), heard(realOta))
        assertNull(OtaEvidence.answerTo("ver", rows, sentAfter = 0L))
        assertEquals(realOta, OtaEvidence.answerTo("start ota", rows, sentAfter = 0L))
    }

    // --- the sequence ----------------------------------------------------

    @Test
    fun `a request waits while the console is owed a reply`() {
        // Positional correlation only works with one command in flight.
        // The panel asks a node for its `board` the moment it opens, so
        // tapping straight through would otherwise leave the thread
        // ending in two replies and neither command second-to-last.
        var state: OtaEntry = OtaEntry.Queued(since = 1_000_000L)
        val rows = mutableListOf(sent("board", 999_000L))

        state = OtaEntry.advance(state, rows, now = 1_000_100L)
        assertTrue(state is OtaEntry.Queued, "it sent into an unanswered exchange")

        rows += heard("ProMicro DIY", 1_000_200L)
        state = OtaEntry.advance(state, rows, now = 1_000_300L)
        val proving = state as OtaEntry.ProvingTheNodeAnswers
        // The clock starts when `ver` actually goes out, not when the
        // operator tapped — otherwise a slow `board` reply eats the
        // timeout budget of the command that follows it.
        assertEquals(1_000_300L, proving.sentAt)
    }

    @Test
    fun `an empty console is settled enough to start`() {
        val state = OtaEntry.advance(OtaEntry.Queued(since = 1L), emptyList(), now = 2L)
        assertTrue(state is OtaEntry.ProvingTheNodeAnswers)
    }

    @Test
    fun `a console that is never answered does not queue forever`() {
        val state = OtaEntry.Queued(since = 1_000_000L)
        val rows = listOf(sent("board", 999_000L))
        val late = OtaEntry.advance(state, rows, now = 1_000_000L + OtaEntry.ANSWER_TIMEOUT_MS)
        assertTrue(late is OtaEntry.GaveUp)
    }

    @Test
    fun `start ota is only sent once the node has answered ver`() {
        var state: OtaEntry = OtaEntry.ProvingTheNodeAnswers(sentAt = 1_000_000L)
        val rows = mutableListOf(sent("ver", 1_000_000L))

        // Nothing back yet: still waiting, and nothing has been sent.
        state = OtaEntry.advance(state, rows, now = 1_000_100L)
        assertTrue(state is OtaEntry.ProvingTheNodeAnswers)

        rows += heard(realVer, 1_000_800L)
        state = OtaEntry.advance(state, rows, now = 1_000_900L)
        val awaiting = state as OtaEntry.AwaitingUpdateMode
        assertEquals(realVer, awaiting.version)
    }

    @Test
    fun `a node that never answers ver is never asked to enter update mode`() {
        val state = OtaEntry.ProvingTheNodeAnswers(sentAt = 1_000_000L)
        val rows = listOf(sent("ver", 1_000_000L))
        val late = OtaEntry.advance(state, rows, now = 1_000_000L + OtaEntry.ANSWER_TIMEOUT_MS)
        val gaveUp = late as OtaEntry.GaveUp
        assertTrue(
            gaveUp.reason.contains("ver") && gaveUp.reason.contains("start ota"),
            "the reason has to say start ota was not sent: ${gaveUp.reason}",
        )
    }

    @Test
    fun `the mac reply is what proves update mode`() {
        val state = OtaEntry.AwaitingUpdateMode(version = realVer, sentAt = 2_000_000L)
        val rows = listOf(sent("start ota", 2_000_000L), heard(realOta, 2_000_700L))
        val done = OtaEntry.advance(state, rows, now = 2_000_800L) as OtaEntry.Confirmed
        assertEquals("FF:5C:EF:28:2A:92", done.address)
        assertEquals(realVer, done.version)
        // The reply's own arrival time, so it can be stamped as the
        // watermark that stops this same row being consumed twice.
        assertEquals(2_000_700L, done.at)
    }

    @Test
    fun `an all-zero mac is not proof of anything`() {
        // What the firmware memsets before asking the stack for its
        // address, so it is what a failed read leaves behind.
        val state = OtaEntry.AwaitingUpdateMode(version = realVer, sentAt = 2_000_000L)
        val rows = listOf(
            sent("start ota", 2_000_000L),
            heard("OK - mac: 00:00:00:00:00:00", 2_000_700L),
        )
        assertTrue(OtaEntry.advance(state, rows, now = 2_000_800L) is OtaEntry.GaveUp)
    }

    @Test
    fun `an ok with no address is the wifi route and says so`() {
        // FAQ §7.2: `start ota` on an ESP32 raises a `MeshCore OTA`
        // hotspot instead. It is a successful command that this app
        // cannot follow up on, and saying "no answer" would send someone
        // to stand next to a node holding the wrong phone.
        val state = OtaEntry.AwaitingUpdateMode(version = "v1.6.2", sentAt = 2_000_000L)
        val rows = listOf(sent("start ota", 2_000_000L), heard("OK", 2_000_700L))
        val gaveUp = OtaEntry.advance(state, rows, now = 2_000_800L) as OtaEntry.GaveUp
        assertTrue(
            gaveUp.reason.contains("192.168.4.1"),
            "the reason must point at the hotspot: ${gaveUp.reason}",
        )
    }

    @Test
    fun `a node refusing a command is reported in its own words`() {
        val state = OtaEntry.ProvingTheNodeAnswers(sentAt = 1_000_000L)
        val rows = listOf(sent("ver", 1_000_000L), heard("??: ver", 1_000_400L))
        val gaveUp = OtaEntry.advance(state, rows, now = 1_000_500L) as OtaEntry.GaveUp
        assertTrue(
            gaveUp.reason.contains("??: ver"),
            "the node's own reply is missing: ${gaveUp.reason}",
        )
    }

    @Test
    fun `a bluefruit stack that would not start is not silence`() {
        val state = OtaEntry.AwaitingUpdateMode(version = realVer, sentAt = 2_000_000L)
        val rows = listOf(sent("start ota", 2_000_000L), heard("Error", 2_000_400L))
        val gaveUp = OtaEntry.advance(state, rows, now = 2_000_500L) as OtaEntry.GaveUp
        assertTrue(gaveUp.reason.contains("Error"))
    }

    @Test
    fun `waiting is not giving up before the timeout`() {
        val state = OtaEntry.AwaitingUpdateMode(version = realVer, sentAt = 2_000_000L)
        val rows = listOf(sent("start ota", 2_000_000L))
        val still = OtaEntry.advance(state, rows, now = 2_000_000L + OtaEntry.ANSWER_TIMEOUT_MS - 1)
        assertEquals(state, still)
    }

    @Test
    fun `idle stays idle and a finished sequence does not move`() {
        val rows = listOf(sent("ver"), heard(realVer))
        assertEquals(OtaEntry.Idle, OtaEntry.advance(OtaEntry.Idle, rows, now = clock))
        val done = OtaEntry.Confirmed(realVer, "FF:5C:EF:28:2A:92", at = 1L)
        assertEquals(done, OtaEntry.advance(done, rows, now = clock))
        val gaveUp = OtaEntry.GaveUp("because")
        assertEquals(gaveUp, OtaEntry.advance(gaveUp, rows, now = clock))
    }

    @Test
    fun `a mac reply from an earlier session cannot confirm a new attempt`() {
        // The console thread is persisted. Without the timestamp gate
        // the old `OK - mac: …` sitting at the end of it would confirm
        // the sequence before `start ota` had even been answered — the
        // original defect, now inside the state machine.
        val state = OtaEntry.AwaitingUpdateMode(version = realVer, sentAt = 3_000_000L)
        val rows = listOf(sent("start ota", 500_000L), heard(realOta, 500_600L))
        assertEquals(state, OtaEntry.advance(state, rows, now = 3_000_100L))
    }

    // --- the passive path -------------------------------------------------

    @Test
    fun `a start ota typed into the console still counts once`() {
        // The operator can send `start ota` from the console tab, and
        // that is a real entry into update mode. It is consumed against
        // the watermark exactly once, so re-reading the thread on the
        // next visit does not re-assert it.
        val rows = listOf(sent("start ota", 900_000L), heard(realOta, 900_500L))
        val row = OtaEvidence.freshAdvertisingAddress(rows, handledAt = 900_000L)
        assertEquals("FF:5C:EF:28:2A:92", row?.address)
        assertEquals(900_500L, row?.at)
        assertNull(OtaEvidence.freshAdvertisingAddress(rows, handledAt = 900_500L))
        assertNull(OtaEvidence.freshAdvertisingAddress(rows, handledAt = 2_000_000L))
    }

    @Test
    fun `the newest advertising address wins`() {
        val rows = listOf(
            heard("OK - mac: AA:AA:AA:AA:AA:AA", 100L),
            heard(realOta, 200L),
        )
        assertEquals("FF:5C:EF:28:2A:92", OtaEvidence.freshAdvertisingAddress(rows, 0L)?.address)
    }

    @Test
    fun `our own outgoing text is never taken for the node's answer`() {
        // Someone pasting a MAC into the console is not the node
        // reporting one.
        val rows = listOf(sent("OK - mac: FF:5C:EF:28:2A:92", 100L))
        assertNull(OtaEvidence.freshAdvertisingAddress(rows, 0L))
    }
}
