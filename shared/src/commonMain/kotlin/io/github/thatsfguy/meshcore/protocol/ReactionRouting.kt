package io.github.thatsfguy.meshcore.protocol

/**
 * Deciding what an inbound reaction refers to, and whether it should
 * interrupt anyone.
 *
 * A reaction arrives as an ordinary text message whose body is
 * `r:HHHH:II` — a 16-bit hash of the message being reacted to, and an
 * emoji index. Nothing on the wire says *which* message; the hash has
 * to be recomputed over local rows until one matches. That work lived
 * in `MessageRepository`, which meant the whole rule — including its
 * hostile-input surface — could only be exercised with a database and
 * an Android device.
 *
 * It is pure, so it lives here now, and iOS gets it for free.
 *
 * Three things this has to get right, and each has bitten already:
 *
 *  - **The hash is computed differently per thread kind.** A channel
 *    message's hash folds in its sender name; a direct message's does
 *    not. Using one rule for both silently matches nothing.
 *  - **16 bits collide.** Two messages in the search window really can
 *    share a hash, and the wire offers no tiebreak. Matching the most
 *    recent is the documented behaviour, and it is a *choice*, not a
 *    certainty — [target] returns the newest match and [isAmbiguous]
 *    says when there was more than one, so a caller can decline.
 *  - **A reaction can arrive three times** — our own local application,
 *    the radio's echo, and the RX log — and never becomes a row, so the
 *    database's unique index cannot dedup it. [SeenKeys] does.
 */
object ReactionRouting {

    /**
     * How far back a reaction may reach.
     *
     * The wire format carries no thread position, only a 16-bit hash,
     * so a wider window means more chances to collide with an unrelated
     * message. 200 covers any realistic "react to something in view"
     * while keeping the collision odds negligible.
     */
    const val SEARCH_WINDOW = 200

    /**
     * Reactions remembered for echo suppression.
     *
     * 512 because that is what Android ran with before this rule moved
     * here, and a dedup window is not something to change quietly while
     * relocating it — a smaller one would start double-counting taps
     * again, which is the exact bug the set exists to prevent.
     */
    const val MAX_SEEN = 512

    /** A local message a reaction might be pointing at. */
    data class Candidate(
        val id: Long,
        val timestamp: Long,
        /** Channel messages only; unauthenticated display text. */
        val senderName: String?,
        val text: String,
        val outgoing: Boolean,
    )

    /**
     * Every candidate whose hash matches [targetHash], newest first.
     *
     * [candidates] must already be newest-first, which is how the store
     * reads them back.
     */
    fun matches(
        candidates: List<Candidate>,
        targetHash: String,
        isChannel: Boolean,
    ): List<Candidate> = candidates.filter { c ->
        // A channel hash folds in the message's own sender name; a DM
        // hash does not. Getting this backwards matches nothing at all,
        // which looks exactly like "nobody reacted".
        val hashSender = if (isChannel) c.senderName.orEmpty() else null
        Reactions.targetHash(c.timestamp, hashSender, c.text)
            .equals(targetHash, ignoreCase = true)
    }

    /** The message a reaction refers to: the newest match, or null. */
    fun target(
        candidates: List<Candidate>,
        targetHash: String,
        isChannel: Boolean,
    ): Candidate? = matches(candidates, targetHash, isChannel).firstOrNull()

    /**
     * Whether more than one message in the window shares the hash.
     *
     * Not a failure — [target] still answers — but the caller may want
     * to know that the answer was a choice among several.
     */
    fun isAmbiguous(
        candidates: List<Candidate>,
        targetHash: String,
        isChannel: Boolean,
    ): Boolean = matches(candidates, targetHash, isChannel).size > 1

    /**
     * Whether a reaction landing on [target] should interrupt anyone.
     *
     * Only reactions to our OWN messages notify. A thumbs-up on
     * something you said is often the whole reply and is worth an
     * interruption; a reaction to a third party's message in a busy
     * channel is somebody else's conversation, and notifying on it
     * would make channels unusable.
     */
    fun shouldNotify(target: Candidate): Boolean = target.outgoing

    /**
     * A bounded set of keys already seen, oldest evicted first.
     *
     * Reactions never become rows, so the database's unique
     * `(selfKey, contentKey)` index cannot dedup them — without this,
     * one tap counted three times. Bounded because the keys come off
     * the mesh.
     */
    class SeenKeys(private val max: Int = MAX_SEEN) {
        private val keys = LinkedHashSet<String>()

        /** True the FIRST time [key] is offered; false on every repeat. */
        fun remember(key: String): Boolean {
            val fresh = keys.add(key)
            while (keys.size > max) keys.remove(keys.first())
            return fresh
        }

        /** A null key cannot be deduped, so it always reads as fresh. */
        fun firstSight(key: String?): Boolean = key == null || remember(key)

        val size: Int get() = keys.size
    }
}
