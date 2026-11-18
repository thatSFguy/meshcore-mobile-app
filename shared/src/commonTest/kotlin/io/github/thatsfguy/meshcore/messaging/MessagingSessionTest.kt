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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagingSessionTest {

    private val peer = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899"

    private class FakeRadio : MessagingRadio {
        val bus = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)
        override val events = bus
        override fun nowSeconds() = 1_700_000_000L
        override fun selfName() = "Me"
        var stored: ContactRoute? = ContactRoute(hasStoredPath = true, storedHops = 2)
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
}
