package io.github.thatsfguy.meshcore.presentation

/**
 * Which time a message bubble shows.
 *
 * MeshCore carries one time per message: the SENDER's clock, which is
 * unauthenticated and sometimes wrong. The receive frame has no sequence
 * number and no time the radio heard it (MESHCORE_PROTOCOL §9), so the
 * only other clock is ours — when the app stored the row.
 *
 * Neither is right on its own:
 * - **Ours** is wrong for a backlog. Messages the radio queued while the
 *   phone was away are all stored at reconnect, so an overnight backlog
 *   would read "7:02 AM" from top to bottom.
 * - **Theirs** is wrong for a clock that runs ahead. A message cannot have
 *   been sent after we received it.
 *
 * So: the sender's time, unless it is later than our arrival, in which
 * case arrival. Threads are ORDERED by arrival regardless (Daos.thread);
 * this only decides the label.
 */
object MessageClock {

    /** Epoch seconds to show for a message. */
    fun shownSec(senderSec: Long, receivedAtMs: Long, outgoing: Boolean): Long {
        val arrivedSec = receivedAtMs / 1000
        if (outgoing) return arrivedSec
        return if (senderSec in 1..arrivedSec) senderSec else arrivedSec
    }
}
