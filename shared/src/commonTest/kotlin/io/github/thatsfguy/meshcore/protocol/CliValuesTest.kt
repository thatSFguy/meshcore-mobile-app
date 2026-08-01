package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Positive AND negative coverage for the CLI value coercions. The
 * negative cases matter most: a bad coercion doesn't fail locally, it
 * sends a malformed `set` to somebody's repeater.
 */
class CliValuesTest {

    // --- booleans ----------------------------------------------------

    @Test
    fun booleanWordingMatchesFirmware() {
        assertEquals("on", CliValues.onOff(true))
        assertEquals("off", CliValues.onOff(false))
        // multi.acks is the odd one out: 1/0, not on/off.
        assertEquals("1", CliValues.oneZero(true))
        assertEquals("0", CliValues.oneZero(false))
    }

    @Test
    fun truthyAcceptsEveryFormTheFirmwareReplies() {
        // Positive: all of these mean "enabled" somewhere in the CLI.
        for (v in listOf("1", "on", "ON", "On", "true", "yes", "enabled", " on ")) {
            assertEquals(true, CliReplies.isTruthy(v), "'$v' should be truthy")
        }
        // Negative: anything else must NOT read as enabled — a false
        // positive here silently flips a switch in the UI.
        for (v in listOf("0", "off", "OFF", "false", "no", "", "   ", "2", "onn", "of")) {
            assertEquals(false, CliReplies.isTruthy(v), "'$v' should be falsy")
        }
    }

    // --- duty cycle --------------------------------------------------

    @Test
    fun percentParsesTheReplyFormatFirmwareSends() {
        // The firmware replies "50.0%" but only accepts "50".
        assertEquals(50, CliValues.parsePercent("50.0%"))
        assertEquals(50, CliValues.parsePercent("50%"))
        assertEquals(50, CliValues.parsePercent(" 50.0 % ".replace(" %", "%")))
        assertEquals(100, CliValues.parsePercent("100.0%"))
        assertEquals(1, CliValues.parsePercent("1"))
    }

    @Test
    fun percentRejectsOutOfRangeAndGarbage() {
        // Out of range — the node would reject these.
        assertNull(CliValues.parsePercent("0"))
        assertNull(CliValues.parsePercent("101"))
        assertNull(CliValues.parsePercent("-5"))
        // Not a number at all.
        assertNull(CliValues.parsePercent(""))
        assertNull(CliValues.parsePercent("   "))
        assertNull(CliValues.parsePercent("%"))
        assertNull(CliValues.parsePercent("abc"))
        assertNull(CliValues.parsePercent("fifty%"))
    }

    // --- loop detect -------------------------------------------------

    @Test
    fun loopDetectMapsKnownModes() {
        assertEquals(0, CliValues.parseLoopDetect("off"))
        assertEquals(1, CliValues.parseLoopDetect("minimal"))
        assertEquals(2, CliValues.parseLoopDetect("MODERATE"))
        assertEquals(3, CliValues.parseLoopDetect(" strict "))
        assertEquals(4, CliValues.LOOP_DETECT_MODES.size)
    }

    @Test
    fun loopDetectRejectsUnknownModes() {
        // An unknown mode must not silently select index 0 ("off") —
        // that would misreport a node's loop detection as disabled.
        for (v in listOf("", "aggressive", "on", "1", "strictly")) {
            assertNull(CliValues.parseLoopDetect(v), "'$v' should not map to a mode")
        }
    }

    // --- owner.info --------------------------------------------------

    @Test
    fun ownerInfoRoundTripsNewlineEncoding() {
        assertEquals("line1\nline2", CliValues.decodeOwnerInfo("line1|line2"))
        assertEquals("line1|line2", CliValues.encodeOwnerInfo("line1\nline2"))
        // Round trip is stable.
        val text = "Rob\nGrand Rapids\nKD8XYZ"
        assertEquals(text, CliValues.decodeOwnerInfo(CliValues.encodeOwnerInfo(text)))
    }

    @Test
    fun ownerInfoEncodingStripsCarriageReturns() {
        // A raw CR/LF would truncate the CLI command on the wire.
        val encoded = CliValues.encodeOwnerInfo("a\r\nb")
        assertEquals(false, encoded.contains('\n'))
        assertEquals(false, encoded.contains('\r'))
    }

    // --- plain integers ----------------------------------------------

    @Test
    fun intParsingHonoursRange() {
        assertEquals(7, CliValues.parseInt("7"))
        assertEquals(7, CliValues.parseInt(" 7 "))
        assertEquals(-3, CliValues.parseInt("-3"))
        assertEquals(5, CliValues.parseInt("5", 1..10))
    }

    @Test
    fun intParsingRejectsGarbageAndOutOfRange() {
        assertNull(CliValues.parseInt(""))
        assertNull(CliValues.parseInt("abc"))
        assertNull(CliValues.parseInt("1.5"))
        assertNull(CliValues.parseInt("0x10"))
        assertNull(CliValues.parseInt("99999999999999999999"))  // overflows Int
        assertNull(CliValues.parseInt("11", 1..10))              // above range
        assertNull(CliValues.parseInt("0", 1..10))               // below range
    }

    // --- radio CSV (the compound one) --------------------------------

    @Test
    fun radioCsvRejectsPartialAndNonNumericInput() {
        // Positive coverage lives in CliRepliesTest; these are the
        // malformed shapes a flaky link can produce.
        assertNull(CliReplies.parseRadioCsv("910.525,250,10"))     // truncated
        assertNull(CliReplies.parseRadioCsv(",,,"))
        assertNull(CliReplies.parseRadioCsv("910.525,250,ten,5"))
        assertNull(CliReplies.parseRadioCsv("> 910.525,250,10,5"))  // marker not stripped
    }
}
