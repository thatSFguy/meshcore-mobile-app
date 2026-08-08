package io.github.thatsfguy.meshcore.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ACK bookkeeping behind direct-message retry.
 *
 * The engine's event flow has no replay, so an ACK nobody is collecting
 * at the instant it is emitted is gone. Opening a fresh wait per attempt
 * left three holes, and every one of them turned a DELIVERED message
 * into a reported failure — the worst kind of wrong answer here, because
 * it invites the user to send again over a link that worked.
 *
 * Each hole gets a test named after it. They are the whole reason this
 * class exists rather than a `withTimeoutOrNull` inline in the loop.
 */
class DeliveryWatchTest {

    @Test
    fun anAckThatArrivedBeforeTheWaitStillCounts() = runTest {
        // Gap 1: the backoff between attempts, when nothing is
        // collecting. The ACK lands, THEN we ask. A listener-based wait
        // returns false here; this must return true.
        val watch = DeliveryWatch()
        watch.record(0xAAAA)
        assertTrue(watch.awaitAny(setOf(0xAAAA), timeoutMs = 0))
    }

    @Test
    fun anAckForAnEarlierAttemptCounts() = runTest {
        // Gap 2: every attempt is sent under its own hash (the attempt
        // byte differs so the mesh does not drop the ACK as a
        // duplicate), and only the CURRENT attempt's hash was watched.
        // Attempt 1's ACK arriving while attempt 3 is in flight is still
        // a delivered message.
        val watch = DeliveryWatch()
        val attempts = setOf(0x1111L, 0x2222L, 0x3333L)
        watch.record(0x1111)
        assertTrue(watch.awaitAny(attempts, timeoutMs = 0))
    }

    @Test
    fun aLateAckAfterGivingUpCounts() = runTest {
        // Gap 3: the message is already marked failed. A timeout is an
        // estimate, not a deadline the mesh agreed to.
        val watch = DeliveryWatch()
        val hashes = setOf(0xBEEFL)
        val result = async { watch.awaitAny(hashes, timeoutMs = SendRetry.LATE_ACK_GRACE_MS) }
        launch { watch.record(0xBEEF) }
        assertTrue(result.await())
    }

    @Test
    fun aWaitInProgressIsWokenByAnArrival() = runTest {
        val watch = DeliveryWatch()
        val result = async { watch.awaitAny(setOf(7L), timeoutMs = 60_000) }
        launch { watch.record(7L) }
        assertTrue(result.await())
    }

    @Test
    fun somebodyElsesAckIsNotOurs() = runTest {
        // The positive controls above would all pass if awaitAny simply
        // returned true. This is the case that must say no.
        val watch = DeliveryWatch()
        watch.record(0x1234)
        assertFalse(watch.awaitAny(setOf(0x9999), timeoutMs = 0))
    }

    @Test
    fun nothingSentMeansNothingDelivered() = runTest {
        // An empty hash set is "the radio never accepted anything", not
        // "match anything".
        val watch = DeliveryWatch()
        watch.record(0x1234)
        assertFalse(watch.awaitAny(emptySet(), timeoutMs = 0))
        assertFalse(watch.awaitAny(emptySet(), timeoutMs = 1_000))
    }

    @Test
    fun aWaitThatIsNeverAnsweredTimesOut() = runTest {
        // runTest's virtual clock: this returns false without waiting
        // the wall-clock 30s.
        val watch = DeliveryWatch()
        assertFalse(watch.awaitAny(setOf(1L), timeoutMs = SendRetry.LATE_ACK_GRACE_MS))
    }

    @Test
    fun aZeroTimeoutNeverSuspends() = runTest {
        // Used for the between-attempts check, which must not add delay
        // to a send that is about to retry anyway.
        val watch = DeliveryWatch()
        assertFalse(watch.awaitAny(setOf(1L), timeoutMs = 0))
        watch.record(1L)
        assertTrue(watch.awaitAny(setOf(1L), timeoutMs = 0))
    }

    @Test
    fun everyRecordedAckIsRemembered() = runTest {
        // Order and duplicates must not lose one: the same ACK can reach
        // us more than once.
        val watch = DeliveryWatch()
        watch.record(1L)
        watch.record(2L)
        watch.record(1L)
        assertEquals(setOf(1L, 2L), watch.observed)
        assertTrue(watch.awaitAny(setOf(2L), timeoutMs = 0))
    }

    @Test
    fun theGraceWindowIsLongerThanAnyAttemptTimeout() = runTest {
        // The retry loop clamps a single attempt's wait to 60s, but the
        // grace only has to outlast the mesh's own delivery, not our
        // clamp. Pin it so it cannot be trimmed to something shorter
        // than a slow flood round trip.
        assertTrue(
            SendRetry.LATE_ACK_GRACE_MS >= 30_000,
            "a late ACK needs longer than a flood round trip to arrive",
        )
    }
}
