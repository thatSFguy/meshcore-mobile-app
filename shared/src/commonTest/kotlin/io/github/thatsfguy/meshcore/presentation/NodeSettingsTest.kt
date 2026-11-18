package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.model.SelfInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeSettingsTest {

    @Test
    fun theAuthorsRealMeshParsesToTheWireUnits() {
        // Blue Base, read live 2026-11-18: 910.525 MHz, 62.5 kHz, SF7, CR5.
        assertEquals(
            NodeSettings.RadioParams(910_525L, 62_500L, 7, 5),
            NodeSettings.parseRadioParams("910.525", "62.5", "7", "5"),
        )
    }

    @Test
    fun everyOutOfRangeRadioFieldIsRefused() {
        val ok = arrayOf("910.525", "62.5", "7", "5")
        fun with(i: Int, v: String) = ok.copyOf().also { it[i] = v }
        for (bad in listOf(
            with(0, "299.999"), with(0, "2500.001"), with(0, "910,525"), with(0, ""),
            with(1, "6.999"), with(1, "500.001"), with(1, "abc"),
            with(2, "4"), with(2, "13"), with(2, "7.5"),
            with(3, "4"), with(3, "9"), with(3, ""),
        )) {
            assertNull(NodeSettings.parseRadioParams(bad[0], bad[1], bad[2], bad[3]), bad.joinToString())
        }
        // The edges themselves are allowed.
        assertEquals(300_000L, NodeSettings.parseRadioParams("300", "7", "5", "8")?.freqKhz)
        assertEquals(500_000L, NodeSettings.parseRadioParams("2500", "500", "12", "5")?.bwHz)
    }

    @Test
    fun txPowerMustBeWithinWhatTheRadioReports() {
        assertEquals(20, NodeSettings.parseTxPower(" 20 ", 22))
        assertNull(NodeSettings.parseTxPower("23", 22))
        assertNull(NodeSettings.parseTxPower("0", 22))
        assertNull(NodeSettings.parseTxPower("-1", 22))
        assertNull(NodeSettings.parseTxPower("twenty", 22))
    }

    @Test
    fun aPositionMustBeOnEarth() {
        assertEquals(NodeSettings.Position(42.96, -85.67), NodeSettings.parsePosition("42.96", "-85.67"))
        assertNull(NodeSettings.parsePosition("90.1", "0"))
        assertNull(NodeSettings.parsePosition("0", "-180.5"))
        assertNull(NodeSettings.parsePosition("NaN", "0"))
        assertNull(NodeSettings.parsePosition("", "0"))
    }

    @Test
    fun driftReadsInTheLargestSensibleUnitAndSaysWhoIsAhead() {
        assertEquals("none — clocks agree", NodeSettings.formatDrift(0))
        assertEquals("45 s (radio ahead)", NodeSettings.formatDrift(45))
        assertEquals("2 min 5 s (radio behind)", NodeSettings.formatDrift(-125))
        assertEquals("3 h 1 min (radio ahead)", NodeSettings.formatDrift(3 * 3600 + 61))
        // A radio that lost its clock on power-cycle reads as 1970.
        assertEquals("20410 days (radio behind)", NodeSettings.formatDrift(-1_763_481_600L))
        assertTrue(NodeSettings.isDriftLarge(31) && NodeSettings.isDriftLarge(-31))
        assertTrue(!NodeSettings.isDriftLarge(30))
    }

    @Test
    fun aCustomVariableKeyMayNotContainTheSeparator() {
        assertEquals("gps:1", NodeSettings.customVarAssignment(" gps ", " 1 "))
        // The firmware splits on the first ':'; "a:b" = "c" would set a.
        assertNull(NodeSettings.customVarAssignment("a:b", "c"))
        assertNull(NodeSettings.customVarAssignment("  ", "1"))
        assertNull(NodeSettings.customVarAssignment("gps", "1\u00002"))
        // A ':' in the VALUE is fine — only the first one splits.
        assertEquals("url:http://x", NodeSettings.customVarAssignment("url", "http://x"))
    }

    @Test
    fun theOtherParamsRoundTripAndOneChangeKeepsTheRest() {
        // base=Flags(1), location=All(2), environment=Deny(0)
        val info = selfInfo(telemetryModes = (0 shl 4) or (2 shl 2) or 1, advertLocPolicy = 1, multiAcks = 0)
        val p = OtherParams.of(info)
        assertEquals(OtherParams(1, 2, 0, 1, 0), p)
        assertEquals(info.telemetryModes, p.telemetryFlags)
        val changed = p.withEnvironment(2)
        assertEquals((2 shl 4) or (2 shl 2) or 1, changed.telemetryFlags)
        assertEquals(1, changed.advertLocationPolicy, "a telemetry change must not drop the location policy")
        assertEquals(OtherParams(1, 2, 0, 0, 1), p.withIncludesLocation(false).withMultiAcks(true))
        // A reserved mode value never escapes its two bits.
        assertEquals(2, p.withBase(7).base)
    }

    @Test
    fun aFlagToggleTouchesOnlyItsBit() {
        assertEquals(0x0F, NodeSettings.toggleFlag(0x0B, 0x04, true))
        assertEquals(0x0B, NodeSettings.toggleFlag(0x0F, 0x04, false))
        assertEquals(0x0B, NodeSettings.toggleFlag(0x0B, 0x04, false))
    }

    @Test
    fun thePresetLineNamesWhatMatches() {
        assertEquals("Current settings match no known preset.", NodeSettings.presetMatchLine(433_001, 1, 7, 5))
        assertTrue(NodeSettings.shareCaption("bayarea").contains("#bayarea"))
        assertTrue(NodeSettings.shareCaption(null).contains("no region"))
    }

    private fun selfInfo(telemetryModes: Int, advertLocPolicy: Int, multiAcks: Int) = SelfInfo(
        advType = 1, txPowerDbm = 20, maxTxPowerDbm = 22, publicKey = ByteArray(32),
        latitude = 0.0, longitude = 0.0, multiAcks = multiAcks, advertLocPolicy = advertLocPolicy,
        telemetryModes = telemetryModes, manualAddContacts = false,
        freqKhz = 910_525, bwHz = 62_500, sf = 7, cr = 5, name = "x",
    )
}
