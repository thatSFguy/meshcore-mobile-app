package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How long to wait for a node, and what to say while waiting.
 *
 * The defect behind this: status, telemetry, the access list and the
 * neighbour table all waited a flat 30 s with no retry, while the LOGIN
 * to the same node used the radio's own estimate and tried three times.
 * A far repeater would sign in and then fail every fetch.
 */
class BinaryRequestBudgetTest {

    @Test
    fun `the radio's estimate is doubled because a fetch is a round trip`() {
        // The firmware's figure covers getting a packet THERE. The reply
        // has to come back over the same path, and it is bigger than an
        // ACK. Measured at 62.5 kHz / SF7: a 5-hop path estimates ~17s
        // one way, which fits inside the old 30s while its round trip
        // does not.
        assertEquals(
            17_000L * 2 + BinaryRequestBudget.GRACE_MS,
            BinaryRequestBudget.budget(17_000),
        )
    }

    @Test
    fun `a near node does not hold the maximum open`() {
        // A 0-hop estimate is about 2s. Waiting 90s for it would make
        // every mistyped request feel like a hang.
        assertEquals(BinaryRequestBudget.MIN_BUDGET_MS, BinaryRequestBudget.budget(2_000))
    }

    @Test
    fun `an absent estimate falls back to the floor rather than to zero`() {
        // A radio that reports no timeout must not produce a budget of
        // 4 seconds — or, worse, of none.
        assertEquals(BinaryRequestBudget.MIN_BUDGET_MS, BinaryRequestBudget.budget(0))
        assertEquals(BinaryRequestBudget.MIN_BUDGET_MS, BinaryRequestBudget.budget(-1))
    }

    @Test
    fun `an absurd estimate is capped rather than believed`() {
        // The estimate is computed by the radio from values that reach
        // it over the air. Past the cap the answer is "this link is not
        // working", not "wait longer".
        assertEquals(BinaryRequestBudget.MAX_BUDGET_MS, BinaryRequestBudget.budget(600_000))
    }

    @Test
    fun `every budget beats the flat thirty seconds it replaced for a far node`() {
        // The regression this is really guarding: a 5-hop estimate must
        // not come back under the old fixed wait.
        assertTrue(BinaryRequestBudget.budget(17_000) > 30_000)
        assertTrue(BinaryRequestBudget.budget(23_000) > 30_000)
    }

    // --- What the user is told while waiting ---------------------------

    private fun inFlight(
        isFlood: Boolean = false,
        estimateMs: Long = 17_000,
        attempt: Int = 1,
        ofAttempts: Int = 2,
    ) = BinaryRequestBudget.InFlight(
        isFlood = isFlood,
        estimateMs = estimateMs,
        budgetMs = BinaryRequestBudget.budget(estimateMs),
        attempt = attempt,
        ofAttempts = ofAttempts,
    )

    @Test
    fun `the wait says how the request went out and when to expect an answer`() {
        assertEquals(
            "Sent over the stored path · reply expected within 34s",
            BinaryRequestBudget.progressLabel(inFlight(), remainingMs = 34_000),
        )
        assertEquals(
            "Sent as a flood · reply expected within 12s",
            BinaryRequestBudget.progressLabel(inFlight(isFlood = true), remainingMs = 11_200),
        )
    }

    @Test
    fun `past the estimate it says so instead of spinning on`() {
        // A spinner reads the same at second 1 and second 60. This is
        // the moment the user is entitled to know the node is late.
        val label = BinaryRequestBudget.progressLabel(inFlight(), remainingMs = 0)
        assertTrue(label.contains("past the radio's estimate"))
        assertTrue(label.contains("still listening"))
        assertEquals(label, BinaryRequestBudget.progressLabel(inFlight(), remainingMs = -5_000))
    }

    @Test
    fun `a retry says which attempt it is`() {
        assertTrue(
            BinaryRequestBudget.progressLabel(inFlight(attempt = 2), 20_000)
                .startsWith("Attempt 2 of 2 · "),
        )
        // The first attempt is not announced — nobody needs "attempt 1
        // of 2" for the ordinary case.
        assertTrue(
            !BinaryRequestBudget.progressLabel(inFlight(attempt = 1), 20_000).contains("Attempt"),
        )
    }
}
