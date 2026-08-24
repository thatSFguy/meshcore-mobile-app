package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two pieces of Compose wiring that no local unit test can execute, and
 * whose failure is silent.
 *
 * The rules themselves are pure and tested where they live — the badge
 * arithmetic in `shared` (`InboxTest`). What is pinned here is that the
 * screens actually use them, which is where this codebase's defects
 * keep turning up (LESSONS §13, and the OTA wiring next door). Source
 * pins are lint, not proof, and they say so.
 */
class AdminInputWiringTest {

    private fun read(path: String) = File(path).readText()

    private val screens = "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/"
    private val verbatim = read(screens + "VerbatimInput.kt")
    private val console = read(screens + "RepeaterSpokeScreens.kt")
    private val regions = read(screens + "RepeaterRegionsPanel.kt")
    private val main = read("src/main/kotlin/io/github/thatsfguy/meshcore/android/MainActivity.kt")

    @Test
    fun `the verbatim keyboard turns off both of Android's helpful defaults`() {
        // The positive control for the two pins below: they assert that
        // fields USE this constant, which proves nothing if the constant
        // itself stops disabling anything.
        assertTrue(
            "VERBATIM_KEYBOARD must disable autocorrect",
            verbatim.contains("autoCorrectEnabled = false"),
        )
        assertTrue(
            "VERBATIM_KEYBOARD must disable auto-capitalisation",
            verbatim.contains("capitalization = KeyboardCapitalization.None"),
        )
    }

    @Test
    fun `the CLI command field is not autocorrected`() {
        // `set prv.key <128 hex>` is exactly the shape a keyboard
        // "fixes", and the node answers a mangled command with an error
        // that names nothing. Ours went out with the default keyboard
        // until 2026-08-23.
        val field = console.substringAfter("placeholder = { Text(\"CLI command")
            .substringBefore("IconButton(")
        assertTrue(
            "the console input must use VERBATIM_KEYBOARD",
            field.contains("keyboardOptions = VERBATIM_KEYBOARD"),
        )
    }

    @Test
    fun `region name and parent are not autocorrected`() {
        // A region name is a token the firmware matches byte for byte,
        // and `region home <name>` on a capitalised name fails as
        // "unknown region" — which reads as the region not existing.
        val name = regions.substringAfter("label = { Text(\"Name\") }").substringBefore("modifier")
        val parent = regions.substringAfter("label = { Text(\"Parent")
            .substringBefore("modifier")
        assertTrue(
            "the region name field must use VERBATIM_KEYBOARD",
            name.contains("keyboardOptions = VERBATIM_KEYBOARD"),
        )
        assertTrue(
            "the region parent field must use VERBATIM_KEYBOARD",
            parent.contains("keyboardOptions = VERBATIM_KEYBOARD"),
        )
    }

    @Test
    fun `the chats tab badge is the shared rule, not a second sum`() {
        // The count must come from Inbox so iOS inherits it and so the
        // cap and the negative-count guard apply here too. A hand-rolled
        // `sumOf { it.unread }` in the shell is the exact shape that has
        // produced six defects in this codebase.
        assertTrue(
            "the tab badge must come from Inbox",
            main.contains("Inbox.badgeLabel(Inbox.unreadTotal("),
        )
        assertFalse(
            "the shell must not sum unread counts itself",
            main.contains("sumOf { it.unread }"),
        )
    }

    @Test
    fun `an empty badge draws no badge`() {
        // Inbox returns "" for nothing-unread; the shell has to honour
        // it. Drawing a Badge on "" gives an empty coloured dot on the
        // Chats tab for ever, which reads as a permanently stuck unread
        // marker.
        val icon = main.substringAfter("icon = {").substringBefore("label = { Text(tab.label) }")
        assertTrue(
            "the shell must skip the badge when the label is empty",
            icon.contains("tab.badge.isEmpty()"),
        )
        assertTrue("the shell must draw a BadgedBox otherwise", icon.contains("BadgedBox("))
    }


    // --- the rekey sequence (identity panel) ---

    private val viewModel =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/MeshCoreViewModel.kt")
    private val identity = read(screens + "RepeaterIdentityPanel.kt")

    private val rekey: String
        get() = viewModel.substringAfter("suspend fun replaceIdentityKey(")
            .substringBefore("suspend fun rebootRepeater(")

    @Test
    fun `a mismatched public key stops before anything is changed`() {
        // The cross-check has to come BEFORE the adoption, or a node
        // that reported an identity we did not ask for gets written into
        // the contact list anyway. Order is the whole guarantee here,
        // and order is what a source pin can see.
        val check = rekey.indexOf("mismatchedWith = expected")
        val adopt = rekey.indexOf("adoptNewIdentity(")
        assertTrue("the mismatch check must exist", check > 0)
        assertTrue("adoption must come after the mismatch check", adopt > check)
    }

    @Test
    fun `the contact is adopted before the node is rebooted`() {
        // The reply carrying the new public key is the only copy the app
        // gets without waiting for a flood advert, and the node is about
        // to stop answering. Writing the contact first means a reboot
        // that goes wrong still leaves the user with the identity.
        val adopt = rekey.indexOf("adoptNewIdentity(")
        val reboot = rekey.indexOf("rebootRepeater(")
        assertTrue("adoption must happen", adopt > 0)
        assertTrue("the reboot must come after the adoption", reboot > adopt)
    }

    @Test
    fun `the reboot is sent without waiting for a reply`() {
        // A repeater reboots without writing a reply and ACKs only
        // TXT_TYPE_PLAIN, so awaiting one spends 15 seconds to learn
        // nothing and turns the expected silence into a timeout.
        val fn = viewModel.substringAfter("suspend fun rebootRepeater(")
            .substringBefore("private suspend fun adoptNewIdentity(")
        assertTrue(
            "reboot must use the fire-and-confirm-sent path",
            fn.contains("sendCliCommand("),
        )
        assertFalse(
            "reboot must not await a CLI reply",
            fn.contains("cliQuery(") || fn.contains("sendCliAndAwaitReply("),
        )
    }

    @Test
    fun `the confirmation probe waits for the node to come back`() {
        // The firmware schedules its own boot advert 16 s after start.
        // Probing sooner asks a node that is not listening yet, and the
        // silence would be reported against a node that is fine.
        val fn = viewModel.substringAfter("private suspend fun confirmRebooted(")
        assertTrue(
            "the probe must wait before asking",
            fn.contains("delay(REBOOT_SETTLE_MS)"),
        )
        assertTrue(
            "the settle time must be at least the firmware's 16 s advert delay",
            Regex("""REBOOT_SETTLE_MS = (\d[\d_]*)L""").find(viewModel)
                ?.groupValues?.get(1)?.replace("_", "")?.toLong()?.let { it >= 16_000 } == true,
        )
    }

    @Test
    fun `the panel reports through the shared rule`() {
        // The wording is pinned by RekeyFlowTest in shared. A panel that
        // built its own sentence would escape every one of those tests —
        // including the one that stops it claiming a reboot happened.
        assertTrue(
            "the panel must describe the outcome via RekeyFlow",
            identity.contains("RekeyFlow.describe("),
        )
    }

    @Test
    fun `the restart checkbox defaults to on`() {
        // A stored key does nothing until the node restarts. Defaulting
        // it off produces the state that confuses everyone: a node
        // reporting one identity and answering to another.
        val dialog = identity.substringAfter("private fun ReplaceKeyDialog(")
        assertTrue(
            "the reboot checkbox must default to checked",
            dialog.contains("var reboot by remember { mutableStateOf(true) }"),
        )
    }
}
