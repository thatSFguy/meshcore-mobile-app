package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.DmRequest.Action
import io.github.thatsfguy.meshcore.presentation.DmRequest.Outstanding
import io.github.thatsfguy.meshcore.presentation.DmRequest.Parsed
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmRequestTest {

    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val now = 1_800_000_000L

    /** The longest name a node can carry: 31 bytes. */
    private val longest = "N".repeat(DmRequest.MAX_NAME_BYTES)

    // --- the wire text ------------------------------------------------------

    @Test
    fun `a request reads as a mention and carries the full key`() {
        assertEquals("DM request for @[Bob] mch:r:$alice", DmRequest.request("Bob", alice))
    }

    @Test
    fun `a reply addresses the requester by key prefix`() {
        assertEquals(
            "DM accepted @[Alice] mch:a:${alice.take(8)}:$bob",
            DmRequest.accept("Alice", alice, bob),
        )
    }

    @Test
    fun `both forms round-trip through the parser`() {
        assertEquals(Parsed.Request("Bob", alice), DmRequest.parse(DmRequest.request("Bob", alice)!!))
        assertEquals(
            Parsed.Accept("Alice", alice.take(8), bob),
            DmRequest.parse(DmRequest.accept("Alice", alice, bob)!!),
        )
    }

    @Test
    fun `both forms arrive whole with the longest names on both ends`() {
        // The firmware cuts a channel message to 160 bytes INCLUDING
        // "<sender>: " without a word (BaseChatMesh::sendGroupMessage). A
        // key cut short would be a request nobody can answer.
        val req = DmRequest.request(longest, alice)!!
        val acc = DmRequest.accept(longest, alice, bob)!!
        assertTrue(DmRequest.fits(longest, req), "request is ${"$longest: $req".length} bytes")
        assertTrue(DmRequest.fits(longest, acc), "accept is ${"$longest: $acc".length} bytes")
        // And the check itself bites one byte over.
        assertTrue(!DmRequest.fits(longest, acc + "x".repeat(Codes.MAX_TEXT_PAYLOAD_BYTES)))
    }

    @Test
    fun `names that cannot be addressed are refused rather than sent broken`() {
        assertNull(DmRequest.request("", alice))
        assertNull(DmRequest.request("[odd]", alice))
        assertNull(DmRequest.request(longest + "N", alice))
        // A multibyte name over the limit in bytes though not in characters.
        assertNull(DmRequest.request("é".repeat(16), alice))
        assertNull(DmRequest.request("Bob", "a1".repeat(31)))
        assertNull(DmRequest.request("Bob", "zz".repeat(32)))
        assertNull(DmRequest.accept("Alice", alice, "abc"))
    }

    @Test
    fun `ordinary and near-miss messages are not requests`() {
        assertNull(DmRequest.parse("hello"))
        assertNull(DmRequest.parse("DM request for @[Bob] mch:r:${alice.dropLast(1)}"))
        assertNull(DmRequest.parse("DM request for @[Bob] mch:r:${alice}0"))
        assertNull(DmRequest.parse("DM request for @[Bob] mch:r:${alice.uppercase()}"))
        assertNull(DmRequest.parse("> quoted\nDM request for @[Bob] mch:r:$alice"))
        assertNull(DmRequest.parse("DM accepted @[Alice] mch:a:${alice.take(7)}:$bob"))
        assertNull(DmRequest.parse("x".repeat(500)))
    }

    // --- deciding -----------------------------------------------------------

    private fun decide(
        text: String,
        sender: String,
        myName: String,
        myKey: String,
        outstanding: Outstanding = Outstanding.NONE,
    ) = DmRequest.decide(DmRequest.parse(text)!!, sender, myName, myKey, outstanding, now)

    @Test
    fun `a request for me asks me`() {
        // Positive control for every "None" below.
        val text = DmRequest.request("Bob", alice)!!
        assertEquals(Action.AskedMe("Alice", alice), decide(text, "Alice", "bob", bob))
    }

    @Test
    fun `a request for someone else or my own echo asks nothing`() {
        val text = DmRequest.request("Bob", alice)!!
        assertEquals(Action.None, decide(text, "Alice", "Carol", "c2".repeat(32)))
        assertEquals(Action.None, decide(text, "Alice", "Bob", alice))
    }

    @Test
    fun `a reply to my request adds them`() {
        val asked = Outstanding.NONE.asked("Bob", now - 60)
        val text = DmRequest.accept("Alice", alice, bob)!!
        assertEquals(Action.TheyAccepted("Bob", bob), decide(text, "Bob", "Alice", alice, asked))
    }

    @Test
    fun `an accept nobody asked for adds no one`() {
        // Otherwise anyone on the channel could put themselves in our
        // contacts by posting an "accepted" with our key prefix.
        val text = DmRequest.accept("Alice", alice, bob)!!
        assertEquals(Action.None, decide(text, "Bob", "Alice", alice))
        // Asked someone else.
        assertEquals(
            Action.None,
            decide(text, "Bob", "Alice", alice, Outstanding.NONE.asked("Carol", now)),
        )
        // Addressed to a different key.
        val other = Outstanding.NONE.asked("Bob", now)
        assertEquals(Action.None, decide(text, "Bob", "Alice", "a2".repeat(32), other))
    }

    @Test
    fun `a stale request no longer admits a reply`() {
        val old = Outstanding.NONE.asked("Bob", now - Outstanding.LIFETIME_SEC - 1)
        val text = DmRequest.accept("Alice", alice, bob)!!
        assertEquals(Action.None, decide(text, "Bob", "Alice", alice, old))
    }

    @Test
    fun `a shared link admits a reply from whoever tapped it`() {
        val shared = Outstanding.NONE.sharedLink(now)
        val text = DmRequest.accept("Alice", alice, bob)!!
        assertEquals(Action.TheyAccepted("Bob", bob), decide(text, "Bob", "Alice", alice, shared))
        // And stays open for the next person once one has answered.
        assertTrue(shared.answered("Bob", now).answers("Carol", now))
    }

    @Test
    fun `an answered request is closed`() {
        val asked = Outstanding.NONE.asked("Bob", now).answered("bob ", now)
        assertTrue(!asked.answers("Bob", now))
    }

    // --- the line a thread shows -------------------------------------------

    @Test
    fun `each side reads the exchange in its own words`() {
        val req = DmRequest.parse(DmRequest.request("Bob", alice)!!)!!
        val acc = DmRequest.parse(DmRequest.accept("Alice", alice, bob)!!)!!
        assertEquals("You asked Bob to message privately.", DmRequest.describe(req, "Alice", true, "Alice", alice))
        assertEquals("Alice wants to message you privately.", DmRequest.describe(req, "Alice", false, "BOB", bob))
        assertEquals("Alice asked Bob to message privately.", DmRequest.describe(req, "Alice", false, "Carol", "c2".repeat(32)))
        assertEquals(
            "Bob accepted your request. You can message each other directly.",
            DmRequest.describe(acc, "Bob", false, "Alice", alice),
        )
        assertEquals(
            "Bob accepted Alice's request to message privately.",
            DmRequest.describe(acc, "Bob", false, "Carol", "c2".repeat(32)),
        )
        assertEquals(
            "You accepted Alice's request to message privately.",
            DmRequest.describe(acc, "Bob", true, "Bob", bob),
        )
    }
}
