package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.DmRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmRequestsTest {

    private val me = "11".repeat(32)
    private val bob = "22".repeat(32)
    private val now = 1_770_000_000L

    private class FakeRadio(
        override val myKeyHex: String?,
        override val myName: String?,
        var addOk: Boolean = true,
    ) : DmRequests.Radio {
        val added = mutableListOf<Pair<String, String>>()
        val posts = mutableListOf<Pair<Int, String>>()
        override suspend fun addContact(keyHex: String, name: String): Boolean { added += keyHex to name; return addOk }
        override suspend fun sendChannel(index: Int, text: String): Boolean { posts += index to text; return true }
    }

    @Test
    fun askingPostsTheRequestAndRemembersIt() = runTest {
        val r = FakeRadio(me, "Rob")
        val dm = DmRequests(r)
        var saved = 0
        dm.onStateChanged = { saved++ }
        val msg = dm.ask(2, "Bob", now)
        assertEquals("Asked Bob. You can message them once they accept.", msg)
        assertEquals(DmRequest.request("Bob", me), r.posts.single().second)
        assertTrue(dm.outstandingRaw.startsWith("bob="), dm.outstandingRaw)
        assertTrue(saved > 0)
    }

    @Test
    fun theirAcceptAddsThemOnlyForARequestWeMade() = runTest {
        val r = FakeRadio(me, "Rob")
        val dm = DmRequests(r)
        val accept = DmRequest.accept("Rob", me, bob)!!
        // Unsolicited: nobody added — a stranger can't push a contact onto us.
        assertNull(dm.onChannelPost("Bob", accept, now))
        assertTrue(r.added.isEmpty())
        dm.ask(2, "Bob", now)
        assertEquals("Bob accepted. You can message each other directly.", dm.onChannelPost("Bob", accept, now))
        assertEquals(listOf(bob to "Bob"), r.added)
        assertFalse(dm.outstandingRaw.contains("bob"), "answered, so no longer outstanding")
    }

    @Test
    fun acceptingAddsThemFirstAndOnlyThenAnswers() = runTest {
        val r = FakeRadio(me, "Bob")
        val dm = DmRequests(r)
        val request = DmRequest.request("Bob", "33".repeat(32))!!
        assertEquals("33".repeat(32), dm.pendingAsker(request, "Amy", outgoing = false))
        val msg = dm.accept(2, "Amy", "33".repeat(32))
        assertEquals("Added Amy. You can message each other directly.", msg)
        assertEquals(DmRequest.accept("Amy", "33".repeat(32), me), r.posts.single().second)
        assertNull(dm.pendingAsker(request, "Amy", outgoing = false), "answered requests lose their buttons")
    }

    @Test
    fun aFailedAddPostsNoAccept() = runTest {
        val r = FakeRadio(me, "Bob", addOk = false)
        val msg = DmRequests(r).accept(2, "Amy", "33".repeat(32))
        assertTrue(msg.startsWith("Couldn't add Amy"))
        assertTrue(r.posts.isEmpty(), "an accept without the contact would mislead them")
    }

    @Test
    fun aRequestForSomeoneElseOffersNoButtonsButStillReadsAsALine() {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        val request = DmRequest.request("Zed", bob)!!
        assertNull(dm.pendingAsker(request, "Bob", outgoing = false))
        assertEquals("Bob asked Zed to message privately.", dm.line(request, "Bob", outgoing = false))
        assertNull(dm.line("just chatting", "Bob", outgoing = false))
    }

    @Test
    fun stateSurvivesARoundTrip() = runTest {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        dm.ask(1, "Bob", now)
        dm.decline("44".repeat(32))
        val back = DmRequests(FakeRadio(me, "Rob"))
        back.outstandingRaw = dm.outstandingRaw
        back.handledRaw = dm.handledRaw
        assertEquals(dm.outstandingRaw, back.outstandingRaw)
        assertEquals(dm.handledRaw, back.handledRaw)
        back.handledRaw = "junk\n" + "44".repeat(32)
        assertEquals("44".repeat(32), back.handledRaw)
    }

    @Test
    fun aKnownContactIsNotAskedAgain() {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        assertTrue(dm.canAsk("Bob", listOf("Amy")))
        assertFalse(dm.canAsk("Bob", listOf("bob")), "already a contact by that name: just open the chat")
        assertFalse(dm.canAsk("", emptyList()))
    }
}
