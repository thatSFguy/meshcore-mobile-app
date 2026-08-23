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
}
