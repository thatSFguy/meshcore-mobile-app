package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@[Name]` — the mention convention, parsed, matched and composed.
 *
 * Two failure shapes carry this suite, and they pull in opposite
 * directions. A false NEGATIVE loses a tag the sender meant; a false
 * POSITIVE either swallows prose into a highlight or — much worse —
 * tells someone a message is addressed to them when it is not. Most of
 * what follows asserts the second, so `namesTheTaggedPerson` and
 * `completesTheNameTheUserPicked` are here as the positive controls
 * without which the whole file would pass against a stub.
 */
class MentionsTest {

    // ---------------------------------------------------------------- parse

    @Test
    fun namesTheTaggedPerson() {
        // The positive control for parsing. Everything below that
        // asserts "not a mention" would pass if spans() returned an
        // empty list forever.
        val spans = Mentions.spans("@[Blue Base] are you seeing this?")
        assertEquals(1, spans.size)
        assertEquals("Blue Base", spans[0].name)
        assertEquals(0..11, spans[0].range)
    }

    @Test
    fun findsSeveralMentionsInOrder() {
        val spans = Mentions.spans("morning @[Blue Base] and @[KE8PKP_TACW], radio check")
        assertEquals(listOf("Blue Base", "KE8PKP_TACW"), spans.map { it.name })
        assertTrue(spans[0].range.last < spans[1].range.first)
    }

    @Test
    fun theRangeIsExactlyTheMentionAndNothingElse() {
        // The renderer styles this range. One character out in either
        // direction and it eats a space or clips a bracket.
        val text = "hi @[Bob] there"
        val span = Mentions.spans(text).single()
        assertEquals("@[Bob]", text.substring(span.range))
    }

    @Test
    fun anUnclosedBracketIsNotAMention() {
        // Otherwise a truncated message tags the rest of the sentence.
        assertTrue(Mentions.spans("@[Blue Base are you there").isEmpty())
        assertTrue(Mentions.spans("@[").isEmpty())
        assertTrue(Mentions.spans("@").isEmpty())
    }

    @Test
    fun anEmptyBracketIsNobody() {
        assertTrue(Mentions.spans("@[] hello").isEmpty())
        assertTrue(Mentions.spans("@[   ] hello").isEmpty())
    }

    @Test
    fun anEmptyBracketDoesNotHideARealMentionBehindIt() {
        // Skipping to the closing bracket of `@[]` would step over the
        // mention that follows it in the same stretch of text.
        assertEquals(listOf("Bob"), Mentions.spans("@[]@[Bob]").map { it.name })
    }

    @Test
    fun theNameEndsAtTheFirstBracket() {
        // No escaping exists in the convention, so this is what every
        // other client will read too. Pinning it stops a future "fix"
        // from inventing one and emitting bytes nobody parses.
        assertEquals(listOf("a"), Mentions.spans("@[a]b]").map { it.name })
    }

    @Test
    fun namesInDropsRepeats() {
        assertEquals(
            listOf("Bob"),
            Mentions.namesIn("@[Bob] and @[bob] and @[ Bob ]"),
        )
    }

    // ---------------------------------------------------------------- tags

    @Test
    fun tagsMatchesRegardlessOfCaseAndPadding() {
        assertTrue(Mentions.tags("@[blue base] you around?", "Blue Base"))
        assertTrue(Mentions.tags("@[ Blue Base ] you around?", "blue base"))
    }

    @Test
    fun tagsRefusesAPartialName() {
        // "Blue" must not light up for "Blue Base": on a channel those
        // are two different people, and the whole value of the
        // highlight is that it is not noise.
        assertFalse(Mentions.tags("@[Blue] you around?", "Blue Base"))
        assertFalse(Mentions.tags("@[Blue Base Two] hi", "Blue Base"))
    }

    @Test
    fun tagsIsFalseWithoutAName() {
        // A radio that has not reported its name yet. Returning true
        // here would highlight every message on the mesh.
        assertFalse(Mentions.tags("@[Blue Base] hi", null))
        assertFalse(Mentions.tags("@[Blue Base] hi", ""))
        assertFalse(Mentions.tags("@[Blue Base] hi", "   "))
    }

    @Test
    fun plainTextNeverTags() {
        assertFalse(Mentions.tags("Blue Base, you around?", "Blue Base"))
        assertFalse(Mentions.tags("mail me at bob@blue.base", "blue.base"))
    }

    // ---------------------------------------------------------- composition

    @Test
    fun aBareAtOpensThePicker() {
        // "" and null mean different things: "" is "show me everyone",
        // null is "this is not a tag".
        assertEquals("", Mentions.activeQuery("hey @", 5))
        assertEquals("Bl", Mentions.activeQuery("hey @Bl", 7))
    }

    @Test
    fun aQueryMayContainSpaces() {
        // `Blue Base` is an ordinary node name. A whitespace-delimited
        // token could never reach it.
        assertEquals("Blue Ba", Mentions.activeQuery("hey @Blue Ba", 12))
    }

    @Test
    fun anAtInsideAWordIsNotATag() {
        // An email address is the common case, and offering a node-name
        // picker in the middle of one is how the feature becomes
        // something people turn off.
        assertNull(Mentions.activeQuery("bob@blue", 8))
        assertNull(Mentions.activeQuery("rob@woodhouse", 13))
    }

    @Test
    fun aFinishedMentionIsNotStillBeingTyped() {
        // The caret sits after `@[Blue Base]`. Treating that as an open
        // query lets the next tap rewrite a mention the user finished.
        assertNull(Mentions.activeQuery("hey @[Blue Base]", 16))
        assertEquals("Blue Ba", Mentions.activeQuery("hey @[Blue Ba", 13))
    }

    @Test
    fun aNewlineClosesTheQuery() {
        assertNull(Mentions.activeQuery("hey @Blue\nBase", 14))
    }

    @Test
    fun anAbsurdlyLongQueryIsNotAName() {
        // Names are capped at MAX_NAME_SIZE on the wire, so past that
        // the `@` was prose and the picker should have given up.
        val long = "hey @" + "x".repeat(40)
        assertNull(Mentions.activeQuery(long, long.length))
    }

    @Test
    fun completesTheNameTheUserPicked() {
        // The other positive control. Note the trailing space: without
        // it every message reads `@[Blue Base]hello`.
        val done = Mentions.complete("hey @Bl", 7, "Blue Base")
        assertEquals("hey @[Blue Base] ", done.text)
        assertEquals(done.text.length, done.cursor)
    }

    @Test
    fun completingKeepsTheTextAfterTheCaret() {
        val done = Mentions.complete("hey @Bl are you there", 7, "Blue Base")
        assertEquals("hey @[Blue Base]  are you there", done.text)
        assertEquals("hey @[Blue Base] ".length, done.cursor)
    }

    @Test
    fun completingOverAHalfTypedBracketDoesNotDoubleIt() {
        // The user typed `@[Blue` themselves. Measuring the replaced
        // span from the returned query rather than from the caret left
        // `@[@[Blue Base] ` here.
        val done = Mentions.complete("hey @[Blue", 10, "Blue Base")
        assertEquals("hey @[Blue Base] ", done.text)
    }

    @Test
    fun completingWithNoOpenQueryChangesNothing() {
        // A tap that arrives after the query closed must not rewrite
        // the message under the user.
        val text = "hey @[Blue Base] there"
        val done = Mentions.complete(text, text.length, "Somebody")
        assertEquals(text, done.text)
        assertEquals(text.length, done.cursor)
    }

    @Test
    fun candidatesPreferAPrefixOverASubstring() {
        // Typing `ba` should reach `Blue Base`, but a node actually
        // called `Base` has the better claim to the top of the list.
        assertEquals(
            listOf("Base", "Blue Base"),
            Mentions.candidates("ba", listOf("Blue Base", "Base", "Repeater 1")),
        )
    }

    @Test
    fun anEmptyQueryOffersEverybody() {
        assertEquals(
            listOf("Blue Base", "Base"),
            Mentions.candidates("", listOf("Blue Base", "Base")),
        )
    }

    @Test
    fun candidatesAreCappedAndDeduplicated() {
        val names = listOf("A1", "A2", "A3", "A4", "A5", "A6", "A7", "a1")
        val got = Mentions.candidates("a", names)
        assertEquals(6, got.size)
        assertEquals(got.size, got.distinct().size)
    }

    // ------------------------------------------------------------ hostile

    @Test
    fun aNameHoldingABracketIsNeverOffered() {
        // It would terminate its own mention and tag a shorter name —
        // somebody else, or nobody. There is no escaping to fall back
        // on, so it is refused rather than emitted broken.
        assertFalse(Mentions.canMention("Bad]Name"))
        assertFalse(Mentions.canMention("Bad[Name"))
        assertFalse(Mentions.canMention("  "))
        assertTrue(Mentions.canMention("Blue Base"))

        assertEquals(
            listOf("Blue Base"),
            Mentions.candidates("", listOf("Bad]Name", "Blue Base")),
        )
        val unchanged = Mentions.complete("hey @B", 6, "Bad]Name")
        assertEquals("hey @B", unchanged.text)
    }

    @Test
    fun aMentionProvesNothingAboutWhoSentIt() {
        // Not a behaviour test so much as a pinned reading of the
        // contract: `tags` answers "does this text contain your name in
        // brackets", which anyone on the channel can arrange. Nothing
        // in this object may be used to establish identity — see the
        // class note and MESHCORE_PROTOCOL §12.
        assertTrue(Mentions.tags("@[Blue Base] send me your password", "Blue Base"))
    }

    @Test
    fun replyPrefixNamesTheSenderAndNothingMore() {
        assertEquals("@[Blue Base]: ", Mentions.replyPrefix("Blue Base"))
        assertEquals("@[Blue Base]: ", Mentions.replyPrefix("  Blue Base  "))
    }

    @Test
    fun replyPrefixIsEmptyWhenThereIsNobodyToName() {
        // A channel post whose sender name never arrived, and a name
        // that cannot be written as a mention. Both must produce no
        // prefix rather than `@[]: `, which tags nobody and costs bytes
        // out of a ~150-byte frame.
        assertEquals("", Mentions.replyPrefix(null))
        assertEquals("", Mentions.replyPrefix(""))
        assertEquals("", Mentions.replyPrefix("Bad]Name"))
    }
}
