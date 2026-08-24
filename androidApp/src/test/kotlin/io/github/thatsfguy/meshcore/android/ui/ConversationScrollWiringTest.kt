package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four facts that together put a conversation at its newest message.
 *
 * This is the bug that keeps coming back — three times in the sibling
 * app (its issue #30) and now twice here — and every return has been the
 * same shape: the anchor is structural, so nothing about it is visible
 * at the call site, and an unrelated edit switches it off without
 * touching a line that mentions scrolling. The 2026-08-23 regression was
 * `Arrangement.spacedBy(4.dp)`, added for the gap between bubbles: in a
 * `reverseLayout` list the DEFAULT arrangement is `Arrangement.Bottom`,
 * naming any arrangement replaces that default, and `spacedBy(space)`
 * means `spacedBy(space, Alignment.Top)`. Four decorative dp silently
 * un-anchored every thread.
 *
 * These are source pins — lint, not proof; the behaviour itself was
 * driven on the phone (Galaxy A42, 2026-08-23, screenshots before and
 * after). Their job is to fail loudly when the next edit takes one of
 * the four facts away.
 */
class ConversationScrollWiringTest {

    private val screen = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/ConversationScreen.kt",
    ).readText()

    private val daos =
        File("src/main/kotlin/io/github/thatsfguy/meshcore/android/storage/Daos.kt").readText()

    /** The message list's own arguments, not the composer's or a sheet's. */
    private val list: String
        get() = screen.substringAfter("LazyColumn(").substringBefore("items(messages")

    @Test
    fun `the thread list is reverse-laid-out`() {
        // The whole anchor. Without it the newest message is at the top
        // and every other pin here is about nothing.
        assertTrue("the message list must use reverseLayout", list.contains("reverseLayout = true"))
    }

    @Test
    fun `the arrangement keeps the bottom alignment`() {
        // spacedBy(4.dp) alone is spacedBy(4.dp, Alignment.Top) and
        // replaces reverseLayout's Arrangement.Bottom default — which is
        // exactly how this regressed. A thread shorter than the viewport
        // then floats at the top with a hole above the composer, and
        // opening the keyboard pushes the newest bubbles out of the
        // shrunken viewport.
        assertTrue(
            "a reverseLayout list must state Alignment.Bottom in its arrangement",
            list.contains("Arrangement.spacedBy(4.dp, Alignment.Bottom)"),
        )
        assertFalse(
            "spacedBy without an alignment cancels the bottom anchor",
            Regex("""verticalArrangement = Arrangement\.spacedBy\([^,)]*\)""").containsMatchIn(list),
        )
    }

    @Test
    fun `the newest message is index zero`() {
        // reverseLayout draws index 0 at the bottom, so the query has to
        // hand back newest-first. These two live in different files and
        // are the same assumption; if the ORDER BY is ever flipped to
        // ASC the whole thread renders upside down, which is the kind of
        // thing that gets called a scroll bug.
        assertTrue(
            "threadPaged must return newest-first for the reversed layout",
            daos.contains("ORDER BY timestamp DESC, receivedAt DESC LIMIT :limit"),
        )
    }

    @Test
    fun `a thread opens at its newest message rather than where it was left`() {
        // rememberLazyListState() is a saveable: a thread reopened from
        // the back stack restores the offset from last time, which for a
        // chat means opening days back and scrolling down through
        // everything since. Keyed to the thread, the state is new on
        // entry and a new LazyListState starts at index 0 — the bottom.
        assertTrue(
            "the list state must be keyed to the thread",
            screen.contains("remember(kind, peerKey) { LazyListState() }"),
        )
        assertFalse(
            "rememberLazyListState restores a stale position into a chat",
            // The assignment, not the word: the comment above the fix
            // names the API it is deliberately avoiding, and a bare
            // substring match on that is a test that fails on its own
            // explanation.
            screen.contains("= rememberLazyListState()"),
        )
    }

    @Test
    fun `a newly arrived message still nudges the view down`() {
        // The structural anchor holds what is already laid out; a freshly
        // prepended index 0 lands just past it. Gated on being near the
        // bottom so reading back through history is not interrupted.
        assertTrue(
            "the prepend nudge must survive",
            screen.contains("listState.animateScrollToItem(0)") &&
                screen.contains("listState.firstVisibleItemIndex <= 2"),
        )
    }
}
