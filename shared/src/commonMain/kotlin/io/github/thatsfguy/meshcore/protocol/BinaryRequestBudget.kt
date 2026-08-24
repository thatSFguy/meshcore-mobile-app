package io.github.thatsfguy.meshcore.protocol

/**
 * How long to wait for a repeater to answer a binary request, and what
 * to tell the user while waiting.
 *
 * ## Why not a fixed number
 *
 * Status, the access list and the neighbour table all waited a flat
 * 30 s, while the LOGIN to the same node used the radio's own estimate,
 * clamped 5–45 s. That is the asymmetry behind "the login works but the
 * fetch times out on a far repeater" — and note that the fetch was
 * already being retried, by `withPathRecovery`; it was each attempt
 * that was cut short, not the number of them.
 *
 * The radio hands us the number. `RESP_CODE_SENT` carries `timeout_ms`,
 * derived from the real airtime and hop count of the path it just used
 * (MESHCORE_PROTOCOL §11), and it was being received and discarded on
 * this path.
 *
 * ## Why the estimate is doubled
 *
 * The firmware's figure covers getting a packet THERE. A request is a
 * round trip — out, the node's own `SERVER_RESPONSE_DELAY`, then a
 * reply that is bigger than an ACK coming back the same way. Measured
 * against a live mesh at 62.5 kHz / SF7 / CR 4/5, a 5-hop path
 * estimates about 17 s one way, which is inside the old 30 s and its
 * round trip is not.
 *
 * [MIN_BUDGET_MS] exists because a 0-hop estimate is about 2 s and a
 * node still has to do the work. [MAX_BUDGET_MS] is the point past
 * which the answer is "this link is not working", not "wait longer".
 */
object BinaryRequestBudget {

    /** Never wait less than this, whatever the radio estimates. */
    const val MIN_BUDGET_MS = 20_000L

    /** Never wait longer than this. Past here the link is the problem. */
    const val MAX_BUDGET_MS = 90_000L

    /** Slack on top, for the node's own processing and reply delay. */
    const val GRACE_MS = 4_000L

    /**
     * Attempts a fetch already gets, for the label's sake — NOT a knob.
     *
     * There is deliberately no retry loop here. A signed-in node's
     * request is already wrapped in `MeshCoreEngine.withPathRecovery`,
     * which tries, resets the route and tries again, re-authenticates
     * and tries a third time — a smarter escalation than a blind
     * resend, and one that stops as soon as a repair works.
     *
     * A second retry underneath it multiplies: three recovery attempts
     * of two requests each is six trips to a node that is probably out
     * of range, and at this budget that is minutes of waiting nobody
     * asked for. The fix for "the fetch gives up too early" was the
     * budget above, not more attempts.
     */
    const val RECOVERY_ATTEMPTS = 3

    /** What the radio told us when it put the request on the air. */
    data class InFlight(
        val isFlood: Boolean,
        /** The radio's own one-way estimate, ms. 0 when it gave none. */
        val estimateMs: Long,
        /** What we will actually wait, ms. */
        val budgetMs: Long,
        /** 1-based, for "attempt 2 of 3" during path recovery. */
        val attempt: Int = 1,
        val ofAttempts: Int = 1,
    )

    /** The wait for a one-way estimate of [estimateMs] (0 = unknown). */
    fun budget(estimateMs: Long): Long {
        if (estimateMs <= 0) return MIN_BUDGET_MS
        return (estimateMs * 2 + GRACE_MS).coerceIn(MIN_BUDGET_MS, MAX_BUDGET_MS)
    }

    /**
     * The line shown while waiting — real evidence, not a fake bar.
     *
     * There is no such thing as a partly-arrived LoRa response, so the
     * only honest progress is: the radio accepted the frame and put it
     * on the air, this is how it sent it, and this is how long it
     * thinks the answer should take. Once that estimate passes we say
     * so rather than letting a spinner imply the same confidence it had
     * at second one.
     */
    fun progressLabel(inFlight: InFlight, remainingMs: Long): String {
        val attempt = if (inFlight.ofAttempts > 1 && inFlight.attempt > 1) {
            "Attempt ${inFlight.attempt} of ${inFlight.ofAttempts} · "
        } else {
            ""
        }
        val how = if (inFlight.isFlood) "sent as a flood" else "sent over the stored path"
        if (remainingMs <= 0) {
            return "$attempt${how.replaceFirstChar { it.uppercase() }} · " +
                "past the radio's estimate, still listening"
        }
        val seconds = (remainingMs + 999) / 1000
        return "$attempt${how.replaceFirstChar { it.uppercase() }} · " +
            "reply expected within ${seconds}s"
    }

    /** Said when the radio never even confirmed it transmitted. */
    const val NOT_SENT = "The radio did not confirm it sent the request."
}
