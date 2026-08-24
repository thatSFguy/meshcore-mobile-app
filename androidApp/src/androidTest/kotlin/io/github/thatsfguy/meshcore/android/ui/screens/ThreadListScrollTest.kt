package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import org.junit.Rule
import org.junit.Test

/**
 * Where a conversation actually sits — asserted on a device, not in a
 * source file.
 *
 * "The chat doesn't open at the bottom" has been reported five times
 * across this app and its sibling. Every fix so far was guarded by a
 * source pin: a test asserting the code still *says* `reverseLayout` and
 * `Alignment.Bottom`. Those pins are lint. They cannot see layout, so
 * every version of the bug passed them, including the one that shipped
 * in 0.8.6.
 *
 * These run on a real device against real Compose layout, and they fail
 * against the code that shipped. That is the point: a regression here is
 * caught by `:androidApp:connectedDebugAndroidTest` before a phone ever
 * sees it.
 *
 * Bubbles are plain fixed-height rows on purpose — this is a test about
 * scroll position, and a real `MessageBubble` would drag in a ViewModel,
 * a database and a radio to prove nothing extra.
 */
class ThreadListScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private fun message(index: Long) = MessageEntity(
        id = index,
        selfKey = "self",
        kind = "ch",
        peerKey = "0",
        senderName = "Someone",
        text = "m-$index",
        timestamp = 1_787_000_000L + index,
        receivedAt = 1_787_000_000_000L + index,
        outgoing = false,
        status = 0,
        ackHash = null,
        contentKey = null,
        snr = null,
    )

    /** Newest first, as `threadPaged` returns them. */
    private fun thread(count: Int) = (count - 1 downTo 0).map { message(it.toLong()) }

    @Composable
    private fun Row(m: MessageEntity) {
        Text(m.text, modifier = Modifier.height(90.dp))
    }

    private fun show(
        messages: List<MessageEntity>,
        total: Int = messages.size,
        threadKey: String = "ch|0",
    ) {
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                ThreadList(
                    threadKey = threadKey,
                    messages = messages,
                    total = total,
                    onLoadOlder = {},
                ) { Row(it) }
            }
        }
    }

    @Test
    fun aShortThreadShowsItsNewestMessage() {
        // Three rows cannot fill the screen. In a reversed list they must
        // pack against the bottom; the 0.8.6 regression floated them at
        // the top with a hole above the composer.
        show(thread(3))
        rule.onNodeWithText("m-2").assertIsDisplayed()
    }

    @Test
    fun aLongThreadOpensAtItsNewestMessage() {
        // Fifty 90dp rows are several screens tall.
        show(thread(50))
        rule.onNodeWithText("m-49").assertIsDisplayed()
    }

    @Test
    fun rowsArrivingAfterTheCountStillLandAtTheNewest() {
        // THE test in this file: the 0.8.6 bug, reduced.
        //
        // `threadCount` and `threadPaged` are separate queries and
        // COUNT(*) returns before fifty full rows do, so re-entering a
        // long thread composes the list ONCE with no rows and a known
        // total — a list containing only the load-older button — and a
        // lazy list anchors by item key. Against the shipped code this
        // fails with the thread parked at its oldest message.
        var messages by mutableStateOf(emptyList<MessageEntity>())
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                ThreadList(
                    threadKey = "ch|0",
                    messages = messages,
                    total = 150,
                    onLoadOlder = {},
                ) { Row(it) }
            }
        }
        rule.waitForIdle()
        messages = thread(50)
        rule.waitForIdle()
        rule.onNodeWithText("m-49").assertIsDisplayed()
    }

    @Test
    fun anArrivingMessageIsScrolledIntoView() {
        // A freshly prepended index 0 lands just past the anchor, behind
        // the composer, unless it is nudged in.
        var messages by mutableStateOf(thread(50))
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                ThreadList(
                    threadKey = "ch|0",
                    messages = messages,
                    total = messages.size,
                    onLoadOlder = {},
                ) { Row(it) }
            }
        }
        rule.waitForIdle()
        messages = listOf(message(999)) + messages
        rule.waitForIdle()
        rule.onNodeWithText("m-999").assertIsDisplayed()
    }

    @Test
    fun theLoadOlderRowIsNeverTheOnlyRow() {
        // With no messages there is nothing to load older THAN, and a
        // lone keyed row is exactly what the list latches onto.
        show(emptyList(), total = 150)
        rule.onAllNodesWithText("Load older", substring = true).assertCountEquals(0)
    }

    @Test
    fun aThreadLongerThanAPageStillOffersItsOlderMessages() {
        // The positive control for the guard above: the guard must
        // suppress the row only when there are NO messages, not whenever
        // there are older ones to fetch.
        //
        // Three rows, so the button is on screen. A fifty-row thread
        // would put it several screens up, where a lazy list has not
        // composed it at all and the finder cannot see it — which is a
        // fact about lazy lists, not about the guard, and asserting on
        // it would make this test lie.
        show(thread(3), total = 150)
        rule.onAllNodesWithText("Load older", substring = true).assertCountEquals(1)
        rule.onNodeWithText("m-2").assertIsDisplayed()
    }
}
