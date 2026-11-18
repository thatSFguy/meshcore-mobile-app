package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.CliFormFields
import io.github.thatsfguy.meshcore.protocol.CliIds
import io.github.thatsfguy.meshcore.protocol.NodeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSettingsTest {

    private fun section(id: String, role: NodeRole = NodeRole.Repeater) =
        RemoteSettingsForm.sections(role).single { it.id == id }

    @Test
    fun theRadioReplySplitsIntoFieldsAtTheirShortHonestForm() {
        val f = RemoteSettingsForm()
        // float32 printed at full precision, as the firmware does.
        assertTrue(f.absorb(CliIds.RADIO, "> 910.5250244,62.5,7,5"))
        assertEquals("910.525", f.value(CliFormFields.RADIO_FREQ))
        assertEquals("62.5", f.value(CliFormFields.RADIO_BW))
        assertEquals("7", f.value(CliFormFields.RADIO_SF))
        assertFalse(f.isDirty(section(RemoteSettingsForm.RADIO)))
    }

    @Test
    fun silenceOrNoiseReadsNothing() {
        val f = RemoteSettingsForm()
        assertFalse(f.absorb(CliIds.NAME, null))
        assertFalse(f.absorb(CliIds.RADIO, "> not,a,radio"))
        assertEquals("", f.value(CliIds.NAME))
    }

    @Test
    fun onlyEditedValidFieldsAreSent() {
        val f = RemoteSettingsForm()
        f.absorb(CliIds.RADIO, "> 910.525,62.5,7,5")
        assertTrue(f.saves(section(RemoteSettingsForm.RADIO)).isEmpty, "nothing edited")
        f.edit(CliFormFields.RADIO_SF, "13")
        assertTrue(f.saves(section(RemoteSettingsForm.RADIO)).isEmpty, "SF 13 is not a LoRa setting")
        f.edit(CliFormFields.RADIO_SF, "8")
        val s = f.saves(section(RemoteSettingsForm.RADIO))
        assertEquals(listOf("set radio 910.525,62.5,8,5"), s.commands)
        assertEquals("The radio settings you saved", s.rebootNote)
    }

    @Test
    fun ownerTextKeepsItsLineBreaksBothWays() {
        // The defect lifted out of Android: it flattened '|' to spaces on
        // read and sent the flattened text back, destroying the layout.
        val f = RemoteSettingsForm()
        f.absorb(CliIds.OWNER_INFO, "> Rob|Grand Rapids")
        assertEquals("Rob\nGrand Rapids", f.value(CliIds.OWNER_INFO))
        f.edit(CliIds.OWNER_INFO, "Rob\nGrand Rapids\nMI")
        assertEquals(listOf("set owner.info Rob|Grand Rapids|MI"), f.saves(section(RemoteSettingsForm.BASIC)).commands)
    }

    @Test
    fun forwardingDelaysMustBeIntegers() {
        val f = RemoteSettingsForm()
        f.edit(CliIds.RXDELAY, "5; reboot")
        f.edit(CliIds.FLOOD_MAX, "8")
        assertEquals(listOf("set flood.max 8"), f.saves(section(RemoteSettingsForm.FORWARDING)).commands)
    }

    @Test
    fun passwordsUseTheirOwnCommandsAndAreForgottenAfterSaving() {
        val f = RemoteSettingsForm()
        val sec = section(RemoteSettingsForm.SECURITY)
        f.edit(CliFormFields.PASSWORD_NEW, "hunter22")
        f.edit(CliFormFields.GUEST_PASSWORD_NEW, "guest1")
        assertEquals(listOf("password hunter22", "set guest.password guest1"), f.saves(sec).commands)
        f.saved(sec)
        assertEquals("", f.value(CliFormFields.PASSWORD_NEW))
        assertTrue(f.saves(sec).isEmpty)
        // A space would split the command; such a password is not sent.
        f.edit(CliFormFields.PASSWORD_NEW, "two words")
        assertTrue(f.saves(sec).isEmpty)
    }

    @Test
    fun switchesUseTheWordTheFirmwareTakes() {
        val f = RemoteSettingsForm()
        assertEquals("set multi.acks 1", f.switchCommand(CliIds.MULTI_ACKS, true))
        assertEquals("set repeat off", f.switchCommand(CliIds.REPEAT, false))
        assertEquals(false, f.isOn(CliIds.REPEAT))
        assertNull(f.isOn(CliIds.RADIO_RXGAIN))
    }

    @Test
    fun dutyCycleReadsAsAWholePercent() {
        val f = RemoteSettingsForm()
        f.absorb(CliIds.DUTYCYCLE, "> 50.0%")
        assertEquals("50.0", f.value(CliIds.DUTYCYCLE))
        f.edit(CliIds.DUTYCYCLE, "101")
        assertTrue(f.saves(section(RemoteSettingsForm.ADVANCED)).isEmpty)
        f.edit(CliIds.DUTYCYCLE, "25")
        assertEquals(listOf("set dutycycle 25"), f.saves(section(RemoteSettingsForm.ADVANCED)).commands)
    }

    @Test
    fun theSectionsFollowTheRole() {
        val companion = RemoteSettingsForm.sections(NodeRole.Companion).map { it.id }
        assertFalse(RemoteSettingsForm.FORWARDING in companion)
        assertEquals(1, section(RemoteSettingsForm.SECURITY, NodeRole.Room).switches.size)
        assertTrue(section(RemoteSettingsForm.SECURITY).switches.isEmpty())
        assertFalse("get acl" in RemoteSettingsForm.MAINTENANCE_READS)
    }

    @Test
    fun loopAndHashModesAreReadAndBounded() {
        val f = RemoteSettingsForm()
        f.absorb(CliIds.LOOP_DETECT, "> Moderate")
        assertEquals(2, f.loopMode())
        assertNull(f.loopCommand(9))
        f.absorb(CliIds.PATH_HASH_MODE, "> 1")
        assertEquals(1, f.pathHashMode())
        assertNull(f.pathHashCommand(7))
    }
}
