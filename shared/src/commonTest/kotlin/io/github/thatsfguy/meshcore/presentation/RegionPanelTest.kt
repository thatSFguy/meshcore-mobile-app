package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Regions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionPanelTest {

    @Test
    fun anInjectedNameIsNeverOfferedAsACommand() {
        // The builders throw on this; the panel must answer null, never crash Swift.
        assertNull(RegionPanel.step(RegionAdmin.Action.Remove, "mi; erase"))
        assertNull(RegionPanel.add("Bay Area", Regions.GLOBAL_SELECTOR))
        assertNull(RegionPanel.add("", Regions.GLOBAL_SELECTOR))
    }

    @Test
    fun destructiveStepsAreConfirmedAndOthersAreNot() {
        val deny = assertNotNull(RegionPanel.step(RegionAdmin.Action.DenyFlood, "mi"))
        assertEquals("Deny flood for mi?", deny.confirmTitle)
        assertEquals(Regions.denyFlood("mi"), deny.command)
        val home = assertNotNull(RegionPanel.step(RegionAdmin.Action.SetHome, "mi"))
        assertNull(home.confirmTitle)
        assertNull(RegionPanel.step(RegionAdmin.Action.AddChild, "mi"))
    }

    @Test
    fun aRefusalIsSilenceErrOrQuestionMarks() {
        assertNull(RegionPanel.refusal("region save", "OK"))
        assertTrue(RegionPanel.refusal("region save", null)!!.startsWith("No reply"))
        assertTrue(RegionPanel.refusal("region save", "Err - not empty")!!.contains("Err - not empty"))
        assertTrue(RegionPanel.refusal("region def mi", "??: region")!!.endsWith("(it needs firmware 1.16 or newer)"))
    }

    @Test
    fun theDefaultLineSaysWhatTheNodeAnswered() {
        assertEquals("Not read yet.", RegionPanel.defaultLine(false, null))
        assertTrue(RegionPanel.defaultLine(true, null).startsWith("The node's answer wasn't recognised"))
    }

    @Test
    fun rowTagsNameDefaultHomeAndFlood() {
        val e = Regions.RegionEntry("mi", Regions.GLOBAL_SELECTOR, floodAllowed = false, depth = 1, home = true)
        assertEquals("default · home · flood denied", RegionPanel.rowTags(e, "mi"))
        assertEquals("Top level", RegionPanel.parentLabel(Regions.GLOBAL_SELECTOR))
    }

    @Test
    fun untaggedStepsAreBuiltSafelyAndRefusalIsConfirmed() {
        // Nearby on firmware without the hop limit: the builder throws; the panel says null.
        assertNull(RegionPanel.untaggedStep(RegionAdmin.Untagged.Nearby, 3, true, null, limitSupported = false))
        assertNull(RegionPanel.untaggedStep(RegionAdmin.Untagged.Nearby, 99, true, 64, limitSupported = true))
        // Already relaying all, no hop limit to fix: nothing to send.
        assertNull(RegionPanel.untaggedStep(RegionAdmin.Untagged.RelayAll, 3, true, 64, limitSupported = true))
        val refuse = assertNotNull(RegionPanel.untaggedStep(RegionAdmin.Untagged.Refuse, 3, true, 64, true))
        assertEquals("Refuse untagged traffic?", refuse.single().confirmTitle)
        val nearby = assertNotNull(RegionPanel.untaggedStep(RegionAdmin.Untagged.Nearby, 3, false, 64, true))
        assertEquals(listOf(Regions.allowFlood(Regions.GLOBAL_SELECTOR), "set flood.max.unscoped 3"), nearby.map { it.command })
        assertNull(nearby[0].confirmTitle)
        assertEquals(2, RegionPanel.untaggedChoices(false).size)
        assertEquals(3, RegionPanel.untaggedChoices(true).size)
    }
}
