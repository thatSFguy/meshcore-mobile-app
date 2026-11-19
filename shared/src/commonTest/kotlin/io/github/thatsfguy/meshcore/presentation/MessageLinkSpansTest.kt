package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageLinkSpansTest {
    @Test
    fun bothKindsAreFoundWithSentencePunctuationLeftBehind() {
        val text = "see https://example.org/a?b=1. and meshcore://contact?name=Bob&type=1!"
        val links = MessageLinkSpans.find(text)
        assertEquals(listOf("https://example.org/a?b=1", "meshcore://contact?name=Bob&type=1"), links.map { it.url })
        assertEquals(listOf(true, false), links.map { it.isHttp })
        val first = links.first()
        assertEquals(first.url, text.substring(first.start, first.end))
    }

    @Test
    fun whicheverStartsFirstWinsAnOverlapAndABareSchemeIsNotALink() {
        assertTrue(MessageLinkSpans.find("hi @[https://x.org] there").isEmpty(), "starts inside a mention")
        // Android's MentionWiringTest case: the URL starts first and stays whole.
        // (`]` ends a URL, as it always has on Android.)
        assertEquals("https://example.com/?q=@[x", MessageLinkSpans.find("https://example.com/?q=@[x]").single().url)
        assertTrue(MessageLinkSpans.find("http:// nothing").isEmpty())
        assertTrue(MessageLinkSpans.find("no links here @[Bob]").isEmpty())
    }

    @Test
    fun offsetsAreUtf16SoEmojiBeforeALinkDontShiftIt() {
        val text = "🙂🙂 https://a.org"
        val link = MessageLinkSpans.find(text).single()
        assertEquals("https://a.org", text.substring(link.start, link.end))
        assertEquals(5, link.start, "two surrogate pairs and a space")
    }

    @Test
    fun theWarningNamesTheAddress() {
        assertTrue("https://a.org" in MessageLinkSpans.leaveText("https://a.org"))
        assertFalse(MessageLinkSpans.leaveText("x").isBlank())
    }
}
