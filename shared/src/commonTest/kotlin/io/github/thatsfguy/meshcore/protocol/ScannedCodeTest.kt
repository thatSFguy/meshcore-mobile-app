package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
