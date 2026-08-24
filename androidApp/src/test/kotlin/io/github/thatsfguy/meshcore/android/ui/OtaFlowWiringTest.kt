package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update-mode flow, checked where it is wired rather than where it
 * is decided.
 *
 * The decisions themselves are pure and tested properly in
 * `shared` (`OtaEntryTest`, `DfuTuningTest`). What cannot be reached
 * from a local unit test is the ViewModel and the Compose panel that
 * call them — and every defect in this area so far has been in the
 * wiring, not the rule: a flag set from the wrong evidence, a board name
 * dropped on the way to the scanner, a reply consumed twice.
 *
 * So these are source pins. They are lint, not proof, and they say so;
 * their job is to fail loudly if a later edit quietly reintroduces the
 * shape of a bug that has already cost a node.
 */
class OtaFlowWiringTest {

    private fun read(path: String) = File(path).readText()

    private val viewModel =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/MeshCoreViewModel.kt")
    private val panel = read(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/" +
            "RepeaterFirmwarePanel.kt",
    )
    private val controller =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/FirmwareUpdateController.kt")

    @Test
    fun `a node that answers a login is taken out of update mode`() {
        // A node in its bootloader has no LoRa stack at all — the Nordic
        // bootloader is a BLE-only image, and MeshCore's own firmware is
        // not running to receive anything. So ANY answer to a login,
        // including "wrong password", is proof the application is up and
        // the node is not in update mode.
        //
        // This is the cheapest evidence the app ever gets, and it is
        // exactly the case that went wrong: a node reflashed over USB
        // and signed into was still being described as advertising for
        // an update.
        // The whole login path, not just the function that starts it:
        // the round trip lives in `performLogin` so a neighbour fetch
        // can wait for a session without a second copy of it, and this
        // rule has to hold wherever the reply is actually read.
        val login = viewModel.substringAfter("fun repeaterLogin(")
            .substringBefore("     * The saved password for a node.")
        assertTrue(
            "a login that was answered must clear the update-mode flag",
            login.contains("outcome.answered") && login.contains("setUpdateMode(keyHex, false)"),
        )
    }

    @Test
    fun `the panel proves the node answers before it sends start ota`() {
        // `ver` first, and only on its answer is `start ota` sent. The
        // version is worth having for its own sake — once the node is in
        // its bootloader nothing can ask it again — but the reason it
        // goes first is that it is a round trip whose failure costs
        // nothing.
        assertTrue(
            "the panel does not run the proving sequence",
            panel.contains("OtaEntry.ProvingTheNodeAnswers"),
        )
        assertTrue("the panel never sends ver", panel.contains("\"ver\""))
        assertTrue(
            "start ota is not gated on the version answer",
            panel.contains("is OtaEntry.AwaitingUpdateMode") && panel.contains("\"start ota\""),
        )
    }

    @Test
    fun `nothing but the node's own address sets the flag`() {
        // Both previous versions of this bug set update mode from
        // something that was not the node reporting an address: first
        // the presence of a stored MAC, then the presence of a persisted
        // console row. The only two places allowed to set it now are the
        // confirmed sequence and the watermarked passive path, and both
        // carry an address that came out of an `OK - mac: …` reply.
        val sets = Regex("""setUpdateMode\([^)]*true""").findAll(panel).count()
        assertTrue("the panel no longer sets update mode at all", sets > 0)
        assertTrue(
            "update mode is set somewhere that is not holding an address",
            panel.contains("OtaEntry.Confirmed") ||
                panel.contains("OtaEvidence.freshAdvertisingAddress"),
        )
        assertFalse(
            "the flag is being derived from the stored address again",
            panel.contains("flaggedInUpdateMode = storedContact?.otaAddress != null"),
        )
    }

    @Test
    fun `the receipt interval is taken from the board`() {
        // FAQ §7.1: 8 packets for a T114, 10 elsewhere. A constant here
        // is the setting the FAQ tells a person to change by hand,
        // silently left wrong.
        assertTrue(
            "the transfer uses a fixed receipt interval instead of the board's",
            controller.contains("DfuTuning.packetsPerNotification"),
        )
    }

    @Test
    fun `the board name reaches a node that is in update mode`() {
        // It was passed as null on this one path, which is the path
        // where it matters most: the node is off the mesh and cannot be
        // asked, the scanner has no name hint to match on, and the
        // firmware picker offers all thirty-five builds instead of the
        // one.
        val target = controller
            .substringAfter("FirmwareTargetKind.NodeInUpdateMode")
            .substringBefore("}")
        assertFalse(
            "AdvertisingForUpdate is still built with boardName = null",
            target.contains("boardName = null"),
        )
    }
}
