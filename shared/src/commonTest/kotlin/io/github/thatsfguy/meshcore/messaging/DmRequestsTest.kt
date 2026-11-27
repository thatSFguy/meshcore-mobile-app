package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.presentation.DmRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        val known: MutableSet<String> = mutableSetOf(),
    ) : DmRequests.Radio {
        val added = mutableListOf<Pair<String, String>>()
        override fun hasContact(keyHex: String): Boolean = keyHex.lowercase() in known
        val posts = mutableListOf<Pair<Int, String>>()
        override suspend fun addContact(keyHex: String, name: String): Boolean {
            added += keyHex to name
            if (addOk) known += keyHex.lowercase()
            return addOk
        }
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
        assertEquals("33".repeat(32), dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = now * 1000))
        val msg = dm.accept(2, "Amy", "33".repeat(32), now + 5)
        assertEquals("Added Amy. You can message each other directly.", msg)
        assertEquals(DmRequest.accept("Amy", "33".repeat(32), me), r.posts.single().second)
        assertNull(dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = now * 1000), "answered requests lose their buttons")
    }

    @Test
    fun aFailedAddPostsNoAccept() = runTest {
        val r = FakeRadio(me, "Bob", addOk = false)
        val msg = DmRequests(r).accept(2, "Amy", "33".repeat(32), now)
        assertTrue(msg.startsWith("Couldn't add Amy"))
        assertTrue(r.posts.isEmpty(), "an accept without the contact would mislead them")
    }

    @Test
    fun aRequestForSomeoneElseOffersNoButtonsButStillReadsAsALine() {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        val request = DmRequest.request("Zed", bob)!!
        assertNull(dm.pendingAsker(request, "Bob", outgoing = false, receivedAtMs = now * 1000))
        assertEquals("Bob asked Zed to message privately.", dm.line(request, "Bob", outgoing = false))
        assertNull(dm.line("just chatting", "Bob", outgoing = false))
    }

    @Test
    fun stateSurvivesARoundTrip() = runTest {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        dm.ask(1, "Bob", now)
        dm.decline("44".repeat(32), now)
        val back = DmRequests(FakeRadio(me, "Rob"))
        back.outstandingRaw = dm.outstandingRaw
        back.handledRaw = dm.handledRaw
        assertEquals(dm.outstandingRaw, back.outstandingRaw)
        assertEquals(dm.handledRaw, back.handledRaw)
        back.handledRaw = "junk\n" + "44".repeat(32) + "=$now"
        assertEquals("44".repeat(32) + "=$now", back.handledRaw)
    }

    @Test
    fun aKnownContactIsNotAskedAgain() {
        val dm = DmRequests(FakeRadio(me, "Rob"))
        assertTrue(dm.canAsk("Bob", listOf("Amy")))
        assertFalse(dm.canAsk("Bob", listOf("bob")), "already a contact by that name: just open the chat")
        assertFalse(dm.canAsk("", emptyList()))
    }

    @Test
    fun showingMyCodeIsAnInvitationAnyoneMayAnswer() = runTest {
        assertNull(DmRequests(FakeRadio(null, "Rob")).myCode(now), "no radio, no code")
        val r = FakeRadio(me, "Rob")
        val dm = DmRequests(r)
        val code = assertNotNull(dm.myCode(now))
        assertTrue(code.startsWith("meshcore://"))
        assertTrue(me in code.lowercase())
        // Before: an unsolicited accept added no one (see above). After the
        // code was shown, an answer from whoever scanned it adds them.
        val accept = DmRequest.accept("Rob", me, bob)!!
        assertEquals("Bob accepted. You can message each other directly.", dm.onChannelPost("Bob", accept, now))
        assertEquals(listOf(bob to "Bob"), r.added)
    }

    // --- A lost accept (2026-11-26) ------------------------------------
    //
    // An accept is a channel post with no ACK. When it was lost, the
    // requester never added us, and asking again used to do nothing: the
    // new request read as already answered because "handled" was a set of
    // keys. These pin both halves of the recovery.

    private val amy = "33".repeat(32)

    @Test
    fun aRequestReceivedAfterWeAnsweredOffersTheButtonsAgain() = runTest {
        val r = FakeRadio(me, "Bob")
        val dm = DmRequests(r)
        val request = DmRequest.request("Bob", amy)!!
        dm.accept(2, "Amy", amy, now + 10)
        assertNull(
            dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = now * 1000),
            "the post we answered stays answered",
        )
        // Positive control: without it, a feature that never reopened
        // anything would pass the line above.
        assertEquals(
            amy,
            dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = (now + 600) * 1000),
            "asked again after our answer: a new question",
        )
    }

    @Test
    fun answeringAgainPostsTheAcceptWithoutReAddingThem() = runTest {
        val r = FakeRadio(me, "Bob")
        val dm = DmRequests(r)
        assertEquals("Added Amy. You can message each other directly.", dm.accept(2, "Amy", amy, now))
        val second = dm.accept(2, "Amy", amy, now + 600)
        assertEquals("Answered Amy again. You can message each other directly.", second)
        assertEquals(1, r.added.size, "re-adding would reset the route and name the radio learned")
        assertEquals(listOf(DmRequest.accept("Amy", amy, me), DmRequest.accept("Amy", amy, me)), r.posts.map { it.second })
    }

    @Test
    fun aDeclinedRequesterWhoAsksAgainIsAskedAboutAgain() {
        val dm = DmRequests(FakeRadio(me, "Bob"))
        val request = DmRequest.request("Bob", amy)!!
        dm.decline(amy, now)
        assertNull(dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = (now - 30) * 1000))
        assertEquals(amy, dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = (now + 30) * 1000))
    }

    @Test
    fun anAcceptFromSomeoneAlreadyAddedClosesTheRequestWithoutReAdding() = runTest {
        val r = FakeRadio(me, "Rob", known = mutableSetOf(bob))
        val dm = DmRequests(r)
        dm.ask(2, "Bob", now)
        val accept = DmRequest.accept("Rob", me, bob)!!
        assertEquals("Bob accepted. You can message each other directly.", dm.onChannelPost("Bob", accept, now))
        assertTrue(r.added.isEmpty())
        assertFalse(dm.outstandingRaw.contains("bob"))
    }

    @Test
    fun oldAnswersAreConvertedOnceAndSaved() {
        // iOS loads state, THEN attaches onStateChanged. The conversion
        // must be saved at that moment, or every launch re-stamps the old
        // entries and a request received between launches reads as answered.
        val update = now
        val dm = DmRequests(FakeRadio(me, "Bob"), nowSec = { update })
        dm.handledRaw = amy
        var saved = 0
        dm.onStateChanged = { saved++ }
        assertEquals(1, saved, "saved as soon as there is somewhere to save it")
        assertEquals("$amy=$update", dm.handledRaw)
        val request = DmRequest.request("Bob", amy)!!
        assertNull(dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = (update - 60) * 1000))
        assertEquals(amy, dm.pendingAsker(request, "Amy", outgoing = false, receivedAtMs = (update + 60) * 1000))

        // A second launch reading the saved form converts nothing.
        val again = DmRequests(FakeRadio(me, "Bob"), nowSec = { update + 10_000 })
        again.handledRaw = dm.handledRaw
        var savedAgain = 0
        again.onStateChanged = { savedAgain++ }
        assertEquals(0, savedAgain)
        assertEquals("$amy=$update", again.handledRaw, "not re-stamped")
    }
}
