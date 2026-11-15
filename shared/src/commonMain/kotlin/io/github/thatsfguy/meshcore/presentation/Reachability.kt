package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.util.RelativeTime

/**
 * "Can I talk to this node?" — answered from evidence the app already
 * holds, without transmitting anything (GUIDED-UX.md, question 1).
 *
 * Hearing a node proves only its direction. Chicago nodes are heard all
 * day around Grand Rapids and almost never answer; the list showed them
 * exactly like nodes that do. This sorts what is known into a verdict and
 * says what it rests on.
 *
 * The rule is the one heard-via follows: never claim more than the
 * evidence. Proof ages — routes change — and silence is never
 * "unreachable", only "nothing confirmed".
 */
object Reachability {

    /** Proof younger than this is "two-way"; older is "two-way before". */
    const val FRESH_SECONDS = 7L * 86_400

    /** A heard-only node this many hops away or more is "far away". */
    const val FAR_HOPS = 4

    /**
     * ...or this far away, when its position is known. Hops alone missed
     * the case this was built for: on the owner's mesh, Chicago nodes
     * 134-151 mi off arrive in 3 hops, because a few long links carry
     * them (seen on the phone, 2026-11-15). 100 km keeps the home mesh
     * — roughly 50 mi across — on the near side.
     */
    const val FAR_METRES = 100_000.0

    enum class State {
        /** Both directions proven within [FRESH_SECONDS]. */
        TwoWay,

        /** Both directions proven, but not recently. */
        TwoWayBefore,

        /** They have heard us (a route they sent back); our side unproven. */
        TheyHeardYou,

        /** We hear them; nothing shows they hear us. */
        HeardOnly,

        /** We sent them something and nothing was ever confirmed; never heard them. */
        NoAnswer,

        /** Nothing known either way. */
        Unknown,
    }

    data class Evidence(
        /**
         * Newest proof of both directions, epoch seconds: a direct message
         * confirmed delivered, or a request they answered (ping, sign-in,
         * telemetry, path discovery). Null if never.
         */
        val confirmedAt: Long? = null,
        /**
         * A repeater relayed our own packets, and we heard it do so —
         * both directions, with no date attached (RepeaterSignals).
         */
        val repeatsUs: Boolean = false,
        /**
         * The radio holds a route to them that THEY sent back, which the
         * firmware does only after receiving a flood from us
         * (BaseChatMesh.cpp:250-254, 353-357). False for a route the user
         * pinned by hand: that proves nothing about them.
         */
        val routeFromThem: Boolean = false,
        /** We have received something from them. */
        val heardThem: Boolean = false,
        /** We have sent them something that asked for an answer. */
        val tried: Boolean = false,
    )

    data class Verdict(
        val state: State,
        /** The full sentence, for the node's sheet. Empty for [State.Unknown]. */
        val line: String,
    ) {
        /** Two or three words for a list row; null when there is nothing to say. */
        val short: String?
            get() = when (state) {
                State.TwoWay -> "Two-way"
                State.TwoWayBefore -> "Two-way before"
                State.TheyHeardYou -> "They've heard you"
                State.HeardOnly -> "Heard only"
                State.NoAnswer -> "No answer yet"
                State.Unknown -> null
            }

        val isTwoWay: Boolean get() = state == State.TwoWay || state == State.TwoWayBefore
    }

    fun of(e: Evidence, nowSeconds: Long): Verdict {
        // A proof stamped in the future (clock changed) is treated as now,
        // not thrown away: it was still a proof.
        val age = e.confirmedAt?.let { (nowSeconds - it).coerceAtLeast(0) }
        return when {
            age != null && age <= FRESH_SECONDS ->
                Verdict(State.TwoWay, "Two-way · confirmed ${RelativeTime.ago(age)}")
            e.repeatsUs ->
                Verdict(State.TwoWay, "Two-way · repeats your messages")
            age != null ->
                Verdict(State.TwoWayBefore, "Two-way before · last confirmed ${RelativeTime.ago(age)}")
            e.routeFromThem ->
                Verdict(
                    State.TheyHeardYou,
                    "They've heard you · they sent a route back, but nothing you sent has been " +
                        "confirmed yet",
                )
            e.heardThem && e.tried ->
                Verdict(State.HeardOnly, "Heard only · nothing you've sent has reached them")
            e.heardThem ->
                Verdict(State.HeardOnly, "Heard only · nothing shows they can hear you")
            e.tried ->
                Verdict(State.NoAnswer, "No answer yet · nothing you've sent has been confirmed")
            else -> Verdict(State.Unknown, "")
        }
    }

    /**
     * Heard only, and [FAR_HOPS] or more hops or [FAR_METRES] or more away:
     * the Chicago case. Folded out of the way on the Nodes list, and adding
     * one asks first. Unknown hops and unknown position are not far — that
     * would hide nodes for lack of data.
     */
    fun isFarHeardOnly(verdict: Verdict, hops: Int?, metres: Double? = null): Boolean =
        verdict.state == State.HeardOnly &&
            ((hops != null && hops >= FAR_HOPS) || (metres != null && metres >= FAR_METRES))

    /**
     * What adding a far heard-only node should warn about first.
     * [distance] is already formatted in the user's units, or null.
     */
    fun addWarning(hops: Int?, distance: String?): String {
        val where = listOfNotNull(
            hops?.let { if (it == 1) "1 hop" else "$it hops" },
            distance?.let { "$it away" },
        ).joinToString(", ")
        return (if (where.isEmpty()) "Nothing" else "This node is $where, and nothing") +
            " shows it can hear you. Nodes this far away often can't be reached, and their " +
            "owners have to add you too before a direct message gets through."
    }

    /**
     * The answer to a path discovery ("Check"), in words.
     *
     * [outHops] / [inHops] are the hop names in order (empty = direct), or
     * null when nothing came back. [asksPermission] is true for nodes that
     * answer only if their owner allows it — people and sensors — so that
     * silence from them is explained rather than read as "unreachable".
     */
    fun describeCheck(outHops: List<String>?, inHops: List<String>?, asksPermission: Boolean): String {
        if (outHops == null || inHops == null) {
            return if (asksPermission) {
                "No answer. That doesn't mean they can't hear you: most radios only answer " +
                    "this check if their owner allows it, and they must have added you. " +
                    "A message they confirm (✓✓) is proof."
            } else {
                "No answer. They may not hear you, or the answer was lost on the way back. " +
                    "Try again in a minute."
            }
        }
        fun route(hops: List<String>) =
            if (hops.isEmpty()) "directly" else "through " + hops.joinToString(", ")
        return "Two-way. Your messages reach them ${route(outHops)}; theirs reach you ${route(inHops)}."
    }
}
