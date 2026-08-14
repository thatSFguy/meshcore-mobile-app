package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Learning what a remote node is, from its console replies.
 *
 * This is what scopes the firmware picker down to one board. Get it
 * wrong and the operator is shown forty builds for a node they cannot
 * see, which is precisely the situation where picking by eye goes
 * wrong.
 *
 * The reply formats are the firmware's, not a guess (`CommonCLI.cpp`):
 * `board` prints `getManufacturerName()` bare, and `ver` prints
 * `"%s (Build: %s)"`.
 */
class NodeIdentityRepliesTest {

    private fun thread(vararg lines: Pair<Boolean, String>) =
        NodeIdentityReplies.from(lines.toList())

    @Test
    fun `board and version are read from the replies that follow them`() {
        val id = thread(
            true to "board",
            false to "ProMicro DIY",
            true to "ver",
            false to "v1.16.0-07a3ca9 (Build: 06-Jun-2026)",
        )
        assertEquals("ProMicro DIY", id.board)
        assertEquals("v1.16.0-07a3ca9", id.version)
        assertEquals("06-Jun-2026", id.buildDate)
        assertEquals("ProMicro DIY · v1.16.0-07a3ca9", id.describe())
    }

    @Test
    fun `the board name is the one the asset table is keyed on`() {
        // `board` returns the same string a companion reports in
        // DEVICE_INFO, so one table serves both. If that ever stops
        // being true, this is the test that says so.
        val id = thread(true to "board", false to "ProMicro DIY")
        assertEquals(listOf("ProMicro"), BoardAssets.prefixesFor(id.board))
    }

    @Test
    fun `a reply is matched to the command that asked for it`() {
        // Interleaved with other console traffic. A reply belongs to the
        // command before it and to nothing else.
        val id = thread(
            true to "get freq",
            false to "> 910.525",
            true to "board",
            false to "RAK 4631",
            true to "get name",
            false to "Some Repeater",
        )
        assertEquals("RAK 4631", id.board)
        assertNull(id.version)
    }

    @Test
    fun `two commands in flight are answered in the order they were sent`() {
        // This is what the panel actually does — it asks `board` and
        // `ver` back to back the moment it opens, and the firmware's
        // serialized queue answers them in order. A single-slot
        // expectation gave the board name to `ver` and dropped the
        // version entirely, which reads on screen as a node that simply
        // never reported its firmware.
        val id = thread(
            true to "board",
            true to "ver",
            false to "ProMicro DIY",
            false to "v1.16.0-07a3ca9 (Build: 06-Jun-2026)",
        )
        assertEquals("ProMicro DIY", id.board)
        assertEquals("v1.16.0-07a3ca9", id.version)
        assertEquals("06-Jun-2026", id.buildDate)
    }

    @Test
    fun `an ota reply is never filed as a board name`() {
        // Seen on a live repeater: the Firmware screen read
        // "OK - mac: FF:5C:EF:28:2A:92 · v1.15.0-dee3e26", and the value
        // was persisted — so the build picker, which narrows on the
        // board name, offered all thirty-one boards in the release
        // instead of the one. An unanswered `board` leaves the queue
        // holding an expectation, and the next unrelated reply satisfies
        // it; here that reply was the node reporting the address it had
        // begun advertising on.
        for (reply in listOf(
            "OK - mac: FF:5C:EF:28:2A:92",
            "OK",
            "ok - mac: 00:11:22:33:44:55",
        )) {
            assertNull(thread(true to "board", false to reply).board, "accepted \"$reply\"")
        }
        // And a real one still gets through.
        assertEquals("ProMicro DIY", thread(true to "board", false to "ProMicro DIY").board)
    }

    @Test
    fun `an ota reply is never filed as a firmware version either`() {
        // The mirror of the test above, and the half that was missing.
        // `OK - mac: …` was refused as a BOARD name and accepted as a
        // VERSION, so the Firmware screen read
        // "ProMicro DIY · OK - mac: FF:5C:EF:28:2A:92" on a live
        // repeater — and that string is what gets stored against the
        // contact as its firmware version and compared against release
        // tags.
        //
        // It is reachable from the app's own update sequence, not from
        // anything unusual: `ver` is sent, then `start ota`, and if the
        // first goes unanswered the second's reply satisfies it.
        for (reply in listOf(
            "OK - mac: FF:5C:EF:28:2A:92",
            "OK",
            "ProMicro DIY",
            "> 910.525",
        )) {
            assertNull(thread(true to "ver", false to reply).version, "accepted \"$reply\"")
        }
        // And real ones still get through, with and without a build.
        assertEquals(
            "v1.16.0-07a3ca9",
            thread(true to "ver", false to "v1.16.0-07a3ca9 (Build: 06-Jun-2026)").version,
        )
        assertEquals("v1.17.0", thread(true to "ver", false to "v1.17.0").version)
        assertEquals("1.17.0", thread(true to "ver", false to "1.17.0").version)
    }

    @Test
    fun `a command we do not track still consumes its own reply`() {
        // `get freq` is not ours, but it is owed an answer, and that
        // answer is not the board's. Dropping the expectation instead of
        // queueing it hands `> 910.525` to whatever asked last.
        val id = thread(
            true to "board",
            true to "get freq",
            false to "ProMicro DIY",
            false to "> 910.525",
        )
        assertEquals("ProMicro DIY", id.board)
        assertNull(id.version)
    }

    @Test
    fun `an answer of the wrong shape is not filed under the wrong command`() {
        // Observed on hardware: the Firmware screen read
        // "v1.15.0-dee3e26 (Build: 19-Apr-2026) · ProMicro DIY" — the
        // two answers the wrong way round, and the wrong one persisted
        // against the contact as its board. The cause was two commands
        // sent as two coroutines, whose console rows raced and landed in
        // the opposite order to the sends.
        //
        // Sending them one at a time is the fix. This is the guard for
        // when something else finds a way to get them out of order:
        // a version string is not a board name, and a board name is not
        // a version, and either is recognisable on sight.
        val swapped = thread(
            true to "board",
            true to "ver",
            false to "v1.15.0-dee3e26 (Build: 19-Apr-2026)",
            false to "ProMicro DIY",
        )
        assertNull(swapped.board, "a version string was accepted as a board name")
        assertNull(swapped.version, "a board name was accepted as a version")
    }

    @Test
    fun `a node that does not know the command reports nothing`() {
        // "??: board" is the firmware's unknown-command reply. Reading
        // it as a board name would send the picker looking for a build
        // called "??: board".
        for (refusal in listOf("??: board", "??", "Error", "(ERR: whatever)", "", "   ")) {
            val id = thread(true to "board", false to refusal)
            assertNull(id.board, "accepted \"$refusal\" as a board")
        }
    }

    @Test
    fun `the newest answer wins`() {
        // The console thread is durable, so an answer from last week is
        // still in it. A node that has been reflashed since must not be
        // described by what it used to be.
        val id = thread(
            true to "board",
            false to "RAK 4631",
            true to "ver",
            false to "v1.15.0 (Build: 01-Jan-2026)",
            true to "board",
            false to "ProMicro DIY",
            true to "ver",
            false to "v1.16.0 (Build: 06-Jun-2026)",
        )
        assertEquals("ProMicro DIY", id.board)
        assertEquals("v1.16.0", id.version)
    }

    @Test
    fun `a version with no build suffix still reads`() {
        val id = thread(true to "ver", false to "v1.17.0")
        assertEquals("v1.17.0", id.version)
        assertNull(id.buildDate)
    }

    @Test
    fun `an unanswered command claims nothing`() {
        assertEquals(NodeIdentityReplies(null, null, null), thread(true to "board", true to "ver"))
        assertNull(thread().describe())
        // A reply with no command before it is not an answer to us.
        assertNull(thread(false to "ProMicro DIY").board)
    }

    @Test
    fun `the reported version compares against a release tag`() {
        // The node reports "v1.16.0-07a3ca9"; the tag is "v1.16.0".
        // String equality says these differ, which would show the
        // installed version as "Older" in the version list.
        val id = thread(true to "ver", false to "v1.16.0-07a3ca9 (Build: 06-Jun-2026)")
        assertEquals(VersionOrder.key("v1.16.0"), VersionOrder.key(id.version!!))
    }
}
