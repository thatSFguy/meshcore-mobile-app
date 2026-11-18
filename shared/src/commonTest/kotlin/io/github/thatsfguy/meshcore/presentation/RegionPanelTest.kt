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
}
