package io.github.thatsfguy.meshcore.presentation

/**
 * When did we last hear from this node — the one place that answers it.
 *
 * There are two timestamps on a contact and only one of them answers
 * that question:
 *
 *  - [NodeListItem.lastSeen] is the clock reading the node put in its
 *    own advert. It is a CLAIM. The firmware keeps it to reject replays
 *    (`BaseChatMesh.cpp:131`) and for nothing else.
 *  - [NodeListItem.lastModified] is our own radio's RTC, stamped every
 *    time an advert or a message arrives from that contact
 *    (`BaseChatMesh.cpp:117`, `:196`, `:238` — the last one commented
 *    "update last heard time").
 *
 * Reading the first one as "last heard" was wrong everywhere it
 * appeared, and visibly so: driven on a live mesh 2026-08-24, a
 * repeater the radio had heard that day rendered as **"20688 days ago ·
 * Jan 1, 1970"**, because its own clock is unset. Others in the same
 * list read 830 days. Those nodes are on the air; their clocks are not.
 *
 * The list row and the detail sheet were cosmetically wrong. The sort
 * and the "heard in last 24 h" filter were worse than cosmetic: they
 * are the controls for finding out who is actually alive, and they were
 * answering with what each node says about itself. A node with a broken
 * RTC could never pass the filter however recently it was heard, and
 * one claiming a time in the future sorted to the top for ever.
 */
object LastHeard {

    /**
     * Epoch **seconds** our radio last heard from [node]; 0 = never.
     *
     * Deliberately NOT clamped at zero. A garbage record reads as
     * negative, and descending order then sinks it below even a node
     * never heard at all — which is the right place for a number that
     * cannot be true, and is the behaviour `NodeListModel` already
     * relies on. Clamping would make it tie with "never heard" and
     * sort alphabetically from there.
     */
    fun seconds(node: NodeListItem): Long = node.lastModified

    /**
     * Epoch millis for the same, for millisecond maths.
     *
     * Callers that mean "is there any evidence at all" must test for
     * `> 0`, not for non-null: see [StaleNodes.lastEvidenceMillis].
     */
    fun millis(node: NodeListItem): Long = seconds(node) * 1000L

    /**
     * A day. Below this, a difference between the node's claim and our
     * observation is ordinary clock skew and worth nobody's attention.
     */
    const val CLAIM_TOLERANCE_SECONDS = 86_400L

    /**
     * True when the node's advert claims a time that disagrees
     * materially with when we actually heard it.
     *
     * Surfaced rather than hidden: a node whose clock is wrong is a
     * real, diagnosable thing about that node — and it explains what
     * its owner would otherwise see as a bug in every other client.
     */
    fun claimDisagrees(node: NodeListItem): Boolean {
        if (node.lastSeen <= 0 || seconds(node) <= 0) return false
        val skew = node.lastSeen - seconds(node)
        return skew > CLAIM_TOLERANCE_SECONDS || skew < -CLAIM_TOLERANCE_SECONDS
    }
}
