package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an inbound reaction points at, and whether it interrupts anyone.
 *
 * This logic lived inside `MessageRepository`, so exercising it needed
 * a database and a device — and its hostile-input surface is real: the
 * target hash arrives over the air from anyone within radio range.
 */
class ReactionRoutingTest {

    private fun msg(
        id: Long,
        text: String,
        ts: Long = 1_700_000_000L,
        sender: String? = null,
        outgoing: Boolean = false,
    ) = ReactionRouting.Candidate(id, ts, sender, text, outgoing)

    /** The hash a real sender would compute for [c] in this thread kind. */
    private fun hashOf(c: ReactionRouting.Candidate, isChannel: Boolean) =
        Reactions.targetHash(c.timestamp, if (isChannel) c.senderName.orEmpty() else null, c.text)

    // --- finding the target ------------------------------------------------

    @Test
    fun `a direct-message reaction finds the message it names`() {
        val a = msg(1, "leaving about six")
        val b = msg(2, "see you there", ts = 1_700_000_050L)
        val found = ReactionRouting.target(listOf(b, a), hashOf(a, isChannel = false), false)
        assertEquals(1L, found?.id)
    }

    @Test
    fun `a channel reaction folds the sender name into the hash`() {
        val a = msg(1, "leaving about six", sender = "Kaylee")
        val found = ReactionRouting.target(listOf(a), hashOf(a, isChannel = true), true)
        assertEquals(1L, found?.id)
    }

    @Test
    fun `using the DM rule on a channel message matches nothing`() {
        // The two hashes genuinely differ, and getting it backwards
        // looks exactly like "nobody reacted" rather than like a bug.
        val a = msg(1, "leaving about six", sender = "Kaylee")
        assertNull(ReactionRouting.target(listOf(a), hashOf(a, isChannel = true), false))
        assertNull(ReactionRouting.target(listOf(a), hashOf(a, isChannel = false), true))
    }

    @Test
    fun `an unknown hash matches nothing rather than the nearest message`() {
        val a = msg(1, "leaving about six")
        assertNull(ReactionRouting.target(listOf(a), "dead", false))
    }

    @Test
    fun `hash comparison is case-insensitive`() {
        val a = msg(1, "leaving about six")
        val upper = hashOf(a, isChannel = false).uppercase()
        assertEquals(1L, ReactionRouting.target(listOf(a), upper, false)?.id)
    }

    @Test
    fun `an empty thread yields no target`() {
        assertNull(ReactionRouting.target(emptyList(), "abcd", false))
    }

    // --- 16 bits collide ---------------------------------------------------

    @Test
    fun `two messages sharing a hash resolve to the newest, and say so`() {
        // Same timestamp and text is the degenerate collision: the wire
        // offers no tiebreak at all. Answering with the newest is a
        // documented choice, and isAmbiguous is how a caller finds out
        // it was one.
        val older = msg(1, "ok", ts = 1_700_000_000L)
        val newer = msg(2, "ok", ts = 1_700_000_000L)
        val hash = hashOf(newer, isChannel = false)
        val candidates = listOf(newer, older)   // newest first, as stored
        assertEquals(2L, ReactionRouting.target(candidates, hash, false)?.id)
        assertTrue(ReactionRouting.isAmbiguous(candidates, hash, false))
        assertEquals(2, ReactionRouting.matches(candidates, hash, false).size)
    }

    @Test
    fun `a single match is not ambiguous`() {
        val a = msg(1, "unique text here")
        assertFalse(ReactionRouting.isAmbiguous(listOf(a), hashOf(a, false), false))
    }

    // --- who gets interrupted ----------------------------------------------

    @Test
    fun `a reaction to our own message notifies`() {
        // A thumbs-up on something you said is often the whole reply.
        assertTrue(ReactionRouting.shouldNotify(msg(1, "mine", outgoing = true)))
    }

    @Test
    fun `a reaction to someone else's message stays silent`() {
        // Otherwise a busy channel interrupts you for other people's
        // conversations.
        assertFalse(ReactionRouting.shouldNotify(msg(1, "theirs", outgoing = false)))
    }

    // --- echo suppression ---------------------------------------------------

    @Test
    fun `the same reaction key is only counted once`() {
        // One tap reaches us three times: our own local application, the
        // radio's echo, and the RX log. It counted as three.
        val seen = ReactionRouting.SeenKeys()
        assertTrue(seen.remember("ch|0|1700|Kaylee|r:1a2b:00"))
        assertFalse(seen.remember("ch|0|1700|Kaylee|r:1a2b:00"))
        assertFalse(seen.remember("ch|0|1700|Kaylee|r:1a2b:00"))
    }

    @Test
    fun `distinct reactions are each counted`() {
        val seen = ReactionRouting.SeenKeys()
        assertTrue(seen.remember("a"))
        assertTrue(seen.remember("b"))
    }

    @Test
    fun `a null key cannot be deduped and always reads as fresh`() {
        // Direct-message reactions carry no contentKey. Treating null as
        // "already seen" would swallow every one of them.
        val seen = ReactionRouting.SeenKeys()
        assertTrue(seen.firstSight(null))
        assertTrue(seen.firstSight(null))
    }

    @Test
    fun `the seen set is bounded and evicts oldest first`() {
        val seen = ReactionRouting.SeenKeys(max = 4)
        for (i in 1..6) seen.remember("k$i")
        assertEquals(4, seen.size)
        // k1 and k2 were evicted, so they read as fresh again; k6 does not.
        assertTrue(seen.remember("k1"))
        assertFalse(seen.remember("k6"))
    }

    @Test
    fun `the tuning values live here, not in each platform's store`() {
        assertEquals(200, ReactionRouting.SEARCH_WINDOW)
        // 512 is what Android ran with before the move. Pinned so the
        // dedup window is not quietly narrowed by a future tidy-up.
        assertEquals(512, ReactionRouting.MAX_SEEN)
    }
}
