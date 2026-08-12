package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Regions

/**
 * What joining a channel should do with the flood scope its code asked
 * for — and what to tell the user about it.
 *
 * A scope is not decoration. It decides how far every message on the
 * channel travels, so all four answers below have to be distinguishable
 * and all four have to be said out loud. The version this replaced
 * could only say one thing ("Channel added"), because the decoder
 * canonicalised the scope before this point and an unusable name
 * arrived indistinguishable from no name at all.
 *
 * Pure so it can be tested without a radio or a device — the side
 * effects (writing the preference, adding the region) stay with the
 * caller.
 */
object ChannelScopeJoin {

    sealed interface Outcome {
        /** The code carried no scope. Nothing to say. */
        data object NoScope : Outcome

        /**
         * The code carried a scope this build cannot canonicalise —
         * most likely a newer app's. The channel is still worth
         * joining, but it will flood globally and the user has to know:
         * this is the silent-widening case the feature exists to stop.
         */
        data class Unusable(val raw: String) : Outcome

        /** Scope recorded. [newRegion] when the region was not known locally. */
        data class Applied(val region: String, val newRegion: Boolean) : Outcome

        /** The channel already carries exactly this scope. */
        data class AlreadyScoped(val region: String) : Outcome

        /**
         * The code and the local setting disagree.
         *
         * NOT resolved in favour of the code. A narrower scope the user
         * chose by hand is a deliberate routing decision, and a QR
         * somebody held up is not authority to undo it.
         */
        data class Conflict(val codeRegion: String, val localRegion: String) : Outcome
    }

    /**
     * Decide what [rawScope] — the code's own spelling, never a
     * canonicalised one — means for a channel currently scoped to
     * [currentRegion] (null when unscoped), given the [knownRegions]
     * this phone has.
     */
    fun decide(
        rawScope: String,
        currentRegion: String?,
        knownRegions: Collection<String>,
    ): Outcome {
        if (rawScope.isBlank()) return Outcome.NoScope
        val region = Regions.canonical(rawScope) ?: return Outcome.Unusable(rawScope.trim())
        val current = Regions.canonical(currentRegion)
        return when {
            current == region -> Outcome.AlreadyScoped(region)
            current != null -> Outcome.Conflict(region, current)
            else -> Outcome.Applied(region, newRegion = knownRegions.none { it == region })
        }
    }

    /** The clause to append to a join message. Empty for [Outcome.NoScope]. */
    fun describe(outcome: Outcome): String = when (outcome) {
        Outcome.NoScope -> ""
        is Outcome.Unusable ->
            " — but its region \"${outcome.raw}\" isn't one this app can use, " +
                "so messages here will flood the whole mesh"
        is Outcome.Applied ->
            if (outcome.newRegion) {
                ", scoped to #${outcome.region} — new region added"
            } else {
                ", scoped to #${outcome.region}"
            }
        is Outcome.AlreadyScoped -> ", already scoped to #${outcome.region}"
        is Outcome.Conflict ->
            " — the code scopes it to #${outcome.codeRegion}, but you have it on " +
                "#${outcome.localRegion}. Change it in Settings → Channels if that's wrong."
    }
}
