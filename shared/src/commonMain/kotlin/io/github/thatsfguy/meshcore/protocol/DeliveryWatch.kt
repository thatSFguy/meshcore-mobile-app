package io.github.thatsfguy.meshcore.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Remembers which ACKs have arrived, so a send can ask "did any of mine
 * land?" at any moment without having been listening at the exact
 * instant one did.
 *
 * ## The gap this closes
 *
 * The engine's event flow has no replay: an event nobody is collecting
 * when it is emitted is gone. The retry loop used to open a fresh wait
 * per attempt, which left three holes, all of them a DELIVERED message
 * reported as a failure:
 *
 *  - an ACK arriving during the backoff between attempts, when nothing
 *    was collecting;
 *  - an ACK for an EARLIER attempt arriving later — each attempt is sent
 *    under its own hash and only the current one was being watched;
 *  - an ACK arriving just after the final attempt gave up.
 *
 * Recording into state instead of racing a listener fixes all three at
 * once: [awaitAny] re-checks what is already known before it waits, so
 * there is no window between one wait and the next.
 *
 * This holds only the hashes, never the messages, and the caller owns
 * its lifetime — one per send.
 */
class DeliveryWatch {

    private val seen = MutableStateFlow<Set<Long>>(emptySet())

    /** ACK hashes observed so far. */
    val observed: Set<Long> get() = seen.value

    /** Note that an ACK arrived. Safe to call from any coroutine. */
    fun record(ackHash: Long) {
        seen.value = seen.value + ackHash
    }

    /**
     * True as soon as any of [hashes] has been seen — including one seen
     * *before* this was called, which is the whole point.
     *
     * A [timeoutMs] of 0 makes this a pure "has it already arrived?"
     * check that never suspends on the timer. Empty [hashes] is false:
     * nothing has been sent, so nothing can have been delivered.
     */
    suspend fun awaitAny(hashes: Set<Long>, timeoutMs: Long): Boolean {
        if (hashes.isEmpty()) return false
        if (seen.value.any { it in hashes }) return true
        if (timeoutMs <= 0) return false
        return withTimeoutOrNull(timeoutMs) {
            seen.first { current -> current.any { it in hashes } }
        } != null
    }
}
