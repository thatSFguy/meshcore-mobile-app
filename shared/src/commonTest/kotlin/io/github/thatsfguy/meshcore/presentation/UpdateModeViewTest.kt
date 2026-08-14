package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the firmware panel is allowed to claim about a node.
 *
 * Reported from the field: a repeater was reflashed over USB, put back
 * into service, and the app went on insisting it was in update mode —
 * and hid `Send start ota`, the one control that would have helped.
 *
 * The cause was a stored address being read as a state. Nothing ever
 * cleared it, so "has an update-mode address" meant "is in update mode"
 * for ever. These tests pin the separation.
 */
class UpdateModeViewTest {

    private val mac = "FF:5C:EF:28:2A:92"

    @Test
    fun `a known address alone is never a claim that the node is in update mode`() {
        // The defect, in one assertion. A BLE address is hardware: it
        // survives a reboot, an update, and a reflash over USB. Reading
        // the state off it meant a node that entered update mode once
        // was described as being in it for ever.
        val v = UpdateModeView.of(flaggedInUpdateMode = false, knownAddress = mac)
        assertFalse(v.inUpdateMode, "a stored address was read as a live state")
    }

    @Test
    fun `the address is kept and used whatever the state`() {
        // Forgetting it would be the other wrong fix: it is the app's
        // only record of this node's BLE address, and without it a flash
        // picks between everything nearby advertising for an update
        // instead of matching this node exactly.
        for (flagged in listOf(true, false)) {
            val v = UpdateModeView.of(flaggedInUpdateMode = flagged, knownAddress = mac)
            assertEquals(mac, v.flashAddress, "the address was dropped when flagged=$flagged")
        }
    }

    @Test
    fun `only the recorded flag asserts the state`() {
        val v = UpdateModeView.of(flaggedInUpdateMode = true, knownAddress = mac)
        assertTrue(v.inUpdateMode)
    }

    @Test
    fun `the flag stands on its own without an address`() {
        // A node put into update mode by its buttons, or one whose reply
        // was never caught. The state is still known; only the address
        // is not.
        val v = UpdateModeView.of(flaggedInUpdateMode = true, knownAddress = null)
        assertTrue(v.inUpdateMode)
        assertNull(v.flashAddress)
    }

    @Test
    fun `knowing nothing claims nothing`() {
        val v = UpdateModeView.of(flaggedInUpdateMode = false, knownAddress = null)
        assertFalse(v.inUpdateMode)
        assertNull(v.flashAddress)
    }
}
