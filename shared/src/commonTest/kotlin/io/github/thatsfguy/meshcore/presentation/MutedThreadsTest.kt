package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutedThreadsTest {
    @Test
    fun togglingMutesAndUnmutesOneThreadOnly() {
        var raw = MutedThreads.toggled("", "ch|2")
        raw = MutedThreads.toggled(raw, "dm|aabb")
        assertTrue(MutedThreads.isMuted(raw, "ch|2"))
        assertTrue(MutedThreads.isMuted(raw, "dm|aabb"))
        assertFalse(MutedThreads.isMuted(raw, "ch|20"), "no prefix match")
        raw = MutedThreads.toggled(raw, "ch|2")
        assertFalse(MutedThreads.isMuted(raw, "ch|2"))
        assertEquals(setOf("dm|aabb"), MutedThreads.decode(raw))
    }

    @Test
    fun junkIsIgnored() {
        assertEquals(setOf("ch|1"), MutedThreads.decode(" ch|1 ,,garbage,  ,x|y"))
        assertTrue(MutedThreads.decode(null).isEmpty())
    }
}

class ChatSearchTest {
    @Test
    fun aThreadMatchesOnTitleOrLastMessage() {
        assertTrue(ChatSearch.matches("Public", "hello there", ""))
        assertTrue(ChatSearch.matches("Public", "hello there", "PUB"))
        assertTrue(ChatSearch.matches("Public", "hello there", " there "))
        assertFalse(ChatSearch.matches("Public", null, "there"))
        assertFalse(ChatSearch.matches("Public", "hello", "bob"))
    }
}
