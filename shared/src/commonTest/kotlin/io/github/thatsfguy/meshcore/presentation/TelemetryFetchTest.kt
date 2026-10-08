package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which nodes need a sign-in before telemetry, and what each outcome says.
 *
 * The gate follows the firmware: repeater, room server and sensor all
 * decrypt a request only for a sender in their client table
 * (`simple_repeater/MyMesh.cpp:663-669`; the room server and sensor have
 * the same `onPeerDataRecv` guard). A companion grants telemetry per
 * contact instead and has no sign-in.
 */
class TelemetryFetchTest {

    @Test
    fun infrastructureSignsInFirst() {
        assertTrue(TelemetryFetch.needsSignIn(Codes.ADV_TYPE_REPEATER))
        assertTrue(TelemetryFetch.needsSignIn(Codes.ADV_TYPE_ROOM))
        assertTrue(TelemetryFetch.needsSignIn(Codes.ADV_TYPE_SENSOR))
    }

    @Test
    fun aCompanionIsAskedDirectly() {
        assertFalse(TelemetryFetch.needsSignIn(Codes.ADV_TYPE_CHAT))
    }

    @Test
    fun unknownTypesAreNotSignedInto() {
        // A login to a node of unknown kind would put a password on the
        // air for nothing.
        assertFalse(TelemetryFetch.needsSignIn(0))
        assertFalse(TelemetryFetch.needsSignIn(5))
        assertFalse(TelemetryFetch.needsSignIn(-1))
        assertFalse(TelemetryFetch.needsSignIn(255))
    }

    @Test
    fun aRefusedSignInSaysTheSameAsTheNeighbourFetch() {
        for (blank in listOf(true, false)) for (answered in listOf(true, false)) {
            assertEquals(
                NeighbourFetch.SignInRefused(blank, answered).message,
                TelemetryFetch.SignInRefused(blank, answered).message,
            )
        }
    }

    @Test
    fun aRefusalNeverClaimsToBeOne() {
        // A repeater is silent when it turns a password down, so an
        // unanswered sign-in must offer both explanations.
        val m = TelemetryFetch.SignInRefused(blank = true, answered = false).message!!
        assertTrue("out of reach" in m, m)
    }

    @Test
    fun silenceAfterSigningInDoesNotBlamePermissions() {
        // Once signed in, access is settled; only reach is left.
        val signedIn = TelemetryFetch.NoAnswer(signedIn = true).message!!
        val companion = TelemetryFetch.NoAnswer(signedIn = false).message!!
        assertFalse("permission" in signedIn, signedIn)
        assertTrue("permission" in companion, companion)
        assertNotEquals(signedIn, companion)
    }

    @Test
    fun readingsCarryNoMessage() {
        assertNull(TelemetryFetch.Readings(emptyList()).message)
    }
}
