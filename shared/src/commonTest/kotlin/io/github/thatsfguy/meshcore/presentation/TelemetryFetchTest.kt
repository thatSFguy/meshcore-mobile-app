package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which nodes get route repair when a telemetry request goes quiet, and
 * what each outcome says.
 *
 * The split follows the firmware: repeater, room server and sensor all
 * decrypt a request only for a sender in their client table
 * (`src/Mesh.cpp:147-152`), which a flooded login re-routes. A companion
 * grants telemetry per contact instead and has nothing to sign in to.
 */
class TelemetryFetchTest {

    @Test
    fun infrastructureIsRepairedOnSilence() {
        assertTrue(TelemetryFetch.usesClientTable(Codes.ADV_TYPE_REPEATER))
        assertTrue(TelemetryFetch.usesClientTable(Codes.ADV_TYPE_ROOM))
        assertTrue(TelemetryFetch.usesClientTable(Codes.ADV_TYPE_SENSOR))
    }

    @Test
    fun aCompanionIsAskedOnce() {
        assertFalse(TelemetryFetch.usesClientTable(Codes.ADV_TYPE_CHAT))
    }

    @Test
    fun unknownTypesGetNoLoginBasedRepair() {
        // Repair ends in a login, and a login to a node of unknown kind
        // would put a password on the air for nothing.
        assertFalse(TelemetryFetch.usesClientTable(0))
        assertFalse(TelemetryFetch.usesClientTable(5))
        assertFalse(TelemetryFetch.usesClientTable(-1))
        assertFalse(TelemetryFetch.usesClientTable(255))
    }

    @Test
    fun silenceAfterRepairPointsAtReachAndTheClientTable() {
        val m = TelemetryFetch.NoAnswer(clientTable = true).message!!
        assertTrue("out of reach" in m, m)
        // The one fix the user can make: be known to the node.
        assertTrue("sign in" in m, m)
        // Not the companion's per-contact permission story.
        assertFalse("permission" in m, m)
    }

    @Test
    fun aCompanionsSilenceIsAboutItsPermissions() {
        val m = TelemetryFetch.NoAnswer(clientTable = false).message!!
        assertTrue("permission" in m, m)
        assertNotEquals(TelemetryFetch.NoAnswer(clientTable = true).message, m)
    }

    @Test
    fun readingsCarryNoMessage() {
        assertNull(TelemetryFetch.Readings(emptyList()).message)
    }
}
