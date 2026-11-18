package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.Reactions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageBookTest {

    private var clock = 1_000L
    private fun book(keep: Int = MessageBook.DEFAULT_KEEP_PER_THREAD) =
        MessageBook(keepPerThread = keep, nowMs = { clock++ })

    private val dm = "dm|aabbccddeeff"
    private val ch = "ch|0"

    @Test
    fun anInboundMessageBecomesARowAndCountsAsUnread() {
        val b = book()
        assertEquals(MessageBook.Inbound.Added, b.receive(dm, false, null, "hello", 100, "k1"))
        assertEquals(listOf("hello"), b.thread(dm).map { it.text })
        assertEquals(1, b.unread.value[dm])
        assertEquals(1, b.unreadTotal())
    }

    @Test
    fun aRetriedMessageIsOneRowWithTwoCopies() {
        val b = book()
        b.receive(dm, false, null, "hello", 100, "k1")
        assertEquals(MessageBook.Inbound.Duplicate, b.receive(dm, false, null, "hello", 100, "k1", hops = 3))
        val rows = b.thread(dm)
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].copies)
        // The later copy fills in what the first did not know.
        assertEquals(3, rows[0].hops)
        assertEquals(1, b.unread.value[dm], "a copy must not count as another unread")
    }

    @Test
    fun aMessageWithNoKeyIsNeverMergedWithAnother() {
        // A null key cannot be deduped; two of them are two messages.
        val b = book()
        b.receive(dm, false, null, "same", 100, null)
        b.receive(dm, false, null, "same", 100, null)
        assertEquals(2, b.thread(dm).size)
    }

    @Test
    fun theEchoOfOurOwnChannelPostIsNotANewMessage() {
        val b = book()
        val id = b.addOutgoing(ch, "Me", "hi all", 200, contentKey = "ck")
        assertEquals(MessageBook.Inbound.Duplicate, b.receive(ch, true, "Me", "hi all", 200, "ck"))
        val row = b.thread(ch).single()
        assertEquals(id, row.id)
        assertTrue(row.outgoing)
        assertEquals(1, row.copies)
        assertNull(b.unread.value[ch])
    }

    @Test
    fun theOpenThreadAccruesNoUnread() {
        val b = book()
        b.activeThread = dm
        b.receive(dm, false, null, "hello", 100, "k1")
        assertNull(b.unread.value[dm])
        b.receive(ch, true, "Bob", "elsewhere", 100, "k2")
        assertEquals(1, b.unread.value[ch])
    }

    @Test
    fun openingAThreadMarksItRead() {
        val b = book()
        b.receive(dm, false, null, "a", 100, "k1")
        b.receive(dm, false, null, "b", 101, "k2")
        assertEquals(2, b.unread.value[dm])
        b.activeThread = dm
        assertNull(b.unread.value[dm])
    }

    @Test
    fun aReactionAttachesToItsTargetInsteadOfBecomingText() {
        val b = book()
        b.receive(ch, true, "Bob", "lunch at noon?", 1_700_000_000, "k1")
        val hash = Reactions.targetHash(1_700_000_000, "Bob", "lunch at noon?")
        val wire = Reactions.encode(hash, Reactions.ALL[0])!!
        assertEquals(MessageBook.Inbound.Reacted, b.receive(ch, true, "Ann", wire, 1_700_000_005, "r1"))
        val rows = b.thread(ch)
        assertEquals(1, rows.size, "the reaction must not land as a line of text")
        assertEquals(mapOf(Reactions.ALL[0] to 1), rows[0].reactions)
    }

    @Test
    fun aRetriedReactionCountsOnce() {
        val b = book()
        b.receive(dm, false, null, "ok?", 1_700_000_000, "k1")
        val wire = Reactions.encode(Reactions.targetHash(1_700_000_000, null, "ok?"), Reactions.ALL[1])!!
        b.receive(dm, false, null, wire, 1_700_000_005, "r1")
        assertEquals(MessageBook.Inbound.Consumed, b.receive(dm, false, null, wire, 1_700_000_005, "r1"))
        assertEquals(1, b.thread(dm).single().reactions[Reactions.ALL[1]])
    }

    @Test
    fun aReactionToNothingWeHoldIsConsumedSilently() {
        val b = book()
        val wire = Reactions.encode("abcd", Reactions.ALL[0])!!
        assertEquals(MessageBook.Inbound.Consumed, b.receive(dm, false, null, wire, 1, "r1"))
        assertTrue(b.thread(dm).isEmpty())
        assertNull(b.unread.value[dm])
    }

    @Test
    fun deliveredNeverMovesBackwards() {
        val b = book()
        val id = b.addOutgoing(dm, null, "hi", 100)
        b.updateStatus(id, MessageStatus.Delivered, ackHash = 7)
        b.updateStatus(id, MessageStatus.Failed)
        assertEquals(MessageStatus.Delivered, b.thread(dm).single().status)
        assertEquals(7L, b.thread(dm).single().ackHash)
    }

    @Test
    fun retentionDropsOnlyTheOldestOfTheSameThread() {
        val b = book(keep = 3)
        b.receive(ch, true, "x", "quiet dm neighbour", 1, "q")
        b.receive(dm, false, null, "keep me", 1, "d")
        for (i in 0 until 5) b.receive(ch, true, "x", "m$i", 10L + i, "c$i")
        assertEquals(listOf("m2", "m3", "m4"), b.thread(ch).map { it.text })
        assertEquals(listOf("keep me"), b.thread(dm).map { it.text })
    }

    @Test
    fun summariesAreMostRecentFirstWithUnread() {
        val b = book()
        b.receive(dm, false, null, "older", 1, "a")
        b.receive(ch, true, "x", "newer", 2, "b")
        val s = b.summaries()
        assertEquals(listOf(ch, dm), s.map { it.threadKey })
        assertEquals("newer", s[0].last?.text)
        assertEquals(1, s[0].unread)
    }

    @Test
    fun aSnapshotRoundTripsThroughJson() {
        val b = book()
        b.receive(dm, false, "Room: Al", "hi", 100, "k1", snr = 7.5, hops = 2)
        val out = b.addOutgoing(dm, null, "yo", 101)
        b.updateStatus(out, MessageStatus.Delivered, ackHash = 0xFFFF_FFFFL, attempts = 2)
        val restored = MessageBook.fromJson(b.toJson(), nowMs = { 0 })
        assertEquals(b.messages.value, restored.messages.value)
        assertEquals(b.unread.value, restored.unread.value)
        // New ids continue past the restored ones rather than colliding.
        val next = restored.addOutgoing(dm, null, "again", 102)
        assertTrue(next > b.messages.value.maxOf { it.id })
    }

    @Test
    fun aPendingRowFromAPreviousRunIsRestoredAsFailed() {
        val b = book()
        b.addOutgoing(dm, null, "never answered", 100)
        val sent = b.addOutgoing(dm, null, "radio took it", 101)
        b.updateStatus(sent, MessageStatus.Sent, ackHash = 9)
        val restored = MessageBook.fromJson(b.toJson(), nowMs = { 0 })
        assertEquals(
            listOf(MessageStatus.Failed, MessageStatus.Sent),
            restored.thread(dm).map { it.status },
        )
    }

    @Test
    fun anUnreadableFileGivesAnEmptyBookNotACrash() {
        for (bad in listOf(null, "", "{", "[]", "{\"messages\":7}", "\u0000\u0001")) {
            val restored = MessageBook.fromJson(bad, nowMs = { 0 })
            assertTrue(restored.messages.value.isEmpty(), "input: $bad")
        }
    }

    @Test
    fun readabilityIsJudgedBeforeLoading() {
        assertTrue(MessageBook.isReadable(null))
        assertTrue(MessageBook.isReadable("  "))
        assertTrue(MessageBook.isReadable(book().toJson()), "an empty book's own output")
        val b = book(); b.receive(dm, false, null, "x", 1, "k")
        assertTrue(MessageBook.isReadable(b.toJson()))
        for (bad in listOf("{", "[]", "{\"messages\":7}", "\u0000")) {
            kotlin.test.assertFalse(MessageBook.isReadable(bad), "input: $bad")
        }
    }

    @Test
    fun idsStayUniqueWhenTheFileUnderstatesNextId() {
        // A hand-edited or older file whose nextId lags its rows must not
        // hand out an id already in use — updateStatus would hit the
        // wrong message.
        val json = """{"nextId":1,"messages":[{"id":5,"threadKey":"$dm","outgoing":true,"text":"x","timestamp":1,"receivedAtMs":1,"status":"Sent"}]}"""
        val restored = MessageBook.fromJson(json, nowMs = { 0 })
        assertEquals(6L, restored.addOutgoing(dm, null, "y", 2))
    }

    @Test
    fun retentionByDaysJudgesAgeByOurClockNotTheSenders() {
        val day = 86_400_000L
        clock = 100 * day
        val b = book()
        // Arrived just now, but the sender's RTC says 2020.
        b.receive(dm, false, null, "fresh, wrong clock", 1_577_836_800L, "a")
        clock = 100 * day - 40 * day
        b.receive(dm, false, null, "really old", 1_900_000_000L, "b")
        clock = 100 * day
        val removed = b.applyRetention(io.github.thatsfguy.meshcore.protocol.Retention.Policy(
            io.github.thatsfguy.meshcore.protocol.Retention.Mode.Days, 30), nowMs = 100 * day)
        assertEquals(1, removed)
        assertEquals(listOf("fresh, wrong clock"), b.thread(dm).map { it.text })
    }

    @Test
    fun retentionByCountKeepsTheNewestOfEachThread() {
        val b = book()
        for (i in 0 until 5) b.receive(dm, false, null, "d$i", i.toLong(), "d$i")
        for (i in 0 until 2) b.receive(ch, true, "x", "c$i", i.toLong(), "c$i")
        val policy = io.github.thatsfguy.meshcore.protocol.Retention.Policy(
            io.github.thatsfguy.meshcore.protocol.Retention.Mode.Count, 3)
        assertEquals(2, b.applyRetention(policy, nowMs = clock))
        assertEquals(listOf("d2", "d3", "d4"), b.thread(dm).map { it.text })
        assertEquals(listOf("c0", "c1"), b.thread(ch).map { it.text })
    }

    @Test
    fun keepEverythingRemovesNothing() {
        val b = book()
        b.receive(dm, false, null, "x", 1, "k")
        assertEquals(0, b.applyRetention(io.github.thatsfguy.meshcore.protocol.Retention.Policy(), nowMs = Long.MAX_VALUE))
        assertEquals(1, b.thread(dm).size)
    }

    @Test
    fun aThreadEmptiedByRetentionLeavesNoUnread() {
        val b = book()
        b.receive(dm, false, null, "x", 1, "k")
        val policy = io.github.thatsfguy.meshcore.protocol.Retention.Policy(
            io.github.thatsfguy.meshcore.protocol.Retention.Mode.Days, 1)
        b.applyRetention(policy, nowMs = clock + 2 * 86_400_000L)
        assertTrue(b.thread(dm).isEmpty())
        assertEquals(0, b.unreadTotal())
    }
}
