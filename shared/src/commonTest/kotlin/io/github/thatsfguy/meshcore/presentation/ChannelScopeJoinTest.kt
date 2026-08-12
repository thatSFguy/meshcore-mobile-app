package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Joining a channel from a code that carries a flood scope.
 *
 * Every one of these was a silent outcome before: the decoder
 * canonicalised the scope, the join path saw only the result, and
 * "Channel added" covered a scope applied, a scope dropped, and a scope
 * refused alike. The property under test is that the four cases are
 * *distinguishable* — a suite that only checked the happy path would
 * have passed against the broken version.
 */
class ChannelScopeJoinTest {

    private val known = listOf("bayarea", "grandrapids")

    @Test
    fun aCodeWithNoScopeSaysNothing() {
        val outcome = ChannelScopeJoin.decide("", currentRegion = null, knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.NoScope, outcome)
        assertEquals("", ChannelScopeJoin.describe(outcome))
    }

    @Test
    fun aKnownScopeIsAppliedWithoutClaimingANewRegion() {
        val outcome =
            ChannelScopeJoin.decide("bayarea", currentRegion = null, knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.Applied("bayarea", newRegion = false), outcome)
        assertEquals(", scoped to #bayarea", ChannelScopeJoin.describe(outcome))
    }

    @Test
    fun anUnknownScopeIsAppliedAndSaysTheRegionIsNew() {
        // Adding the region locally is what makes the channel usable —
        // a scope the app doesn't know aborts the send rather than
        // widening it. But it IS a routing change nobody asked for by
        // name, so it gets said.
        val outcome = ChannelScopeJoin.decide("socal", currentRegion = null, knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.Applied("socal", newRegion = true), outcome)
        assertTrue(ChannelScopeJoin.describe(outcome).contains("new region added"))
    }

    @Test
    fun aScopeIsCanonicalisedBeforeItIsCompared() {
        // "#BayArea" and "bayarea" are the same scope; treating them as
        // two would split a mesh in half over a capital letter.
        assertEquals(
            ChannelScopeJoin.Outcome.AlreadyScoped("bayarea"),
            ChannelScopeJoin.decide("#BayArea", currentRegion = "bayarea", knownRegions = known),
        )
    }

    @Test
    fun anUnusableScopeIsReportedRatherThanTreatedAsNoScope() {
        // THE bug. Both used to reach the join path as "", so a code
        // asking for a region this build can't parse produced exactly
        // the same message as a global channel — and the channel then
        // flooded the whole mesh.
        val outcome =
            ChannelScopeJoin.decide("Bay Area", currentRegion = null, knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.Unusable("Bay Area"), outcome)
        val message = ChannelScopeJoin.describe(outcome)
        assertTrue(message.contains("Bay Area"), message)
        assertTrue(message.contains("flood the whole mesh"), message)
        // And it must not be confusable with the silent case.
        assertTrue(message != ChannelScopeJoin.describe(ChannelScopeJoin.Outcome.NoScope))
    }

    @Test
    fun aLocalScopeIsNotOverwrittenByACode() {
        // Someone who narrowed a channel by hand made a routing
        // decision. A QR is not authority to undo it, so the
        // disagreement is reported and the local setting stands.
        val outcome =
            ChannelScopeJoin.decide("bayarea", currentRegion = "grandrapids", knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.Conflict("bayarea", "grandrapids"), outcome)
        val message = ChannelScopeJoin.describe(outcome)
        assertTrue(message.contains("bayarea") && message.contains("grandrapids"), message)
    }

    @Test
    fun rejoiningAChannelYouAlreadyHoldScopedSaysSo() {
        val outcome =
            ChannelScopeJoin.decide("bayarea", currentRegion = "bayarea", knownRegions = known)
        assertEquals(ChannelScopeJoin.Outcome.AlreadyScoped("bayarea"), outcome)
        assertEquals(", already scoped to #bayarea", ChannelScopeJoin.describe(outcome))
    }

    @Test
    fun everyOutcomeIsDistinguishableFromEveryOther() {
        // The property the old code failed: four situations, four
        // messages. A regression that collapses any two back into one
        // fails here even if each individual case still "works".
        val messages = listOf(
            ChannelScopeJoin.decide("", null, known),
            ChannelScopeJoin.decide("Bay Area", null, known),
            ChannelScopeJoin.decide("bayarea", null, known),
            ChannelScopeJoin.decide("socal", null, known),
            ChannelScopeJoin.decide("bayarea", "bayarea", known),
            ChannelScopeJoin.decide("bayarea", "grandrapids", known),
        ).map { ChannelScopeJoin.describe(it) }
        assertEquals(messages.size, messages.distinct().size, messages.toString())
    }

    @Test
    fun anOverlongRegionNameIsUnusableNotSilentlyTruncated() {
        // 30 characters: one over the firmware's 29-byte bound. Cutting
        // it to fit would produce a DIFFERENT flood scope — the hash is
        // over the exact bytes — so the honest answer is that we cannot
        // use it.
        val outcome = ChannelScopeJoin.decide("b".repeat(30), null, known)
        assertTrue(outcome is ChannelScopeJoin.Outcome.Unusable, "was $outcome")
    }
}
