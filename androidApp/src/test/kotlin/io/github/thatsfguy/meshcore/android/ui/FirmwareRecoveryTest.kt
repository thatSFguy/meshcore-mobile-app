package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where firmware recovery lives, checked against the source.
 *
 * A node in update mode is off the mesh: no admin session, no repeater
 * hub, and possibly no stored address. Every route that goes *through*
 * the node is unavailable at precisely the moment recovery is needed —
 * which is how the first cut ended up reachable only from
 * Settings → Firmware on an unrelated radio.
 *
 * The rule this pins: recovery is offered on the node's own long-press
 * sheet, and it does not depend on data the app may never have
 * captured.
 */
class FirmwareRecoveryTest {

    private val nodes = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/NodesScreen.kt",
    )

    @Test
    fun `the node sheet offers recovery`() {
        val source = nodes.readText()
        assertTrue("NodesScreen.kt not found", nodes.exists())
        assertTrue(
            "the long-press sheet no longer offers a recovery action",
            source.contains("Recover / firmware…"),
        )
    }

    @Test
    fun `recovery is not gated on an address the app may never have seen`() {
        // It used to be inside `contact.otaAddress?.let { … }`, so a node
        // that entered update mode from a session this app never
        // witnessed had no way back at all — the case that most needs
        // one.
        val source = nodes.readText()
        val gated = Regex(
            """otaAddress\?\.let\s*\{[^}]*Recover""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertFalse(
            "recovery is hidden unless an update-mode address was recorded",
            gated.containsMatchIn(source),
        )
    }

    @Test
    fun `the weak-signal screen's buttons reach the flash path`() {
        // Both buttons on that screen call into the controller, and the
        // controller used to accept only the Confirm state — so "Try
        // again" and "Transfer anyway" silently did nothing, which is
        // indistinguishable from a frozen app.
        val controller = File(
            "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/FirmwareUpdateController.kt",
        )
        val source = controller.readText()
        val flash = source.substringAfter("fun flash()").substringBefore("scope.launch")
        assertTrue(
            "flash() no longer accepts the weak-signal state, so its buttons do nothing",
            flash.contains("is FirmwareUi.WeakSignal"),
        )
    }
}
