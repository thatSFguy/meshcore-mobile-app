package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Firmware GET replies are `> <value>` lines (CommonCLI.cpp) — these
 * tests pin the parsing the form-based remote-management UI relies on.
 */
class CliRepliesTest {

    @Test
    fun extractsSimpleGetValue() {
        assertEquals("910.525", CliReplies.extractGetValue("> 910.525"))
        assertEquals("MyRepeater", CliReplies.extractGetValue(">   MyRepeater  "))
    }

    @Test
    fun extractsFirstValueLineFromMultilineReply() {
        assertEquals("22", CliReplies.extractGetValue("noise\n> 22\n> 23"))
    }

    @Test
    fun missingOrEmptyValueYieldsNull() {
        assertNull(CliReplies.extractGetValue("no marker here"))
        assertNull(CliReplies.extractGetValue(">"))
        assertNull(CliReplies.extractGetValue(">   "))
        assertNull(CliReplies.extractGetValue(""))
    }

    @Test
    fun radioCsvRoundTrip() {
        val parsed = CliReplies.parseRadioCsv("910.525,250,10,5")
        assertEquals(910.525, parsed!!.freqMhz, 1e-9)
        assertEquals(250.0, parsed.bwKhz, 1e-9)
        assertEquals(10, parsed.sf)
        assertEquals(5, parsed.cr)
        // Whole numbers render without a trailing .0 (firmware format).
        assertEquals("910.525,250,10,5", parsed.toCsv())
        assertEquals(
            "915,125,7,5",
            CliReplies.RadioCsv(915.0, 125.0, 7, 5).toCsv(),
        )
    }

    @Test
    fun radioCsvToleratesSpaces() {
        val parsed = CliReplies.parseRadioCsv(" 869.525 , 250 , 11 , 8 ")
        assertEquals(11, parsed!!.sf)
        assertEquals(8, parsed.cr)
    }

    @Test
    fun malformedRadioCsvIsNull() {
        assertNull(CliReplies.parseRadioCsv(""))
        assertNull(CliReplies.parseRadioCsv("910.525,250,10"))       // too few
        assertNull(CliReplies.parseRadioCsv("abc,250,10,5"))
        assertNull(CliReplies.parseRadioCsv("910.5,250,x,5"))
    }

    @Test
    fun truthyParsing() {
        for (v in listOf("1", "on", "ON", "true", "yes", " enabled ")) {
            assertTrue(CliReplies.isTruthy(v), "'$v' should be truthy")
        }
        for (v in listOf("0", "off", "false", "no", "", "2")) {
            assertFalse(CliReplies.isTruthy(v), "'$v' should be falsy")
        }
    }
}
