package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.engine.LoginOutcome
import io.github.thatsfguy.meshcore.protocol.Codes
import io.github.thatsfguy.meshcore.protocol.RepeaterStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeaterReadoutTest {

    private fun ok(isAdmin: Boolean, perms: Int = 0, acl: Int? = null) =
        LoginOutcome(accepted = true, isAdmin = isAdmin, answered = true, permissions = perms, aclPermissions = acl)

    @Test
    fun anAdminLoginOnARepeaterIsAdmin() {
        assertEquals(AdminSession.Admin, RepeaterReadout.session(Codes.ADV_TYPE_REPEATER, ok(true)))
    }

    @Test
    fun aNonAdminLoginOnARepeaterIsGuest() {
        assertEquals(AdminSession.Guest, RepeaterReadout.session(Codes.ADV_TYPE_REPEATER, ok(false)))
    }

    @Test
    fun aRoomReadsItsAclForMemberVersusGuest() {
        assertEquals(AdminSession.Member, RepeaterReadout.session(Codes.ADV_TYPE_ROOM, ok(false, acl = 2)))
        assertEquals(AdminSession.Guest, RepeaterReadout.session(Codes.ADV_TYPE_ROOM, ok(false, acl = 0)))
    }

    @Test
    fun aRefusalOrSilenceGrantsNothingAndSaysWhich() {
        assertEquals(AdminSession.None, RepeaterReadout.session(Codes.ADV_TYPE_REPEATER, LoginOutcome.Rejected))
        assertEquals(AdminSession.None, RepeaterReadout.session(Codes.ADV_TYPE_REPEATER, LoginOutcome.NoAnswer))
        assertEquals("The node refused that password.", RepeaterReadout.failure(LoginOutcome.Rejected))
        // Silence must not claim the password was wrong.
        assertTrue("may have refused" in RepeaterReadout.failure(LoginOutcome.NoAnswer)!!)
        assertNull(RepeaterReadout.failure(ok(true)))
    }

    @Test
    fun statusReadsInTheUnitsAndWordsAndroidUses() {
        val s = RepeaterStatus(
            senderPrefixHex = "aabbccddeeff", batteryMillivolts = 4100, queueLength = 2,
            noiseFloor = -112, lastRssi = -95, packetsReceived = 1200, packetsSent = 800,
            txAirSeconds = 360, uptimeSeconds = 36_000, floodTx = 10, directTx = 20, floodRx = 30,
            directRx = 40, errorEvents = 1, lastSnr = 7.25, directDuplicates = 3, floodDuplicates = 4,
            rxAirSeconds = 540,
        )
        val rows = RepeaterReadout.status(s).flatMap { it.rows }.associate { it.label to it.value }
        assertEquals("-95 dBm / 7.3 dB", rows["Last RSSI / SNR"]) // fixed() rounds half up
        assertEquals("-112 dBm", rows["Noise floor"])
        assertEquals("2.5 %", rows["Channel utilisation"]) // (360+540)/36000
        assertEquals("1200 / 800", rows["Received / sent"])
        assertTrue(rows.getValue("Battery").startsWith("4.10 V"))
    }
}

class RepeaterConsoleRedactionTest {
    private fun shown(s: String) = io.github.thatsfguy.meshcore.messaging.RepeaterAdmin.displayed(s)

    @Test
    fun aPrivateKeyTypedIntoTheConsoleIsNotShown() {
        val key = "ab".repeat(64)
        assertEquals("set prv.key [REDACTED]", shown("set prv.key $key"))
        assertEquals("> [HEX-REDACTED]", shown("> $key"), "a key echoed back by `get prv.key`")
    }

    @Test
    fun passwordsAreNotShown() {
        assertEquals("set password [REDACTED]", shown("set password hunter2"))
        assertEquals("set guest.password [REDACTED]", shown("set guest.password letmein"))
    }

    @Test
    fun ordinaryLinesAreUntouched() {
        // Positive control: redaction that ate everything would pass the above.
        assertEquals("get radio", shown("get radio"))
        assertEquals("> 910.525,250.0,10,5", shown("> 910.525,250.0,10,5"))
    }
}
