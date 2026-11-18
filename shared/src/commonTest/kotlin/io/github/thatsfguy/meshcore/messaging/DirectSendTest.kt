package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.protocol.SendRetry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectSendTest {

    /** A radio that records what it was asked and ACKs when told to. */
    private class FakeRadio(private val scope: TestScope) : DirectSend.Radio {
        val calls = mutableListOf<String>()
        val acks = MutableSharedFlow<Long>(extraBufferCapacity = 16)
        override val deliveries = acks
        /** Attempt numbers the radio refuses outright. */
        var refuse: Set<Int> = emptySet()
        /** Attempt number → delay (ms) after which its ACK arrives. */
        var ackAfter: Map<Int, Long> = emptyMap()

        override suspend fun send(text: String, attempt: Int, timestampSeconds: Long): DirectSend.Receipt? {
            calls += "send:$attempt"
            if (attempt in refuse) return null
            val hash = 1000L + attempt
            ackAfter[attempt]?.let { d ->
                scope.launch { kotlinx.coroutines.delay(d); acks.emit(hash) }
            }
            return DirectSend.Receipt(ackHash = hash, timeoutMs = 5_000, isFlood = false)
        }

        override suspend fun resetPath(): Boolean {
            calls += "reset"
            return true
        }
    }

    private fun TestScope.ladder(
        radio: FakeRadio,
        hasStoredPath: Boolean = true,
        updates: MutableList<DirectSend.Update> = mutableListOf(),
    ) = launch {
        DirectSend.run(radio, "hi", 1_700_000_000L, hasStoredPath, knownHops = 1) { updates += it }
    }

    @Test
    fun anAckOnTheFirstAttemptDeliversWithNoRetry() = runTest {
        val radio = FakeRadio(this).apply { ackAfter = mapOf(0 to 500L) }
        val updates = mutableListOf<DirectSend.Update>()
        ladder(radio, updates = updates).join()
        assertEquals(listOf("send:0"), radio.calls)
        assertEquals(MessageStatus.Delivered, updates.last().status)
        assertEquals(1000L, updates.last().ackHash)
        assertEquals(1, updates.last().attempts)
    }

    @Test
    fun theLastAttemptResetsThePathThenFloods() = runTest {
        // The documented default (SendRetry): three attempts, and the
        // path reset comes immediately before the third send — not after,
        // and not on every attempt.
        val radio = FakeRadio(this)
        ladder(radio).join()
        assertEquals(listOf("send:0", "send:1", "reset", "send:2"), radio.calls)
        assertEquals(SendRetry.DEFAULT_MAX_ATTEMPTS, radio.calls.count { it.startsWith("send") })
    }

    @Test
    fun withNoStoredPathThereIsNothingToReset() = runTest {
        val radio = FakeRadio(this)
        ladder(radio, hasStoredPath = false).join()
        assertEquals(listOf("send:0", "send:1", "send:2"), radio.calls)
    }

    @Test
    fun anAckForAnEarlierAttemptStillCountsAsDelivered() = runTest {
        // Attempt 0's ACK lands after attempt 1 has gone out: each attempt
        // has its own hash, and watching only the current one reported a
        // delivered message as failed.
        // Each wait is 5 s, so attempt 1 goes out at 6 s; 7 s is inside
        // ITS wait, under attempt 0's hash.
        val radio = FakeRadio(this).apply { ackAfter = mapOf(0 to 7_000L) }
        var deliveredAt = -1L
        val job = launch {
            DirectSend.run(radio, "hi", 1_700_000_000L, true, knownHops = 1) {
                if (it.status == MessageStatus.Delivered) deliveredAt = testScheduler.currentTime
            }
        }
        job.join()
        assertEquals(listOf("send:0", "send:1"), radio.calls)
        // Reported the moment it lands — not at the end of attempt 1's
        // wait, which is what watching only the current hash gives.
        assertEquals(7_000L, deliveredAt)
    }

    @Test
    fun aLateAckAfterGivingUpTurnsFailedIntoDelivered() = runTest {
        val radio = FakeRadio(this)
        val updates = mutableListOf<DirectSend.Update>()
        val job = ladder(radio, updates = updates)
        // The ladder is 5 + 1 + 5 + 2 + 5 = 18 s; then the 30 s grace.
        // ACK the last attempt inside it.
        advanceTimeBy(25_000L)
        assertEquals(MessageStatus.Failed, updates.last().status)
        radio.acks.emit(1002L)
        job.join()
        assertEquals(MessageStatus.Delivered, updates.last().status)
    }

    @Test
    fun anAckAfterTheGraceWindowIsTooLate() = runTest {
        // The bound on the late-ACK window: 18 s of ladder + 30 s grace.
        val radio = FakeRadio(this).apply { ackAfter = mapOf(2 to 40_000L) }
        val updates = mutableListOf<DirectSend.Update>()
        ladder(radio, updates = updates).join()
        assertEquals(MessageStatus.Failed, updates.last().status)
    }

    @Test
    fun withNoAckTheFinalReportIsFailed() = runTest {
        val radio = FakeRadio(this)
        val updates = mutableListOf<DirectSend.Update>()
        ladder(radio, updates = updates).join()
        assertEquals(MessageStatus.Failed, updates.last().status)
        // Every attempt reported Sent on the way, with its own hash.
        assertEquals(listOf(1000L, 1001L, 1002L), updates.filter { it.status == MessageStatus.Sent }.map { it.ackHash })
    }

    @Test
    fun aRadioThatRefusesTheFirstAttemptFailsAtOnce() = runTest {
        val radio = FakeRadio(this).apply { refuse = setOf(0) }
        val updates = mutableListOf<DirectSend.Update>()
        ladder(radio, updates = updates).join()
        assertEquals(listOf("send:0"), radio.calls)
        assertEquals(listOf(MessageStatus.Failed), updates.map { it.status })
    }

    @Test
    fun anAckForSomeoneElsesMessageIsNotOurs() = runTest {
        val radio = FakeRadio(this)
        val updates = mutableListOf<DirectSend.Update>()
        val job = ladder(radio, updates = updates)
        advanceTimeBy(100)
        radio.acks.emit(42L)
        job.join()
        assertEquals(MessageStatus.Failed, updates.last().status)
    }
}
