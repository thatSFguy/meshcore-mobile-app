package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.messaging.MessageBook
import io.github.thatsfguy.meshcore.messaging.PathHistoryBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PurgeTest {
    @Test
    fun onlyTheWordArmsIt() {
        assertTrue(Purge.armed(" purge "))
        assertFalse(Purge.armed("purg"))
        assertFalse(Purge.armed(""))
        assertFalse(Purge.armed("yes"))
    }

    @Test
    fun theResultStatesFactsAndThatTheRadioWasNotTouched() {
        assertEquals(
            "Purged 12 message(s), 3 contact(s), 2 channel(s). The radio was not touched.",
            Purge.result(12, 3, 2, alsoSecrets = false),
        )
        assertEquals(
            "Purged 0 message(s), and all stored keys and passwords. The radio was not touched.",
            Purge.result(0, null, null, alsoSecrets = true),
        )
    }

    @Test
    fun theBooksEmptyCompletely() {
        var t = 1L
        val book = MessageBook(nowMs = { t++ })
        book.receive("ch|0", true, "Bob", "a", 1, "k1")
        book.receive("dm|aa", false, null, "b", 2, "k2")
        assertEquals(2, book.clearAll())
        assertTrue(book.messages.value.isEmpty())
        assertEquals(0, book.unreadTotal())
        val paths = PathHistoryBook(nowMs = { t++ })
        paths.remember("aa".repeat(32), "b389c985", 2)
        paths.remember("bb".repeat(32), "c985", 2)
        assertEquals(2, paths.rows.value.size, "the rows exist before the purge")
        paths.clearAll()
        assertTrue(paths.rows.value.isEmpty())
    }
}
