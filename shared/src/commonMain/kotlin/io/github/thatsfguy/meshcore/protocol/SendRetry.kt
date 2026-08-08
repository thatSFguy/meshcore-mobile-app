package io.github.thatsfguy.meshcore.protocol

/**
 * How a direct message is routed across its retry attempts.
 *
 * This is MeshCore's own documented behaviour, not an invention and not
 * a copy of any one client. From the firmware repo's FAQ
 * (`meshcore-dev/MeshCore/docs/faq.md`):
 *
 * > "If you used to reach a node through a repeater and the repeater is
 * > no longer reachable, the client will send the message using the
 * > existing (but now broken) known path, the message will fail after 3
 * > retries, and the app will reset the path and send the message as
 * > flood on the last retry by default. This can be turned off in
 * > settings."
 *
 * Three things in that sentence are load-bearing and it is worth being
 * explicit about all of them, because the first cut of this feature had
 * only the middle one:
 *
 *  1. **Three attempts**, not five. MeshCore Open uses five
 *     (`maxMessageRetries`), which is that client's choice, not the
 *     documented default.
 *  2. **The last attempt floods.** Retrying an identical frame down an
 *     identical route mostly re-tests the thing that just failed; the
 *     usual reason a retry succeeds is that the route changed.
 *  3. **The path is reset**, not just bypassed for one send. Without
 *     this the radio keeps the dead path and the next message starts
 *     the same three-attempt cycle over again.
 *
 * A client flood is the most expensive packet on a shared medium — the
 * same FAQ notes that MeshCore clients never repeat, precisely to keep
 * the air clear — so exactly one is allowed, and only when there is a
 * stored path that could be stale in the first place.
 */
object SendRetry {

    /** The documented default. */
    const val DEFAULT_MAX_ATTEMPTS: Int = 3

    /**
     * How long to keep listening for an ACK after the last attempt has
     * been given up on.
     *
     * A timeout is an estimate, not a deadline the mesh agreed to. An
     * ACK that arrives a second after we stopped waiting means the
     * message WAS delivered, and showing it as failed is simply wrong —
     * it invites the user to send it again over a link that worked.
     *
     * The status still goes to Failed at the end of the attempts,
     * because that is the honest report of what we know then; this
     * window only lets a later fact correct it. 30s matches what
     * MeshCore Open allows itself, and is comfortably longer than the
     * flood timeout at the slowest spreading factor most meshes use.
     */
    const val LATE_ACK_GRACE_MS: Long = 30_000

    /** What to do for one attempt. */
    enum class Route {
        /** Send over whatever path the radio currently holds. */
        StoredPath,

        /**
         * Clear the radio's stored path for this contact, then send —
         * which the firmware transmits as a flood, and which leaves the
         * contact path-less so the next reply can teach it a new one.
         */
        ResetAndFlood,
    }

    /**
     * Route for [attempt] (0-based) of [maxAttempts].
     *
     * [hasStoredPath] false means the radio is already flooding, so
     * there is nothing to reset and no fallback to make — asking for one
     * would spend an extra flood to reach the state we are already in.
     */
    fun routeFor(
        attempt: Int,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        hasStoredPath: Boolean,
        floodFallbackEnabled: Boolean = true,
    ): Route {
        if (!floodFallbackEnabled || !hasStoredPath) return Route.StoredPath
        if (maxAttempts <= 1) return Route.StoredPath
        return if (attempt >= maxAttempts - 1) Route.ResetAndFlood else Route.StoredPath
    }

    /**
     * Whether a failed attempt should count against the stored path's
     * score.
     *
     * A flood attempt is not evidence about the path — the path was
     * thrown away before it was sent. Blaming it would let one bad
     * delivery record two failures against a route that only carried
     * one of them.
     */
    fun scoresStoredPath(route: Route): Boolean = route == Route.StoredPath
}
