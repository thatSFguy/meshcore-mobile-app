package io.github.thatsfguy.meshcore.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.thatsfguy.meshcore.android.ui.screens.MessageLinks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@[Name]` rendering, and the composer wiring around it.
 *
 * The convention itself is pinned in `MentionsTest` over in `shared`,
 * where it belongs and where iOS inherits it. What is left here is the
 * half that only exists on Android: that the annotated string actually
 * carries the styles, that a mention is NOT turned into a tappable link,
 * and that the composer keeps the two properties the pure model cannot
 * see — a caret to anchor the picker to, and the frame budget.
 */
class MentionWiringTest {

    private val link = Color(0xFF0000FF)
    private val mention = Color(0xFF00FF00)

    private fun annotate(text: String, selfName: String? = null) =
        MessageLinks.annotate(
            text = text,
            linkColor = link,
            mentionColor = mention,
            selfName = selfName,
            onHttpLink = {},
            onMeshcoreLink = {},
        )

    // ------------------------------------------------------------ styling

    @Test
    fun `a mention is styled`() {
        // The positive control for this file: everything else asserts
        // that something is absent, and would pass against a renderer
        // that dropped mentions on the floor.
        val a = annotate("morning @[Blue Base]")
        val styled = a.spanStyles.single()
        assertEquals(mention, styled.item.color)
        assertEquals("@[Blue Base]", a.text.substring(styled.start, styled.end))
    }

    @Test
    fun `the text is preserved exactly`() {
        // The renderer rebuilds the string span by span. An off-by-one
        // in the overlap guard silently eats or repeats characters, and
        // the result still renders — as a subtly wrong message.
        for (text in listOf(
            "morning @[Blue Base] and @[Base], radio check",
            "@[Bob]",
            "no mentions here at all",
            "@[] @[Bob] @[",
            "see https://example.com and @[Bob] too",
        )) {
            assertEquals(text, annotate(text).text)
        }
    }

    @Test
    fun `only the mention of the reader is bold`() {
        // Bolding every mention makes a message tagging three people
        // shout, and loses the one distinction the weight carries.
        val a = annotate("@[Blue Base] and @[Somebody]", selfName = "Blue Base")
        val weights = a.spanStyles.map { it.item.fontWeight }
        assertEquals(listOf(FontWeight.Bold, FontWeight.Medium), weights)
    }

    @Test
    fun `a mention is never tappable`() {
        // A mention carries a name and no key, so "open that node" would
        // mean guessing which contact answers to the string. Tinting
        // claims nothing; a link claims it resolved.
        val a = annotate("@[Blue Base]")
        assertTrue(
            "a mention must not become a link annotation",
            a.getLinkAnnotations(0, a.text.length).isEmpty(),
        )
    }

    @Test
    fun `a real link is still tappable alongside a mention`() {
        // The regression guard for folding two passes into one: the
        // mention must not have consumed the URL's span.
        val a = annotate("@[Bob] see https://example.com")
        assertEquals(1, a.getLinkAnnotations(0, a.text.length).size)
    }

    @Test
    fun `a url containing the mention marker stays one link`() {
        // The overlap case the single pass exists for. Two independent
        // passes would style the `@[` inside the query string and cut
        // the link in half.
        val text = "https://example.com/?q=@[x]"
        val a = annotate(text)
        assertEquals(text, a.text)
        assertEquals(1, a.getLinkAnnotations(0, a.text.length).size)
        assertTrue("the URL must not be split by a mention span", a.spanStyles.isEmpty())
    }

    @Test
    fun `hasMarkup notices a mention`() {
        // The fast path. If this misses, a message full of mentions
        // renders as plain text and every test above is unreachable.
        assertTrue(MessageLinks.hasMarkup("hi @[Bob]"))
        assertTrue(MessageLinks.hasMarkup("see https://example.com"))
        assertFalse(MessageLinks.hasMarkup("just a message"))
        assertFalse(MessageLinks.hasMarkup("an @ on its own"))
    }

    // ------------------------------------------------------------- wiring

    private val screen = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/ConversationScreen.kt",
    ).readText()

    @Test
    fun `the composer tracks a caret`() {
        // The picker anchors to where the `@` was typed. A String
        // composer cannot say where that is, so the whole feature rests
        // on this one type.
        assertTrue(
            "the draft must be a TextFieldValue for the picker to have a caret",
            screen.contains("mutableStateOf(TextFieldValue(saved, TextRange(saved.length)))"),
        )
    }

    @Test
    fun `a caret move is never rejected by the size limit`() {
        // The composer refuses input over the frame budget. Applied to a
        // TextFieldValue that also carries the selection, a draft
        // sitting exactly on the limit becomes un-navigable — arrow
        // keys and taps stop moving the caret, which reads as a frozen
        // text field.
        assertTrue(
            "caret-only changes must bypass the budget check",
            screen.contains("if (it.text == draft.text ||"),
        )
    }

    @Test
    fun `the picker is shown only while something matches`() {
        // What makes it safe for a query to run on past a space: an `@`
        // typed in prose stops matching within a word or two and the row
        // disappears on its own. Without this it would sit there empty.
        assertTrue(
            "the picker must be gated on a non-empty candidate list",
            screen.contains("if (suggestions.isNotEmpty())"),
        )
    }

    @Test
    fun `inserting a mention respects the frame budget`() {
        // Both insertion paths — the picker and the Mention action —
        // add bytes to a ~150-byte frame. Neither may push a draft over
        // the limit the composer is enforcing everywhere else.
        val budgetChecks =
            Regex("""encodeToByteArray\(\)\.size <= bodyBudget""").findAll(screen).count()
        assertTrue(
            "every mention insertion path must check bodyBudget (found $budgetChecks)",
            budgetChecks >= 3,
        )
    }

    @Test
    fun `a direct message does not offer the whole contact list`() {
        // There is exactly one other party to a DM. Offering every
        // contact would suggest tagging people who will never see the
        // message — the mention is text in a body, not an address.
        val block = screen.substringAfter("var mentionNames by remember")
            .substringBefore("MessageBubble(")
        assertTrue(
            "a channel offers the names seen posting on it",
            block.contains("vm.channelSenders("),
        )
        assertTrue(
            "a DM offers only the peer",
            block.contains("listOfNotNull(title.takeIf { Mentions.canMention(it) })"),
        )
    }

    @Test
    fun `being tagged is signalled by more than colour`() {
        // A tinted container against a tinted theme can be nearly the
        // same value, and colour alone is not a signal on a colour-blind
        // screen. Same rule the neighbour links follow after the
        // 2026-08-24 hardware session deleted their legend.
        val bubble = screen.substringAfter("val bubbleShape =").substringBefore("Column {")
        assertTrue(
            "the tagged bubble must carry a border as well as a fill",
            bubble.contains("tagsMe -> MaterialTheme.colorScheme.tertiaryContainer") &&
                bubble.contains("if (tagsMe) {") && bubble.contains("Modifier.border("),
        )
    }

    @Test
    fun `an outgoing message never reports that it tagged the reader`() {
        // Your own name in your own message is not somebody tagging you,
        // and a self-addressed highlight is how a reader learns to
        // ignore the highlight.
        assertTrue(
            "tagsMe must exclude outgoing messages",
            screen.contains("!outgoing && Mentions.tags(body, selfName)"),
        )
    }

    @Test
    fun `Mention is offered separately from Reply`() {
        // Reply quotes the text being answered and costs those bytes;
        // Mention costs the name. The official app seeds a mention on
        // reply, which here would silently double the cost of the
        // commonest action on the tightest budget in the app.
        assertTrue(
            "Mention must be its own action",
            screen.contains("""ActionLabel("Mention", onMention)"""),
        )
        assertTrue(
            "Mention is only useful where more than one person can read it",
            screen.contains("canMention = isChannel && !m.outgoing &&"),
        )
    }
}
