package io.github.thatsfguy.meshcore.protocol

/**
 * What to do when a repeater you are signed into stops answering.
 *
 * ## There is no session to expire
 *
 * Verified against the firmware 2026-08-09. A login writes an entry
 * into the repeater's ACL keyed by your public key (`ClientACL.h`) and
 * nothing ever expires it: no TTL is compared against `last_activity`,
 * admin entries are exempt from the LRU eviction that trims the table
 * (`ClientACL.cpp:102` only considers `!clients[i].isAdmin()`), and
 * non-guest entries are written to flash so they outlive a reboot. The
 * keep-alive interval in the login reply is hardcoded to `0` on both
 * the repeater and the room server, commented "Legacy", and the
 * companion only starts a keep-alive connection when it is non-zero —
 * so that machinery never runs either.
 *
 * So "the session went stale" is never the diagnosis. Signing out and
 * back in cannot fix authentication, because authentication was never
 * lost.
 *
 * ## What it does fix is the route
 *
 * The repeater caches the way back to you in `ClientInfo.out_path` and
 * answers with `sendDirect()` down it. When the mesh moves, your
 * requests still arrive — they flood in — and the replies go down a
 * path that no longer exists. From the app that is indistinguishable
 * from a node that has stopped listening.
 *
 * The repair is in the firmware's own login handler:
 *
 * ```cpp
 * if (is_flood) {
 *     client->out_path_len = OUT_PATH_UNKNOWN;  // need to rediscover out_path
 * }
 * ```
 *
 * — a login that arrives as a **flood** clears the dead path, and the
 * reply re-learns it. A login sent down the same broken path fixes
 * nothing, which is why this escalates through [Stage.PathReset]:
 * clear our own stored path first (`CMD_RESET_PATH`) so the login has
 * no choice but to flood.
 *
 * ## The probe is free
 *
 * A login carrying a **blank password** is handled as "am I still in
 * your ACL?" — `handleLoginReq` looks the sender up and, finding them,
 * skips the password comparison entirely. So the first repair costs
 * nothing but airtime and never puts the credential back on the air.
 * Only when that goes unanswered — meaning we really have been
 * forgotten, or the node is gone — is [Stage.Reauthenticated] worth
 * spending the password on.
 *
 * That ordering is the whole point of this class, and it is the reason
 * the stages are a type rather than three booleans at the call site.
 */
object PathRecovery {

    /**
     * How far the recovery has escalated. Each stage is entered at most
     * once and only ever forwards, so a caller that loops on
     * [escalate] terminates.
     */
    enum class Stage {
        /** Nothing tried yet — the plain request. */
        Initial,

        /** Cleared our stored path and probed with a blank-password login. */
        PathReset,

        /** Spent the stored password on a full login. */
        Reauthenticated,

        /** Out of repairs. */
        Exhausted,
    }

    /**
     * The next repair to try after the request at [from] went
     * unanswered.
     *
     * [hasPassword] false skips [Stage.Reauthenticated] rather than
     * attempting it and failing: without a credential there is nothing
     * to send, and a stage that cannot act would only cost the user
     * another timeout before saying the same thing.
     */
    fun escalate(from: Stage, hasPassword: Boolean): Stage = when (from) {
        Stage.Initial -> Stage.PathReset
        Stage.PathReset -> if (hasPassword) Stage.Reauthenticated else Stage.Exhausted
        Stage.Reauthenticated -> Stage.Exhausted
        Stage.Exhausted -> Stage.Exhausted
    }

    /**
     * True when entering [stage] transmits the stored password.
     *
     * Exists so the ordering above can be asserted directly: the free
     * probe must always come first.
     */
    fun sendsPassword(stage: Stage): Boolean = stage == Stage.Reauthenticated

    /**
     * How many times the request itself is sent, worst case — the first
     * attempt plus one after each repair.
     */
    fun requestAttempts(hasPassword: Boolean): Int = if (hasPassword) 3 else 2

    /**
     * What to tell the user while a repair is running, or null for
     * stages that are not worth narrating.
     *
     * Recovery takes tens of seconds and the screen would otherwise sit
     * on a spinner that had already timed out once. Saying *what* is
     * being retried is also the honest version: "re-establishing the
     * route" is the actual diagnosis, where "signing in again" would
     * be the wrong story to tell about a session that never lapsed.
     */
    fun progressLabel(stage: Stage): String? = when (stage) {
        Stage.PathReset -> "No answer — re-establishing the route…"
        Stage.Reauthenticated -> "Still nothing — signing in again…"
        Stage.Initial, Stage.Exhausted -> null
    }

    /**
     * What to tell the user when every repair has been spent.
     *
     * Names the two remaining explanations rather than blaming the
     * login, because by this point the login has been demonstrated not
     * to be the problem.
     */
    const val EXHAUSTED_MESSAGE: String =
        "No answer after re-routing. The node is out of reach, or off."
}
