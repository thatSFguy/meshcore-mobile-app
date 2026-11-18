package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteScanTest {

    private val link = RadioShare.uriFor(910_525, 62_500, 7, 5, 2, null, "GR Mesh")!!

    @Test
    fun onlySettingsCodesDecode() {
        assertNotNull(RemoteScan.decode(link))
        assertNull(RemoteScan.decode("meshcore://not-a-code"))
        assertNull(RemoteScan.decode(""))
    }

    @Test
    fun aNodeAlreadyOnTheseSettingsIsSentNothing() {
        val s = RemoteScan.decode(link)!!
        s.readCommands().forEach { cmd ->
            s.take(cmd, if (cmd == "get radio") "> 910.5250244,62.5,7,5" else "> 1")
        }
        assertTrue(s.radioSame, "width 2 bytes = path.hash.mode 1")
        assertTrue(s.nothingToDo(writeRegions = true))
        assertFalse(s.needsReboot)
    }

    @Test
    fun aDifferentNodeGetsRadioThenWidthAndNeedsAReboot() {
        val s = RemoteScan.decode(link)!!
        s.take("get radio", "> 869.525,250,11,5")
        s.take("get path.hash.mode", "> 0")
        assertEquals(listOf("set radio 910.525,62.5,7,5", "set path.hash.mode 1"), s.commands(writeRegions = false))
        assertTrue(s.needsReboot)
        assertTrue(s.commands(false).none { it.startsWith("set tx") }, "TX power is never taken from a code")
    }

    @Test
    fun silenceIsNotSameness() {
        val s = RemoteScan.decode(link)!!
        assertFalse(s.radioSame, "an unread node must not be assumed to match")
    }

    @Test
    fun aRefusalNamesTheCommandAndFirmware() {
        val s = RemoteScan.decode(link)!!
        assertNull(s.refusal("Harbor", "set radio 1,2,3,4", "> OK"))
        assertEquals("Harbor didn't take `set radio 1,2,3,4`: no reply", s.refusal("Harbor", "set radio 1,2,3,4", null))
        assertTrue(s.refusal("Harbor", "region def mi", "??: region")!!.endsWith("(it needs firmware 1.16 or newer)"))
    }

    @Test
    fun aCodeWithoutRegionsSaysNothingAboutThem() {
        val s = RemoteScan.decode(link)!!
        assertFalse(s.needsRegions)
        assertNull(s.regionLine("Harbor"))
        assertEquals(listOf("get radio", "get path.hash.mode"), s.readCommands())
    }
}
