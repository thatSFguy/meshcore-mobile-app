package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarGlyphTest {

    @Test
    fun `a leading emoji is skipped for the initial`() {
        // Real names from this mesh.
        assertEquals("D", AvatarGlyph.of("🐄Donk-N8FWG"))
        assertEquals("F", AvatarGlyph.of("🐸 FROG"))
        assertEquals("E", AvatarGlyph.of("👾ENGNR"))
    }

    @Test
    fun `plain names are unchanged`() {
        assertEquals("B", AvatarGlyph.of("BlueT1000"))
        assertEquals("W", AvatarGlyph.of("  wall-Base"))
        assertEquals("1", AvatarGlyph.of("123 Main"))
        assertEquals("É", AvatarGlyph.of("éclair"))
    }

    @Test
    fun `a name that is only an emoji shows the whole emoji`() {
        // Never half of one — that half is what drew the "�".
        assertEquals("🔥", AvatarGlyph.of("🔥"))
        assertEquals("🔥", AvatarGlyph.of(" 🔥🔥 "))
    }

    @Test
    fun `nothing to show is a question mark`() {
        assertEquals("?", AvatarGlyph.of(""))
        assertEquals("?", AvatarGlyph.of("   "))
        // A lone surrogate half, as a truncated name can end up.
        assertEquals("?", AvatarGlyph.of("\uD83D"))
    }

    @Test
    fun `punctuation before a name is skipped too`() {
        assertEquals("N", AvatarGlyph.of("[NC0N"))
        assertEquals("K", AvatarGlyph.of("★ KCEST"))
    }
}
