package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node heard but not added opens a sheet like a contact's, on tap and
 * on long-press, and adding it from there opens the full contact sheet.
 * The rules underneath (HeardReach, RepeaterSignals, LastHeard) are
 * tested in shared; this holds the screen to using them.
 */
class HeardNodeSheetWiringTest {

    private val s = File("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/NodesScreen.kt").readText()

    @Test
    fun `tap and long-press on a heard row both open its sheet`() {
        assertTrue(s.contains(".combinedClickable(onClick = onOpen, onLongClick = onOpen)"))
        assertTrue(s.contains("onOpen = { heardDetail = d },"))
    }

    @Test
    fun `adding from the sheet opens the contact sheet once the radio has it`() {
        assertTrue(s.contains("openWhenAdded = node.keyHex"))
        assertTrue(s.contains("contacts.firstOrNull { it.keyHex == key }?.let {"))
    }

    @Test
    fun `both sheets draw position and distance the same way`() {
        // One helper, called by both — the two copies once disagreed.
        assertEquals(2, Regex("""PositionRows\(""").findAll(s).count() - 1)
    }
}
