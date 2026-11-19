package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.Frames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerBudgetTest {
    @Test
    fun aQuoteAndAChannelNameComeOutOfTheSameBudget() {
        val dm = ComposerBudget.budget(false, "Rob", "")
        assertEquals(Frames.maxContactMessageBytes(), dm)
        val ch = ComposerBudget.budget(true, "Rob", "")
        assertEquals(Frames.maxChannelMessageBytes("Rob"), ch)
        assertTrue(ComposerBudget.budget(true, "A much longer node name", "") < ch, "a longer name leaves less")
        assertEquals(dm - "@[Bob] hi\n".length, ComposerBudget.budget(false, "Rob", "@[Bob] hi\n"))
    }

    @Test
    fun clampingNeverSplitsACharacter() {
        val text = "é".repeat(100) // 2 bytes each
        val clamped = ComposerBudget.clamp(text, 11)
        assertEquals("é".repeat(5), clamped, "10 bytes, not half an é")
        assertEquals("short", ComposerBudget.clamp("short", 11))
        assertTrue(ComposerBudget.fits(clamped, 11))
    }

    @Test
    fun theCounterAppearsOnlyNearTheEnd() {
        assertNull(ComposerBudget.counter("hi", 150))
        assertEquals("5 bytes left", ComposerBudget.counter("x".repeat(145), 150))
    }
}
