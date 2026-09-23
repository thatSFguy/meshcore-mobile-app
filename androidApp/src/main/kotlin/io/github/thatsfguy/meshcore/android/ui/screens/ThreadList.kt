package io.github.thatsfguy.meshcore.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.github.thatsfguy.meshcore.android.storage.MessageEntity
import io.github.thatsfguy.meshcore.presentation.DayLabel
import io.github.thatsfguy.meshcore.presentation.DaySeparator
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.datetime.TimeZone

/**
 * The message list, and every rule about where it sits.
 *
 * Extracted from `ConversationScreen` on 2026-08-23 for one reason: this
 * is the fifth time "the conversation opens in the wrong place" has been
 * reported, and it had never once been possible to write a test that
 * would fail. The rules live in layout, and the tests that guarded them
 * were source pins — they assert the code still *says* `Alignment.Bottom`
 * and cannot see whether the newest message is on screen. Every version
 * of this bug passed them.
 *
 * A composable taking plain data can be driven by a real Compose test on
 * a real device, including the case that caused the last one: rows
 * arriving a frame AFTER the count they are counted by. See
 * `ThreadListScrollTest`, which fails against the code that shipped in
 * 0.8.6.
 *
 * Four rules, all of them load-bearing:
 *
 *  1. **`reverseLayout`** — index 0 is the newest message and is drawn at
 *     the bottom, so the anchor is structural rather than a scroll that
 *     has to be re-fired on every event.
 *  2. **`Alignment.Bottom` in the arrangement** — naming any arrangement
 *     replaces the `Arrangement.Bottom` that `reverseLayout` defaults to,
 *     and `spacedBy(4.dp)` alone means `spacedBy(4.dp, Alignment.Top)`.
 *     Four decorative pixels un-anchored every short thread in 0.8.6.
 *  3. **The landing scroll** — whatever the list did while it had no
 *     rows, it ends at the newest message once it has them. Once per
 *     thread, so it never yanks somebody reading back.
 *  4. **The load-older row needs a message to sit above** — otherwise
 *     the list can briefly consist of that row alone, and a lazy list
 *     anchors by item key, so it holds on to it while the messages
 *     arrive in front of it. That is what put a 149-message thread at
 *     its oldest message on re-entry.
 */
@Composable
fun ThreadList(
    threadKey: String,
    messages: List<MessageEntity>,
    total: Int,
    modifier: Modifier = Modifier,
    onLoadOlder: () -> Unit,
    row: @Composable (MessageEntity) -> Unit,
) {
    // Deliberately NOT rememberLazyListState(): that is a saveable, so a
    // thread reopened from the back stack restores wherever the user was
    // last time — which for a chat means opening at a message from days
    // ago and scrolling down through everything since. Keyed to the
    // thread, the state is new on entry, and a new LazyListState starts
    // at index 0: the newest message.
    val listState = remember(threadKey) { LazyListState() }

    // Rule 3. The structural anchor holds content that is ALREADY laid
    // out; it cannot defend against a list that was composed with
    // different content a frame earlier and anchored to that. So the
    // invariant is stated outright rather than inferred from layout.
    var landed by remember(threadKey) { mutableStateOf(false) }
    LaunchedEffect(threadKey, messages.isNotEmpty()) {
        if (!landed && messages.isNotEmpty()) {
            listState.scrollToItem(0)
            landed = true
        }
    }

    // A freshly prepended index 0 — the message you just sent — lands
    // just past the anchor, hidden behind the composer and the keyboard.
    // Gated on being near the bottom already, so reading back through
    // history is not interrupted by an arriving message.
    val newestId = messages.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom), // rule 2
        reverseLayout = true, // rule 1
    ) {
        itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
            // The heading is drawn INSIDE the item, above the bubble.
            // reverseLayout flips the order of items, not the content
            // within one — so "above the bubble" here is above it on
            // screen, and the item that owns the heading is the oldest
            // message of its day, which is the one nearest the top.
            Column {
                val stamps = remember(messages) { messages.map { it.timestamp } }
                if (DaySeparator.startsNewDay(stamps, index, TimeZone.currentSystemDefault())) {
                    DayHeading(m.timestamp)
                }
                row(m)
            }
        }
        // Rule 4. Last item in a reversed list == top of the screen.
        if (total > messages.size && messages.isNotEmpty()) {
            item(key = "load_older") {
                TextButton(onClick = onLoadOlder, modifier = Modifier.fillMaxWidth()) {
                    Text("Load older (${total - messages.size} more)")
                }
            }
        }
    }
}

/**
 * "Today" / "Yesterday" / "Wednesday" / "17 September 2026".
 *
 * [DaySeparator] decides which of those four this is; the words come
 * from the phone, so the date reads the way the reader's locale writes
 * dates rather than the way this file would have hardcoded them.
 */
@Composable
private fun DayHeading(epochSeconds: Long) {
    val now = remember { System.currentTimeMillis() / 1000 }
    val label = DaySeparator.labelFor(epochSeconds, now, TimeZone.currentSystemDefault())
        ?: return
    val text = when (label) {
        DayLabel.Today -> "Today"
        DayLabel.Yesterday -> "Yesterday"
        DayLabel.Weekday ->
            SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(epochSeconds * 1000))
        DayLabel.FullDate ->
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(epochSeconds * 1000))
    }
    // A quiet band rather than a divider with a word on it: the heading
    // separates days, and a chat already has plenty of lines in it.
    // Centred in its own Box so the bubbles either side keep their own
    // left/right alignment.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
    }
}
