package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupChecklistTest {
    private fun steps(connected: Boolean = true, name: String? = "Base", presets: List<String> = listOf("USA/Canada"),
                      simple: Boolean = false, group: Boolean = false) =
        SetupChecklist.steps(connected, "MeshCore-Blue", name, presets, simple, group)

    @Test
    fun fullModeHasFourStepsAndSimpleAddsTheGroupChannel() {
        assertEquals(listOf("connect", "name", "radio", "protects"), steps().map { it.key })
        val simple = steps(simple = true)
        assertEquals(listOf("connect", "name", "radio", "group", "protects"), simple.map { it.key })
        assertEquals(listOf(1, 2, 3, 4, 5), simple.map { it.number })
    }

    @Test
    fun nothingCountsAsDoneWithoutARadio() {
        val s = steps(connected = false)
        assertFalse(s.any { it.done })
        assertTrue(s.first().detail.startsWith("Pair"))
        assertTrue("Currently on" !in s[2].detail, "a disconnected radio's stale values claim no preset")
    }

    @Test
    fun aConnectedRadioOnAPresetIsDoneAndSaysWhich() {
        val s = steps()
        assertEquals("Connected to MeshCore-Blue.", s[0].detail)
        assertTrue(s[1].done && s[2].done)
        assertEquals("Currently on USA/Canada.", s[2].detail)
        assertFalse(steps(presets = emptyList())[2].done)
        assertFalse(steps(name = " ")[1].done)
        assertFalse(s.last().done, "what the app protects is read, never ticked")
    }
}
