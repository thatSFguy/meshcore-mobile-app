package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Working out what a scanned QR is, so the user does not have to.
 *
 * The bug: scanning a repeater's contact QR from the Chats "+" button
 * answered "Invalid community code", because that button was wired
 * straight to the community JSON parser. The code was fine — the app
 * was complaining about its own screen layout.
 */
class ScannedCodeTest {

    @Test
    fun `a contact card scanned anywhere is recognised as a MeshCore URI`() {
        val repeater = ShareUri.encodeContact("KCEST-GRR-DOWNTOWN-02", "9e5efdafeeec", 2)
        assertEquals(ScannedCode.MeshCoreUri, ScannedCode.classify(repeater))
    }

    @Test
    fun `a channel key is a MeshCore URI too`() {
        val channel = ShareUri.encodeChannel("Public", "00".repeat(16))
        assertEquals(ScannedCode.MeshCoreUri, ScannedCode.classify(channel))
    }

    @Test
    fun `a community invite is recognised without parsing it`() {
        val community = """{"type":"meshcore_community","v":1,"name":"GR","k":"AAAA"}"""
        assertEquals(ScannedCode.Community, ScannedCode.classify(community))
    }

    @Test
    fun `whitespace around a code does not change what it is`() {
        val community = "\n  {\"type\":\"meshcore_community\",\"v\":1}  \n"
        assertEquals(ScannedCode.Community, ScannedCode.classify(community))
        assertEquals(
            ScannedCode.MeshCoreUri,
            ScannedCode.classify("  " + ShareUri.encodeContact("a", "ab", 0) + "  "),
        )
    }

    @Test
    fun `the scheme is matched case-insensitively`() {
        assertEquals(ScannedCode.MeshCoreUri, ScannedCode.classify("MESHCORE://contact/add?n=a"))
    }

    @Test
    fun `anything else is unknown rather than mis-routed`() {
        // These must NOT be handed to the community parser, which is
        // what produced "Invalid community code" for unrelated input.
        for (text in listOf(
            "",
            "   ",
            "https://example.com",
            "just some text",
            """{"type":"something_else"}""",
            "{not json at all",
        )) {
            assertEquals(ScannedCode.Unknown, ScannedCode.classify(text), "misclassified: $text")
        }
    }

    @Test
    fun `a JSON blob without the marker is not treated as a community`() {
        assertEquals(ScannedCode.Unknown, ScannedCode.classify("""{"v":1,"name":"GR"}"""))
    }
    // ------------------------------------------------------------------
    // extract() — pasted text, which is never just the code
    // ------------------------------------------------------------------

    @Test
    fun aBareCodeComesBackUnchanged() {
        val uri = "meshcore://channel/add?name=KCEST&secret=98b6a0616fecd19801d949bde368f87e"
        assertEquals(uri, ScannedCode.extract(uri))
        assertEquals(uri, ScannedCode.extract("  $uri\n"))
    }

    @Test
    fun aLinkIsFoundInsideASentence() {
        // The case this exists for. Liam Cottle's client shares a
        // contact by copying a meshcore:// link to the clipboard — it
        // renders no QR at all — and by the time it reaches someone it
        // is usually inside a message.
        val uri = "meshcore://contact/add?name=Blue&public_key=" + "9c".repeat(32) + "&type=1"
        assertEquals(uri, ScannedCode.extract("here you go $uri thanks"))
        assertEquals(uri, ScannedCode.extract("Join us:\n$uri\n\nsee you there"))
    }

    @Test
    fun theSchemeIsMatchedCaseInsensitively() {
        val uri = "MESHCORE://channel/add?name=x&secret=" + "ab".repeat(16)
        assertEquals(uri, ScannedCode.extract("try $uri"))
    }

    @Test
    fun textWithNoCodeIsNull() {
        for (junk in listOf("", "   ", "hello", "https://example.com", "meshcore", "mesh://x")) {
            assertNull(ScannedCode.extract(junk), "found a code in \"$junk\"")
        }
    }

    @Test
    fun onlyWhitespaceEndsTheLink() {
        // Deliberately NOT trimming trailing punctuation. A
        // percent-encoded name can end in almost anything, so stripping
        // a "." or ")" to be helpful would corrupt codes that were fine.
        // A link pasted with the sentence's full stop attached fails
        // visibly, which is the better failure.
        val uri = "meshcore://channel/add?name=x&secret=" + "ab".repeat(16)
        assertEquals("$uri.", ScannedCode.extract("$uri."))
        assertEquals(uri, ScannedCode.extract("$uri "))
    }

    @Test
    fun proseAfterALinkThatCameFirstIsStillTrimmed() {
        // The commonest paste there is: the link, then the sentence.
        // extract() used to return the whole string the moment it
        // STARTED with the scheme, so the link-in-prose logic never ran
        // for this shape — the trailing words landed in the last
        // parameter's value and the decoder reported "Malformed contact
        // code", which reads as the sender's code being broken.
        val uri = "meshcore://contact/add?name=Blue&public_key=" + "9c".repeat(32) + "&type=1"
        assertEquals(uri, ScannedCode.extract("$uri — join us"))
        assertEquals(uri, ScannedCode.extract("$uri\nsee you there"))

        // And the whole point: what comes back decodes.
        val decoded = ShareUri.decode(ScannedCode.extract("$uri — join us")!!)
        assertTrue(decoded is ShareUri.Decoded.Contact, "decoded as $decoded")
        assertEquals(1, decoded.type)
    }

    @Test
    fun aCommunityBlobStillPassesThrough() {
        // extract() must not break the other code shape: community JSON
        // is not a URI and has no scheme to search for.
        val blob = "{\"kind\":\"meshcore_community\",\"name\":\"x\"}"
        assertEquals(blob, ScannedCode.extract(blob))
        assertEquals(ScannedCode.Community, ScannedCode.classify(blob))
    }

    @Test
    fun whatExtractReturnsIsAlwaysSomethingClassifyAccepts() {
        // The contract the caller relies on: extract hands its result
        // straight to the same dispatcher a scan uses.
        val samples = listOf(
            "meshcore://contact/add?name=A&public_key=" + "9c".repeat(32) + "&type=1",
            "look: meshcore://channel/add?name=x&secret=" + "ab".repeat(16),
            "{\"kind\":\"meshcore_community\"}",
        )
        for (sample in samples) {
            val code = ScannedCode.extract(sample)
            assertNotNull(code, "nothing extracted from $sample")
            assertNotEquals(
                ScannedCode.Unknown,
                ScannedCode.classify(code),
                "extract returned something classify rejects: $code",
            )
        }
    }
}
