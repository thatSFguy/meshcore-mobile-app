package io.github.thatsfguy.meshcore.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reconnect loop, driven on virtual time.
 *
 * These are the walked-out-of-range tests. The shape that has bitten
 * this app is not "the loop is wrong" — it is "the loop never gets
 * another turn", so the cases that carry the suite are the ones where a
 * connect hangs, and the one asserting the radio is picked up again
 * once it comes back.
 */
class ReconnectSupervisorTest {

    /** A transport whose connect outcome each attempt is scripted. */
    private class FakeTransport(
        private val outcome: Outcome,
    ) : Transport {
        sealed interface Outcome {
            /** Connects, then the link drops after [upForMs] of virtual time. */
            data class Connects(val upForMs: Long) : Outcome
            /** connect() throws after [afterMs]. */
            data class Fails(val afterMs: Long, val message: String = "no radio") : Outcome
            /** connect() never returns and never throws. */
            data object Hangs : Outcome
            /** Connects and stays up indefinitely. */
            data object Holds : Outcome
        }

        val _state = MutableStateFlow(TransportState.Disconnected)
        override val state: StateFlow<TransportState> = _state
        override val incoming: Flow<IncomingFrame> = emptyFlow()

        var connectCalls = 0
        var disconnectCalls = 0

        override suspend fun connect() {
            connectCalls++
            when (outcome) {
                is Outcome.Connects -> {
                    _state.value = TransportState.Connected
                }
                is Outcome.Holds -> _state.value = TransportState.Connected
                is Outcome.Fails -> {
                    kotlinx.coroutines.delay(outcome.afterMs)
                    _state.value = TransportState.Error
                    throw IllegalStateException(outcome.message)
                }
                Outcome.Hangs -> CompletableDeferred<Unit>().await()
            }
        }

        /** Simulate the radio walking away. */
        fun dropLink() {
            _state.value = TransportState.Disconnected
        }

        override suspend fun disconnect() {
            disconnectCalls++
            _state.value = TransportState.Disconnected
        }

        override suspend fun send(frame: ByteArray) = Unit
    }

    private class Harness(val scope: TestScope) {
        val built = mutableListOf<ReconnectAttempt>()
        val attached = mutableListOf<FakeTransport>()
        val errors = mutableListOf<String?>()
        val logs = mutableListOf<String>()
        var script: (ReconnectAttempt) -> FakeTransport = { FakeTransport(FakeTransport.Outcome.Holds) }

        val supervisor = ReconnectSupervisor(
            scope = scope,
            build = { attempt -> built += attempt; script(attempt) },
            onTransport = { attached += it as FakeTransport },
            onError = { errors += it },
            log = { logs += it },
            now = { scope.testScheduler.currentTime },
        )
    }

    // ------------------------------------------------------------------
    // The bug this was written for.
    // ------------------------------------------------------------------

    @Test
    fun aConnectThatNeverAnswersDoesNotParkTheLoopForever() = runTest {
        val h = Harness(this)
        // The radio is out of range: every attempt hangs. Its connect()
        // is bounded by the transport (BleTransport's timeout), which
        // shows up here as an attempt that eventually throws.
        h.script = { FakeTransport(FakeTransport.Outcome.Fails(afterMs = 40_000)) }
        h.supervisor.start()

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertTrue(
            h.built.size >= 5,
            "the loop must keep getting turns while the radio is away, got ${h.built.size}",
        )
        h.supervisor.stop()
    }

    @Test
    fun theRadioIsPickedUpAgainWhenItComesBackInRange() = runTest {
        val h = Harness(this)
        var away = true
        val reconnected = FakeTransport(FakeTransport.Outcome.Holds)
        h.script = {
            if (away) FakeTransport(FakeTransport.Outcome.Fails(afterMs = 40_000)) else reconnected
        }
        h.supervisor.start()

        // Five minutes out of range.
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertTrue(h.attached.none { it.connectCalls > 0 && it === reconnected })

        // The user walks back.
        away = false
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(1, reconnected.connectCalls, "the radio must be reconnected once back in range")
        assertEquals(TransportState.Connected, reconnected._state.value)
        h.supervisor.stop()
    }

    // ------------------------------------------------------------------
    // Attempt policy — what BleTransport reads to pick its connect mode.
    // ------------------------------------------------------------------

    @Test
    fun theFirstAttemptIsImpatientAndLaterOnesAreNot() = runTest {
        val h = Harness(this)
        h.script = { FakeTransport(FakeTransport.Outcome.Fails(afterMs = 1_000)) }
        h.supervisor.start()

        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(0, h.built.first().consecutiveFailures)
        assertTrue(!h.built.first().patient, "the first try must be a fast direct connect")
        assertTrue(h.built[1].patient, "every try after a failure must be the patient kind")
        assertTrue(h.built[2].patient)
        h.supervisor.stop()
    }

    @Test
    fun aLinkThatHeldResetsTheAttemptCountSoTheNextTryIsFastAgain() = runTest {
        val h = Harness(this)
        val good = FakeTransport(FakeTransport.Outcome.Connects(upForMs = 0))
        var served = 0
        h.script = {
            served++
            when (served) {
                1, 2 -> FakeTransport(FakeTransport.Outcome.Fails(afterMs = 1_000))
                3 -> good
                else -> FakeTransport(FakeTransport.Outcome.Fails(afterMs = 1_000))
            }
        }
        h.supervisor.start()
        advanceTimeBy(10_000L)
        runCurrent()

        assertTrue(h.built[2].patient, "the third build follows two failures")
        // The link is up; let it hold well past the threshold, then drop.
        advanceTimeBy(ReconnectSupervisor.HELD_LONG_ENOUGH_MS + 5_000L)
        runCurrent()
        good.dropLink()
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(
            0,
            h.built[3].consecutiveFailures,
            "a link that held is not a failure — the next try must be direct again",
        )
        h.supervisor.stop()
    }

    /**
     * The regression that made the backoff useless. The hold clock used
     * to start when the ATTEMPT began, so a connect that spent 40s
     * failing looked like a link that had held for 40s: the counter
     * reset and the app retried an absent radio every second forever.
     */
    @Test
    fun timeSpentFailingToConnectIsNotCountedAsTimeTheLinkHeld() = runTest {
        val h = Harness(this)
        h.script = {
            FakeTransport(
                FakeTransport.Outcome.Fails(
                    afterMs = ReconnectSupervisor.HELD_LONG_ENOUGH_MS + 10_000L,
                ),
            )
        }
        h.supervisor.start()

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        val failures = h.built.map { it.consecutiveFailures }
        assertEquals(
            failures.sorted(),
            failures,
            "consecutive failures must climb, not reset: $failures",
        )
        assertTrue(failures.last() >= 3, "backoff must actually grow, got $failures")
        h.supervisor.stop()
    }

    @Test
    fun aLinkThatDropsImmediatelyStillBacksOff() = runTest {
        val h = Harness(this)
        h.script = { FakeTransport(FakeTransport.Outcome.Connects(upForMs = 0)) }
        h.supervisor.start()
        runCurrent()
        // Connected, then dropped straight away — a radio at the edge of
        // range. This must NOT be treated as a link that held.
        h.attached.first().dropLink()
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(
            h.built[1].patient,
            "a link that came up and died in under a second is a failed attempt",
        )
        h.supervisor.stop()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    fun stoppingTheSupervisorEndsTheLoopAndReleasesTheTransport() = runTest {
        val h = Harness(this)
        val held = FakeTransport(FakeTransport.Outcome.Holds)
        h.script = { held }
        h.supervisor.start()
        runCurrent()
        assertEquals(1, held.connectCalls)

        h.supervisor.stop()
        advanceTimeBy(5 * 60_000L)
        runCurrent()

        assertEquals(1, h.built.size, "no further attempts after stop()")
        assertTrue(!h.supervisor.running)
    }

    @Test
    fun aBuildThatThrowsIsAFailedAttemptNotADeadLoop() = runTest {
        val h = Harness(this)
        var builds = 0
        val good = FakeTransport(FakeTransport.Outcome.Holds)
        val supervisor = ReconnectSupervisor(
            scope = this,
            build = {
                builds++
                if (builds < 3) throw IllegalArgumentException("bad MAC") else good
            },
            onTransport = {},
            onError = { h.errors += it },
            log = { h.logs += it },
            now = { testScheduler.currentTime },
        )
        supervisor.start()
        advanceTimeBy(30_000L)
        runCurrent()

        assertEquals(1, good.connectCalls)
        assertTrue(h.errors.contains("bad MAC"))
        supervisor.stop()
    }

    @Test
    fun aSuccessfulConnectClearsTheLastError() = runTest {
        val h = Harness(this)
        var served = 0
        h.script = {
            served++
            if (served == 1) FakeTransport(FakeTransport.Outcome.Fails(afterMs = 100, message = "boom"))
            else FakeTransport(FakeTransport.Outcome.Holds)
        }
        h.supervisor.start()
        advanceTimeBy(10_000L)
        runCurrent()

        assertEquals("boom", h.errors.first())
        assertEquals(null, h.errors.last())
        h.supervisor.stop()
    }

    // ------------------------------------------------------------------
    // Backoff schedule
    // ------------------------------------------------------------------

    @Test
    fun theBackoffScheduleDoublesAndIsCapped() {
        assertEquals(1_000L, ReconnectSupervisor.backoffMs(0))
        assertEquals(1_000L, ReconnectSupervisor.backoffMs(1))
        assertEquals(2_000L, ReconnectSupervisor.backoffMs(2))
        assertEquals(4_000L, ReconnectSupervisor.backoffMs(3))
        assertEquals(8_000L, ReconnectSupervisor.backoffMs(4))
        assertEquals(16_000L, ReconnectSupervisor.backoffMs(5))
        assertEquals(32_000L, ReconnectSupervisor.backoffMs(6))
        assertEquals(60_000L, ReconnectSupervisor.backoffMs(7))
    }

    /** A long absence must not overflow the shift into a negative delay. */
    @Test
    fun theBackoffNeverOverflowsHoweverLongTheRadioIsAway() {
        for (n in 0..10_000) {
            val ms = ReconnectSupervisor.backoffMs(n)
            assertTrue(
                ms in 1L..ReconnectSupervisor.MAX_BACKOFF_MS,
                "backoff($n) = $ms is out of range",
            )
        }
        assertEquals(ReconnectSupervisor.MAX_BACKOFF_MS, ReconnectSupervisor.backoffMs(Int.MAX_VALUE))
    }
}
