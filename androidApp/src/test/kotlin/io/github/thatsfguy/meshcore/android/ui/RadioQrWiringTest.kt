package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a scanned mesh-settings code's region reaches the radio, and how
 * this phone's settings become a code. The rules are tested in shared
 * (ShareUriRadioTest, RadioShareTest); these pin that the screens use
 * them.
 */
class RadioQrWiringTest {

    private fun screen(name: String) =
        File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/$name").readText()

    @Test
    fun `the scanned region is applied only through its own checkbox`() {
        val s = screen("ScanConfirmations.kt")
        // Ticked when the code named a valid region, and passed through.
        assertTrue(s.contains("mutableStateOf(config.region != null)"))
        // The level picked, and only while the box is ticked; the view
        // model re-checks it against the code (regionToApply).
        assertTrue(s.contains("vm.confirmRadioConfig(config, picked.takeIf { applyRegion })"))
        // The code's default is preselected, else the most local level.
        assertTrue(s.contains("mutableStateOf(config.region ?: choices.lastOrNull())"))
        // A refused region or tree is shown, not silently dropped.
        assertTrue(s.contains("config.rejectedRegion?"))
        assertTrue(s.contains("config.rejectedRegionTree?"))
    }

    @Test
    fun `the phone shares its radio's values through RadioShare`() {
        val s = screen("SettingsSections.kt")
        assertTrue(s.contains("RadioShare.uriFor("))
        // From the radio, never the edit fields: an unsaved edit must not
        // be handed out.
        assertTrue(s.contains("info.freqKhz, info.bwHz, info.sf, info.cr"))
    }

    @Test
    fun `the phone's region is persisted and restored`() {
        val svc = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/service/MeshCoreService.kt").readText()
        assertTrue(svc.contains("engine.restoreFloodScope(prefs.floodScope)"))
        assertTrue(svc.contains("prefs.floodScope = it"))
    }

    @Test
    fun `a code scanned for a repeater writes its regions only when ticked`() {
        val s = screen("RemoteSettingsForm.kt")
        // Unticked by default, as on the web generator's USB writer.
        assertTrue(s.contains("var writeRegions by remember(config) { mutableStateOf(false) }"))
        // The commands come from the tested plan, made against what the
        // node holds now, and a refusal stops them.
        assertTrue(s.contains("ScannedSettingsPlan.regions(tree, config.region, current, currentDefault)"))
        assertTrue(s.contains("changes?.commands?.takeIf { writeRegions }"))
        assertTrue(s.contains("RegionAdmin.treeStepRefused(reply)"))
        // Radio values the node already runs are not re-sent, nor a reboot asked for.
        assertTrue(s.contains("if (!state.radioSame) pendingReboot"))
        // The default is stated, not just marked.
        assertTrue(s.contains("config.regionHeadline()"))
    }
}
