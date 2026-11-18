package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.engine.MeshEvent
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingSessionTest {

    private val peer = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"

    private class FakeRadio : MessagingRadio {
        val bus = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
        override val events = bus
        override fun nowSeconds() = 1_700_000_000L
        override fun selfName() = "Me"
        var stored: ContactRoute? = ContactRoute(hasStoredPath = true, storedHops = 2, storedPathHex = "b389c985", hashWidth = 2)
        override fun route(keyHex: String) = stored
        val sentDirect = mutableListOf<Int>()
        var resets = 0
        var channelOk = true
        val channelSends = mutableListOf<Pair<Int, String>>()

        override suspend fun sendDirect(keyHex: String, text: String, attempt: Int, timestampSeconds: Long): DirectSend.Receipt {
            sentDirect += attempt
            return DirectSend.Receipt(ackHash = 500L + attempt, timeoutMs = 3_000, isFlood = false)
        }
        override suspend fun resetPath(keyHex: String): Boolean { resets++; return true }
        override suspend fun sendChannel(index: Int, text: String, timestampSeconds: Long): Boolean {
            channelSends += index to text
            return channelOk
        }
        override fun channelContentKey(index: Int, timestampSeconds: Long, senderName: String, text: String) =
            "ck:$index:$timestampSeconds:$senderName:$text"
    }

    private fun TestScope.session(radio: FakeRadio = FakeRadio()) =
        MessagingSession(radio, MessageBook(nowMs = { testScheduler.currentTime }), this).also {
            it.start()
            runCurrent()
        }

    private fun dm(text: String, key: String? = "k", txtType: Int = Codes.TXT_TYPE_PLAIN) =
        MeshEvent.DirectMessageReceived(
            senderPrefixHex = peer.take(12),
            senderKeyHex = peer,
            text = text,
            timestamp = 1_700_000_000L,
            txtType = txtType,
            snr = 6.0,
            contentKey = key,
        )

    @Test
    fun anInboundDirectMessageLandsInItsThread() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val heard = mutableListOf<String>()
        s.onNewMessage = { key, _, text -> heard += "$key $text" }
        radio.bus.emit(dm("hello"))
        runCurrent()
        assertEquals(listOf("hello"), s.book.thread("dm|$peer").map { it.text })
        assertEquals(listOf("dm|$peer hello"), heard)
        s.stop()
    }

    @Test
    fun anUnresolvedSenderIsFiledUnderItsPrefix() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(dm("hi").copy(senderKeyHex = null))
        runCurrent()
        assertEquals(1, s.book.thread("dm|${peer.take(12)}").size)
        s.stop()
    }

    @Test
    fun aCliReplyIsNotAChatMessage() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(dm("> 910.525", key = null, txtType = Codes.TXT_TYPE_CLI_DATA))
        runCurrent()
        assertTrue(s.book.messages.value.isEmpty())
        s.stop()
    }

    @Test
    fun aDuplicateNeverNotifiesTwice() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        var notified = 0
        s.onNewMessage = { _, _, _ -> notified++ }
        radio.bus.emit(dm("hello"))
        radio.bus.emit(dm("hello"))
        runCurrent()
        assertEquals(1, notified)
        s.stop()
    }

    @Test
    fun theOpenThreadDoesNotNotify() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        var notified = 0
        s.onNewMessage = { _, _, _ -> notified++ }
        s.book.activeThread = "dm|$peer"
        radio.bus.emit(dm("hello"))
        runCurrent()
        assertEquals(0, notified)
        assertEquals(1, s.book.thread("dm|$peer").size)
        s.stop()
    }

    @Test
    fun anInboundChannelPostLandsWithItsSender() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(
            MeshEvent.ChannelMessageReceived(
                channelIndex = 2, senderName = "Bob", text = "hey", timestamp = 1, contentKey = "c1",
            ),
        )
        runCurrent()
        val row = s.book.thread("ch|2").single()
        assertEquals("Bob", row.senderName)
        s.stop()
    }

    @Test
    fun aDirectSendIsDeliveredByItsAck() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val id = s.sendDirect(peer, "  hi  ")!!
        assertEquals(MessageStatus.Pending, s.book.thread("dm|$peer").single().status)
        runCurrent()
        assertEquals(MessageStatus.Sent, s.book.thread("dm|$peer").single().status)
        radio.bus.emit(MeshEvent.MessageDelivered(ackHash = 500L, tripMs = 900))
        runCurrent()
        val row = s.book.thread("dm|$peer").single()
        assertEquals(id, row.id)
        assertEquals("hi", row.text, "the sent text is trimmed")
        assertEquals(MessageStatus.Delivered, row.status)
        assertEquals(listOf(0), radio.sentDirect)
        s.stop()
    }

    @Test
    fun anUnansweredDirectSendRunsTheLadderAndFails() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), radio.sentDirect)
        assertEquals(1, radio.resets)
        val row = s.book.thread("dm|$peer").single()
        assertEquals(MessageStatus.Failed, row.status)
        assertEquals(3, row.attempts)
        s.stop()
    }

    @Test
    fun aContactWithNoPathNeverHasItReset() = runTest {
        val radio = FakeRadio().apply { stored = null }
        val s = session(radio)
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        assertEquals(0, radio.resets)
        s.stop()
    }

    @Test
    fun blankTextOrABadKeySendsNothing() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        assertNull(s.sendDirect(peer, "   "))
        assertNull(s.sendDirect("not hex!", "hi"))
        assertNull(s.sendChannel(0, ""))
        assertNull(s.sendChannel(-1, "hi"))
        advanceUntilIdle()
        assertTrue(radio.sentDirect.isEmpty())
        assertTrue(radio.channelSends.isEmpty())
        assertTrue(s.book.messages.value.isEmpty())
        s.stop()
    }

    @Test
    fun aChannelPostIsSentAndItsEchoIsRecognisedAsOurs() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.sendChannel(1, "hi all")
        runCurrent()
        assertEquals(listOf(1 to "hi all"), radio.channelSends)
        assertEquals(MessageStatus.Sent, s.book.thread("ch|1").single().status)
        // The engine computes the echo's key from the same four fields.
        radio.bus.emit(
            MeshEvent.ChannelMessageReceived(
                channelIndex = 1, senderName = "Me", text = "hi all", timestamp = 1_700_000_000L,
                contentKey = radio.channelContentKey(1, 1_700_000_000L, "Me", "hi all"),
            ),
        )
        runCurrent()
        assertEquals(1, s.book.thread("ch|1").size)
        s.stop()
    }

    @Test
    fun aRefusedChannelPostIsMarkedFailed() = runTest {
        val radio = FakeRadio().apply { channelOk = false }
        val s = session(radio)
        s.sendChannel(1, "hi")
        runCurrent()
        assertEquals(MessageStatus.Failed, s.book.thread("ch|1").single().status)
        s.stop()
    }

    @Test
    fun aChannelReactionIsSentInTheWireFormAndItsEchoCountsOnce() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(MeshEvent.ChannelMessageReceived(1, "Bob", "lunch?", 1_699_999_000L, "c1"))
        runCurrent()
        val target = s.book.thread("ch|1").single()
        val emoji = io.github.thatsfguy.meshcore.protocol.Reactions.ALL[0]
        assertTrue(s.react(target.id, emoji))
        runCurrent()
        val hash = io.github.thatsfguy.meshcore.protocol.Reactions.targetHash(1_699_999_000L, "Bob", "lunch?")
        val wire = io.github.thatsfguy.meshcore.protocol.Reactions.encode(hash, emoji)!!
        assertEquals(listOf(1 to wire), radio.channelSends)
        // Our own reaction echoing back is not a second reaction.
        radio.bus.emit(
            MeshEvent.ChannelMessageReceived(1, "Me", wire, 1_700_000_000L,
                radio.channelContentKey(1, 1_700_000_000L, "Me", wire)),
        )
        runCurrent()
        assertEquals(mapOf(emoji to 1), s.book.thread("ch|1").single().reactions)
        s.stop()
    }

    @Test
    fun aDirectReactionHashesWithoutASenderName() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(dm("ok?"))
        runCurrent()
        val target = s.book.thread("dm|$peer").single()
        val emoji = io.github.thatsfguy.meshcore.protocol.Reactions.ALL[2]
        assertTrue(s.react(target.id, emoji))
        advanceUntilIdle()
        assertEquals(mapOf(emoji to 1), s.book.thread("dm|$peer").single().reactions)
        assertEquals(1, s.book.thread("dm|$peer").size, "a reaction is not a bubble")
        s.stop()
    }

    @Test
    fun anEmojiOffTheWireListSendsNothing() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        radio.bus.emit(dm("ok?"))
        runCurrent()
        assertFalse(s.react(s.book.thread("dm|$peer").single().id, "not-an-emoji"))
        assertFalse(s.react(9_999, io.github.thatsfguy.meshcore.protocol.Reactions.ALL[0]))
        advanceUntilIdle()
        assertTrue(radio.sentDirect.isEmpty())
        s.stop()
    }

    @Test
    fun aBlockedSendersMessageNeverBecomesARow() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        var notified = 0
        s.onNewMessage = { _, _, _ -> notified++ }
        s.blockedKeys = setOf(peer)
        radio.bus.emit(dm("spam"))
        runCurrent()
        assertTrue(s.book.messages.value.isEmpty())
        assertEquals(0, notified)
        // Positive control: unblocked, the same message lands.
        s.blockedKeys = emptySet()
        radio.bus.emit(dm("spam"))
        runCurrent()
        assertEquals(1, s.book.messages.value.size)
        s.stop()
    }

    @Test
    fun aSenderKnownOnlyByPrefixIsNeverBlocked() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.blockedKeys = setOf(peer)
        radio.bus.emit(dm("who?").copy(senderKeyHex = null))
        runCurrent()
        assertEquals(1, s.book.messages.value.size)
        s.stop()
    }

    @Test
    fun aFilteredChannelNameIsHiddenAndOthersAreNot() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.filteredNames = setOf("spammer")
        radio.bus.emit(MeshEvent.ChannelMessageReceived(0, "Spammer", "buy", 1, "a"))
        radio.bus.emit(MeshEvent.ChannelMessageReceived(0, "Bob", "hi", 2, "b"))
        runCurrent()
        assertEquals(listOf("hi"), s.book.thread("ch|0").map { it.text })
        s.stop()
    }

    // --- diagnostics -------------------------------------------------------

    @Test
    fun theLogNarratesADeliveredSendWithoutItsText() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val lines = mutableListOf<String>()
        s.log = { lines += it }
        val id = s.sendDirect(peer, "meet at the north gate")!!
        runCurrent()
        radio.bus.emit(MeshEvent.MessageDelivered(ackHash = 500L, tripMs = 900))
        runCurrent()
        assertEquals(
            listOf(
                "DM #$id to ${peer.take(12)}: queued, stored path 2 hops, est hops=2",
                "DM #$id attempt 1: radio accepted, ack=1f4 wait=3000ms flood=false",
                "DM #$id to ${peer.take(12)}: Delivered",
            ),
            lines,
        )
        assertTrue(lines.none { "north gate" in it })
        s.stop()
    }

    @Test
    fun theLogShowsEveryAttemptOfAnUnansweredSend() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val lines = mutableListOf<String>()
        s.log = { lines += it }
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        assertEquals(3, lines.count { "attempt" in it })
        assertTrue(lines.any { "path reset before flood ok" in it })
        assertTrue(lines.last().endsWith(": Failed"))
        s.stop()
    }

    @Test
    fun arrivalsAreLoggedWithoutTextOrChannelSenderName() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val lines = mutableListOf<String>()
        s.log = { lines += it }
        radio.bus.emit(dm("secret plans"))
        radio.bus.emit(
            MeshEvent.ChannelMessageReceived(
                channelIndex = 2, senderName = "Bob", text = "hey there", timestamp = 1, contentKey = "c1", hops = 3, snr = 4.5,
            ),
        )
        runCurrent()
        assertEquals(
            listOf(
                "DM from ${peer.take(12)}: Added, hops=? snr=6.0",
                "Channel 2 post: Added, hops=3 snr=4.5",
            ),
            lines,
        )
        assertTrue(lines.none { "secret" in it || "hey there" in it || "Bob" in it })
        s.stop()
    }

    @Test
    fun aThrowingLogNeverBreaksASend() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.log = { error("log sink gone") }
        s.sendDirect(peer, "hi")
        runCurrent()
        radio.bus.emit(MeshEvent.MessageDelivered(ackHash = 500L, tripMs = 900))
        runCurrent()
        assertEquals(MessageStatus.Delivered, s.book.thread("dm|$peer").single().status)
        s.stop()
    }

    @Test
    fun withFloodFallbackOffAStoredPathIsNeverReset() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        s.floodFallback = false
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        assertEquals(0, radio.resets)
        assertEquals(MessageStatus.Failed, s.book.thread("dm|$peer").single().status)
        s.stop()
    }

    @Test
    fun floodFallbackIsOnByDefault() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        assertTrue(s.floodFallback)
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        assertEquals(1, radio.resets)
        s.stop()
    }

    @Test
    fun aDeliveredFirstAttemptScoresTheStoredRoute() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val paths = PathHistoryBook({ testScheduler.currentTime })
        s.paths = paths
        s.sendDirect(peer, "hi")
        runCurrent()
        radio.bus.emit(MeshEvent.MessageDelivered(ackHash = 500L, tripMs = 900))
        runCurrent()
        val row = paths.forContact(peer).single()
        assertEquals("b389c985", row.pathHex)
        assertEquals(1, row.successes)
        assertEquals(0, row.failures)
        s.stop()
    }

    @Test
    fun theFloodFallbackScoresNothingButTheStoredAttemptsCountAgainstTheRoute() = runTest {
        val radio = FakeRadio()
        val s = session(radio)
        val paths = PathHistoryBook({ testScheduler.currentTime })
        s.paths = paths
        s.sendDirect(peer, "hi")
        advanceUntilIdle()
        val rows = paths.forContact(peer)
        assertEquals(listOf("b389c985"), rows.map { it.pathHex }, "the reset-and-flood attempt is not scored")
        assertEquals(2, rows.single().failures)
        s.stop()
    }
}
