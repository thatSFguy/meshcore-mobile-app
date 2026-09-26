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
        assertTrue(s.contains("vm.confirmRadioConfig(config, applyRegion)"))
        // A refused region is shown, not silently dropped.
        assertTrue(s.contains("config.rejectedRegion"))
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
}
