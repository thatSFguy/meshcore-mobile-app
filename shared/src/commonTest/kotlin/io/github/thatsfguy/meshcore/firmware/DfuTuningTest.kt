package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Transfer settings that the MeshCore FAQ names per board.
 *
 * §7.1 step 7 of the FAQ describes the manual nRF Device Firmware Update
 * procedure and is the only published statement of what these should be:
 *
 * > Enable `Packet receipt notifications`, and change `Number of Packets`
 * > to 10 for RAK, 8 for T114. 8 also works for RAK.
 *
 * That is a real setting a person has to change by hand in the nRF app,
 * and getting it wrong is one of the documented causes of a failed
 * flash. Doing it here rather than asking the operator to is most of the
 * point of having our own updater.
 */
class DfuTuningTest {

    @Test
    fun `receipt notifications are never switched off`() {
        // Zero disables flow control, which is how stock bootloaders are
        // made to fail: the phone writes faster than `hci_mem_pool` can
        // flush to flash. Every board, known or not, gets a positive
        // interval.
        for (board in listOf(null, "", "Heltec T114", "RAK 4631", "Something New")) {
            assertTrue(
                DfuTuning.packetsPerNotification(board) > 0,
                "board $board got flow control switched off",
            )
        }
    }

    @Test
    fun `the t114 gets eight packets per notification`() {
        assertEquals(8, DfuTuning.packetsPerNotification("Heltec T114"))
    }

    @Test
    fun `a t114 build variant is still a t114`() {
        // `getManufacturerName()` is one string per variant and the
        // display-less build reports its own. Matching the model number
        // rather than the whole name keeps those together.
        assertEquals(8, DfuTuning.packetsPerNotification("Heltec T114 without display"))
        assertEquals(8, DfuTuning.packetsPerNotification("heltec  t114"))
    }

    @Test
    fun `rak boards get the documented ten`() {
        assertEquals(10, DfuTuning.packetsPerNotification("RAK 4631"))
        assertEquals(10, DfuTuning.packetsPerNotification("RAK WisMesh Tag"))
    }

    @Test
    fun `an unknown or missing board gets the documented default`() {
        // Not a guess: 10 is what the FAQ's procedure uses unless it
        // says otherwise, and it is the nRF app's own default.
        assertEquals(LegacyDfu.DEFAULT_PRN_INTERVAL, DfuTuning.packetsPerNotification(null))
        assertEquals(LegacyDfu.DEFAULT_PRN_INTERVAL, DfuTuning.packetsPerNotification(""))
        assertEquals(
            LegacyDfu.DEFAULT_PRN_INTERVAL,
            DfuTuning.packetsPerNotification("ProMicro DIY"),
        )
        assertEquals(10, LegacyDfu.DEFAULT_PRN_INTERVAL)
    }

    @Test
    fun `the otafix bootloader is named for the boards that have one`() {
        // FAQ §7.3 lists the boards `Adafruit_nRF52_Bootloader_OTAFIX`
        // supports. It is the single biggest reduction in the risk of a
        // half-flashed node, and it can only be put on over USB — which
        // means the advice is worth nothing unless it arrives BEFORE the
        // node goes up somewhere awkward.
        assertTrue(DfuTuning.hasOtafixBootloader("Heltec T114"))
        assertTrue(DfuTuning.hasOtafixBootloader("ProMicro DIY"))
        assertTrue(DfuTuning.hasOtafixBootloader("RAK 4631"))
        assertTrue(DfuTuning.hasOtafixBootloader("Seeed Xiao-nRF52"))
        // Not on the published list, so we do not claim it either way.
        assertTrue(!DfuTuning.hasOtafixBootloader("Nano G2 Ultra"))
        assertTrue(!DfuTuning.hasOtafixBootloader(null))
    }
}
