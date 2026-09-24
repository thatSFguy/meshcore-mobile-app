package io.github.thatsfguy.meshcore.android.ui

import io.github.thatsfguy.meshcore.firmware.DfuProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which firmware-screen states are cleared when the screen closes.
 *
 * On the test RAK (2026-09-24) the next update opened on the previous
 * one's "Update complete", and taps meant for the version list landed on
 * it. Clearing too much is the opposite failure: an update still running
 * must survive the operator looking at another screen.
 */
class FirmwareUiTest {

    @Test
    fun `a finished or failed update is over`() {
        assertTrue(FirmwareUi.Finished("v1.17.1").isOver)
        assertTrue(FirmwareUi.Failed("it failed", null).isOver)
    }

    @Test
    fun `an update in progress is not`() {
        assertFalse(FirmwareUi.Running(DfuProgress.Transferring(1024, 4096)).isOver)
        assertFalse(FirmwareUi.Running(DfuProgress.FindingNode).isOver)
        assertFalse(FirmwareUi.Checking.isOver)
        assertFalse(FirmwareUi.Idle().isOver)
    }
}
