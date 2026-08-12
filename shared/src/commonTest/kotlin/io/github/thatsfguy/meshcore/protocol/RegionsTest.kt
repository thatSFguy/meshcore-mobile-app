package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Region naming, the discovery-reply parser, and the `region …` CLI
 * builders (PARITY §8).
 *
 * Two of these carry real weight: region names arrive off the mesh, and
 * a name is pasted straight into a CLI line sent to a repeater. So the
 * hostile cases below — truncated UTF-8, NUL padding, newlines,
 * over-long names, an unbounded list — are the point, not decoration.
 */
class RegionsTest {

    // ------------------------------------------------------------------
    // Canonical names
    // ------------------------------------------------------------------

    @Test
    fun canonicalAcceptsTheEcosystemNameShape() {
        assertEquals("bayarea", Regions.canonical("bayarea"))
        assertEquals("bay-area-2", Regions.canonical("bay-area-2"))
        assertEquals("a", Regions.canonical("a"))
        assertEquals("-", Regions.canonical("-"))
        assertEquals("0", Regions.canonical("0"))
    }

    @Test
    fun canonicalStripsTheHashAndSurroundingSpace() {
        // The flood-scope hash re-adds the '#', so storing it would
        // double it and produce a different scope on the air.
        assertEquals("bayarea", Regions.canonical("#bayarea"))
        assertEquals("bayarea", Regions.canonical("  #bayarea  "))
        assertEquals("bayarea", Regions.canonical(" bayarea\t"))
    }

    @Test
    fun canonicalLowercasesSoOneRegionIsNotTwo() {
        // The scope is SHA256 over the exact bytes: "#BayArea" and
        // "#bayarea" are different regions on the air. Everyone else
        // writes lowercase, so that is what we send.
        assertEquals("bayarea", Regions.canonical("BayArea"))
        assertEquals("bayarea", Regions.canonical("#BAYAREA"))
    }

    @Test
    fun canonicalRejectsAnythingThatIsNotAName() {
        for (bad in listOf(
            null, "", "  ", "#", "##bayarea",
            "bay area",            // space
            "bay_area",            // underscore
            "bay.area",            // dot
            "bay/area", "bay:area", "bay,area",
            "bay\narea",           // newline — CLI injection
            "bay\tarea",
            "b".repeat(31),        // one over the limit
            "régions",             // non-ASCII
            "�",              // UTF-8 replacement char (from a bad decode)
        )) {
            assertNull(Regions.canonical(bad), "should have rejected: $bad")
            assertTrue(!Regions.isValid(bad), "isValid should be false for: $bad")
        }
    }

    @Test
    fun canonicalAcceptsExactlyTheMaximumLength() {
        val max = "b".repeat(Regions.MAX_NAME_LENGTH)
        assertEquals(max, Regions.canonical(max))
        assertNull(Regions.canonical("b".repeat(Regions.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun theMaximumIsTheFirmwaresTwentyNineBytes() {
        // Pinned to the number, not to our own constant: MeshCore's
        // region-filtering documentation says "maximum 29 _bytes_
        // (UTF-8)". This was 30, and one over is not cosmetic — a
        // 30-character name canonicalises, gets hashed into a flood
        // scope, and is then refused by `region put`, so the scope
        // looks set on the phone and routes nothing on the air.
        assertEquals(29, Regions.MAX_NAME_LENGTH)
        assertEquals("b".repeat(29), Regions.canonical("b".repeat(29)))
        assertNull(Regions.canonical("b".repeat(30)))
        // The charset is ASCII-only, so the byte bound and the
        // character bound are the same count — that is what makes
        // expressing it in characters safe here and nowhere else.
        assertEquals(29, "b".repeat(29).encodeToByteArray().size)
    }

    @Test
    fun selectorAllowsTheGlobalWildcardAndNothingElseExotic() {
        assertEquals("*", Regions.canonicalSelector("*"))
        assertEquals("*", Regions.canonicalSelector("  *  "))
        assertEquals("bayarea", Regions.canonicalSelector("#BayArea"))
        // A partial wildcard is not a selector we know how to mean.
        assertNull(Regions.canonicalSelector("bay*"))
        assertNull(Regions.canonicalSelector("**"))
        assertNull(Regions.canonicalSelector(null))
        assertNull(Regions.canonicalSelector(""))
    }

    // ------------------------------------------------------------------
    // Discovery replies — attacker-controlled bytes off the mesh
    // ------------------------------------------------------------------

    private fun body(header: ByteArray = ByteArray(4), names: String): ByteArray =
        header + names.encodeToByteArray()

    @Test
    fun discoveryResponseParsesACommaSeparatedList() {
        val parsed = Regions.parseDiscoveryResponse(body(names = "bayarea,socal,sierra"))
        assertEquals(listOf("bayarea", "sierra", "socal"), parsed)
    }

    @Test
    fun discoveryResponseStripsNulPadding() {
        // The list is NUL-padded to the slot width. NUL is not
        // whitespace, so a trim alone would leave it and every padded
        // name would fail validation.
        val parsed = Regions.parseDiscoveryResponse(
            body(names = "bayarea\u0000\u0000,socal\u0000"),
        )
        assertEquals(listOf("bayarea", "socal"), parsed)
    }

    @Test
    fun discoveryResponseDropsNamesItCannotCanonicalise() {
        val parsed = Regions.parseDiscoveryResponse(
            body(names = "bayarea,bay area,,socal,bay/area,${"x".repeat(40)},SIERRA"),
        )
        // The over-long and space/slash-bearing names are gone; the
        // uppercase one is folded to its canonical form.
        assertEquals(listOf("bayarea", "sierra", "socal"), parsed)
    }

    @Test
    fun discoveryResponseSurvivesMalformedUtf8() {
        // A truncated multi-byte sequence must not take the parse down.
        val broken = byteArrayOf(0xF0.toByte(), 0x9F.toByte()) // dangling 4-byte lead
        val payload = ByteArray(4) + "bayarea,".encodeToByteArray() + broken +
            ",socal".encodeToByteArray()
        val parsed = Regions.parseDiscoveryResponse(payload)
        assertEquals(listOf("bayarea", "socal"), parsed)
    }

    @Test
    fun discoveryResponseHandlesShortAndEmptyBodies() {
        assertEquals(emptyList(), Regions.parseDiscoveryResponse(ByteArray(0)))
        for (n in 1..Regions.DISCOVERY_BODY_HEADER) {
            assertEquals(
                emptyList(),
                Regions.parseDiscoveryResponse(ByteArray(n)),
                "body of $n bytes should yield nothing",
            )
        }
    }

    @Test
    fun discoveryResponseDeduplicatesAndCapsTheList() {
        val many = (1..500).joinToString(",") { "region-$it" }
        val parsed = Regions.parseDiscoveryResponse(body(names = "$many,region-1,region-1"))
        assertEquals(Regions.MAX_DISCOVERED, parsed.size)
        assertEquals(parsed.distinct(), parsed)
        // A hostile node cannot make the list unbounded.
        assertTrue(parsed.all { Regions.isValid(it) })
    }

    @Test
    fun discoveryResponseOfPureGarbageYieldsNothing() {
        val garbage = ByteArray(4) + ByteArray(120) { 0xFF.toByte() }
        assertEquals(emptyList(), Regions.parseDiscoveryResponse(garbage))
    }

    // ------------------------------------------------------------------
    // CLI reply parsing
    // ------------------------------------------------------------------

    @Test
    fun regionListingParsesTheDocumentedReplyShape() {
        val reply = """
            -> bayarea (*) 'F'
            -> peninsula (bayarea) 'F'
            -> sierra (*)
        """.trimIndent()
        val entries = Regions.parseRegionListing(reply)
        assertEquals(3, entries.size)
        assertEquals(Regions.RegionEntry("bayarea", "*", floodAllowed = true), entries[0])
        assertEquals(Regions.RegionEntry("peninsula", "bayarea", floodAllowed = true), entries[1])
        // No 'F' means flood is not permitted — absence is not "allowed".
        assertEquals(Regions.RegionEntry("sierra", "*", floodAllowed = false), entries[2])
    }

    @Test
    fun regionListingIgnoresLinesItDoesNotUnderstand() {
        // Firmware without region support answers "??: region"; that must
        // not be mistaken for "this node has no regions".
        assertEquals(emptyList(), Regions.parseRegionListing("??: region"))
        assertEquals(emptyList(), Regions.parseRegionListing(""))
        assertEquals(emptyList(), Regions.parseRegionListing(null))
        assertEquals(emptyList(), Regions.parseRegionListing("ERROR: not supported"))
    }

    @Test
    fun regionListingDropsUnparseableNamesAndDuplicates() {
        val reply = """
            -> bayarea (*) 'F'
            -> bay area (*) 'F'
            -> bayarea (*)
        """.trimIndent()
        val entries = Regions.parseRegionListing(reply)
        // "bay area" isn't a name; the repeated "bayarea" keeps the first.
        assertEquals(1, entries.size)
        assertTrue(entries[0].floodAllowed)
    }

    @Test
    fun regionNamesReadsOnlyReplyShapedLines() {
        assertEquals(
            listOf("bayarea", "socal"),
            Regions.parseRegionNames("> bayarea, socal"),
        )
        assertEquals(
            listOf("bayarea", "socal"),
            Regions.parseRegionNames("-> 'bayarea'\n-> 'socal'"),
        )
        // The killer case: tokenising everything would read this error
        // reply as three regions — "region", "list" and "allowed" are
        // all valid names.
        assertEquals(emptyList(), Regions.parseRegionNames("??: region list allowed"))
        assertEquals(emptyList(), Regions.parseRegionNames("bayarea, socal"))
        assertEquals(emptyList(), Regions.parseRegionNames("ERROR: unsupported"))
    }

    @Test
    fun defaultScopeReplyIsParsedOrReportedUnknown() {
        assertEquals("bayarea", Regions.parseDefaultScope("> bayarea"))
        assertEquals("bayarea", Regions.parseDefaultScope("-> bayarea"))
        assertEquals("bayarea", Regions.parseDefaultScope("-> 'bayarea'"))
        assertEquals("*", Regions.parseDefaultScope("-> *"))
        // Unrecognised must be null ("unknown"), never "" ("cleared") and
        // never a name scavenged out of the error text: every word in
        // "??: region default" is itself a valid region name.
        assertNull(Regions.parseDefaultScope("??: region default"))
        assertNull(Regions.parseDefaultScope("default: bayarea"))
        assertNull(Regions.parseDefaultScope(""))
        assertNull(Regions.parseDefaultScope(null))
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    @Test
    fun buildersProduceTheFirmwareCommandStrings() {
        assertEquals("region get *", Regions.get("*"))
        assertEquals("region get bayarea", Regions.get("#BayArea"))
        assertEquals("region put bayarea *", Regions.put("bayarea"))
        assertEquals("region put peninsula bayarea", Regions.put("peninsula", "bayarea"))
        assertEquals("region remove bayarea", Regions.remove("bayarea"))
        assertEquals("region allowf bayarea", Regions.allowFlood("bayarea"))
        assertEquals("region denyf bayarea", Regions.denyFlood("bayarea"))
        assertEquals("region home", Regions.home())
        assertEquals("region home bayarea", Regions.setHome("bayarea"))
        assertEquals("region default", Regions.default())
        assertEquals("region default bayarea", Regions.setDefault("bayarea"))
        assertEquals("region default <null>", Regions.setDefault(null))
        assertEquals("region list allowed", Regions.listAllowed())
        assertEquals("region list denied", Regions.listDenied())
        assertEquals("region save", Regions.save())
    }

    @Test
    fun buildersRefuseNamesThatWouldInjectAnotherCommand() {
        // `region load` puts the node into a mode where each following
        // line is a region name, so a newline in a name is a second
        // command, not a formatting nuisance.
        for (bad in listOf(
            "bay\nregion remove bayarea",
            "bayarea region remove x",
            "bay area",
            "",
            "#",
            "b".repeat(31),
        )) {
            assertFailsWith<IllegalArgumentException>("accepted: $bad") { Regions.put(bad) }
            assertFailsWith<IllegalArgumentException>("accepted: $bad") { Regions.remove(bad) }
            assertFailsWith<IllegalArgumentException>("accepted: $bad") { Regions.allowFlood(bad) }
            assertFailsWith<IllegalArgumentException>("accepted: $bad") { Regions.get(bad) }
        }
    }

    @Test
    fun everyBuiltCommandIsASingleLine() {
        val commands = listOf(
            Regions.get("*"), Regions.put("bayarea", "*"), Regions.remove("bayarea"),
            Regions.allowFlood("*"), Regions.denyFlood("*"), Regions.home(),
            Regions.setHome("bayarea"), Regions.default(), Regions.setDefault("bayarea"),
            Regions.setDefault(null), Regions.listAllowed(), Regions.listDenied(),
            Regions.save(),
        )
        for (c in commands) {
            assertTrue(!c.contains('\n') && !c.contains('\r'), "multi-line command: $c")
            assertTrue(c.startsWith("region "), "not a region command: $c")
        }
    }

    @Test
    fun aParentIsNeverSilentlyWidenedToTheGlobalScope() {
        // Falling back to "*" on a bad parent would attach a region to
        // the widest scope there is — always an explicit failure instead.
        assertFailsWith<IllegalArgumentException> { Regions.put("bayarea", "bay area") }
        assertFailsWith<IllegalArgumentException> { Regions.put("bayarea", "") }
    }

    @Test
    fun canonicalRoundTripsThroughEveryBuilder() {
        // Whatever canonical() accepts, the builders must accept too —
        // otherwise the UI would offer names it cannot then send.
        for (name in listOf("a", "-", "0", "bay-area-2", "b".repeat(Regions.MAX_NAME_LENGTH))) {
            assertEquals("region put $name *", Regions.put(name))
            assertEquals("region remove $name", Regions.remove(name))
        }
    }

    // ------------------------------------------------------------------
    // Captured from hardware (2026-08-01)
    // ------------------------------------------------------------------

    @Test
    fun theRealGlobalScopeOnlyReplyIsRecognised() {
        // Verbatim body from a live repeater's answer to an anonymous
        // regions request: a 4-byte header, then '*' NUL-padded. The
        // node HAS answered — it just uses the global scope — and that
        // must not be reported as silence.
        val body = byteArrayOf(
            0x00, 0x8c.toByte(), 0x6e, 0x6a,      // header
            0x2a,                                  // '*'
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        assertTrue(Regions.isGlobalScopeOnly(body))
        // And it yields no NAMES, because '*' is not a region name.
        assertEquals(emptyList(), Regions.parseDiscoveryResponse(body))
    }

    @Test
    fun aRealNamedListIsNotMistakenForGlobalScope() {
        val body = ByteArray(4) + "bayarea,socal".encodeToByteArray()
        assertTrue(!Regions.isGlobalScopeOnly(body))
        assertEquals(listOf("bayarea", "socal"), Regions.parseDiscoveryResponse(body))
    }

    @Test
    fun anEmptyOrShortBodyIsNotGlobalScope() {
        // "Didn't answer" must not masquerade as "answered with global".
        assertTrue(!Regions.isGlobalScopeOnly(ByteArray(0)))
        assertTrue(!Regions.isGlobalScopeOnly(ByteArray(4)))
    }
}
