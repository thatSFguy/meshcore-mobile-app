package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    /*
     * Replies below are what the firmware prints, not what we'd like it to.
     * `region` is RegionMap::printChildRegions (src/helpers/RegionMap.cpp):
     * one space per level, `^` on the home region, ` F` when flood is
     * allowed — identical from v1.10.0 to main. The parser these replaced
     * read `-> name (parent) 'F'`, which no firmware ever printed, and its
     * tests pinned that guess; a live tree answered "not recognised".
     */

    /** The tree the operator built with `region def`, 2026-09-26. */
    private val truckTree = "* F\n midwest F\n  mi F\n   mi-west F\n    grr F\n"

    @Test
    fun theRegionTreeParsesAsTheFirmwarePrintsIt() {
        val tree = assertNotNull(Regions.parseRegionTree(truckTree))
        assertTrue(tree.wildcardFloodAllowed)
        assertEquals(false, tree.truncated)
        assertEquals(
            listOf(
                Regions.RegionEntry("midwest", "*", floodAllowed = true, depth = 1),
                Regions.RegionEntry("mi", "midwest", floodAllowed = true, depth = 2),
                Regions.RegionEntry("mi-west", "mi", floodAllowed = true, depth = 3),
                Regions.RegionEntry("grr", "mi-west", floodAllowed = true, depth = 4),
            ),
            tree.regions,
        )
    }

    @Test
    fun aMissingFIsDeniedAndACaretIsHome() {
        val tree = assertNotNull(Regions.parseRegionTree("*\n mi^ F\n  grr\n"))
        // `region denyf *`: the wildcard line loses its F.
        assertEquals(false, tree.wildcardFloodAllowed)
        assertEquals(Regions.RegionEntry("mi", "*", floodAllowed = true, depth = 1, home = true), tree.regions[0])
        // No F is denied — absence is not "allowed".
        assertEquals(Regions.RegionEntry("grr", "mi", floodAllowed = false, depth = 2), tree.regions[1])
    }

    @Test
    fun siblingsAndABranchBackUpFindTheirParents() {
        // `region def a b|* c d|b e` style: indentation going back up.
        val tree = assertNotNull(Regions.parseRegionTree("* F\n mi F\n  grr F\n  lansing F\n oh F\n  cle F"))
        assertEquals(
            listOf("mi" to "*", "grr" to "mi", "lansing" to "mi", "oh" to "*", "cle" to "oh"),
            tree.regions.map { it.name to it.parent },
        )
    }

    @Test
    fun aNodeWithNoRegionsIsAnEmptyTreeNotAnUnknownOne() {
        val tree = assertNotNull(Regions.parseRegionTree("* F"))
        assertEquals(emptyList(), tree.regions)
        assertTrue(tree.wildcardFloodAllowed)
    }

    @Test
    fun anythingNotShapedLikeTheTreeIsUnrecognised() {
        // Firmware without regions answers "??: region"; that must not be
        // read as "this node has no regions".
        assertNull(Regions.parseRegionTree("??: region"))
        assertNull(Regions.parseRegionTree("Err - ??"))
        assertNull(Regions.parseRegionTree(""))
        assertNull(Regions.parseRegionTree(null))
        // The old guessed shape is not a tree either.
        assertNull(Regions.parseRegionTree("-> bayarea (*) 'F'"))
        // A region two levels below its predecessor has no parent to hang on.
        assertNull(Regions.parseRegionTree("* F\n mi F\n   grr F"))
        // A child with no wildcard root above it.
        assertNull(Regions.parseRegionTree(" mi F"))
        // A space inside a name is two tokens, not a name.
        assertNull(Regions.parseRegionTree("* F\n bay area F"))
    }

    @Test
    fun oldFirmwareHashPrefixesAreDropped() {
        // Before v1.12 printChildRegions printed the stored name, '#' and all.
        val tree = assertNotNull(Regions.parseRegionTree("* F\n #mi F"))
        assertEquals("mi", tree.regions.single().name)
    }

    @Test
    fun aFullReplyBufferIsTruncatedAndItsCutLineDropped() {
        // exportTo(reply, 160): the tree stops wherever the buffer ends,
        // which can be mid-name. "grr" cut to "gr" must not appear as a
        // region called "gr".
        // Lines of " rgn-NN F" run past 159 bytes; the cut lands
        // inside a name, leaving a line that would parse as a region
        // that doesn't exist.
        val names = (10..35).map { "rgn-$it" }
        val full = "* F\n" + names.joinToString("") { " $it F\n" }
        val cut = full.take(159)
        val lastLine = cut.substringAfterLast('\n')
        assertTrue(lastLine.isNotEmpty() && !lastLine.endsWith(" F"), "fixture must cut mid-line: '$lastLine'")

        val tree = assertNotNull(Regions.parseRegionTree(cut))
        assertTrue(tree.truncated)
        // Every region shown is a real one, whole, and the cut one is gone.
        assertEquals(names.take(tree.regions.size), tree.regions.map { it.name })
        assertEquals(cut.count { it == '\n' } - 1, tree.regions.size)
    }

    @Test
    fun aShortReplyIsNotTruncated() {
        assertEquals(false, assertNotNull(Regions.parseRegionTree(truckTree)).truncated)
    }

    @Test
    fun aNameTheFirmwareHoldsButWeWouldRewriteIsShownNotActedOn() {
        // Set from the console as "MI": its scope hash differs from "mi",
        // and canonicalising it for a command would address another region.
        val tree = assertNotNull(Regions.parseRegionTree("* F\n MI F\n mi F"))
        assertEquals(listOf("MI", "mi"), tree.regions.map { it.name })
        assertEquals(listOf(false, true), tree.regions.map { it.actionable })
    }

    @Test
    fun defaultScopeIsReadFromTheFirmwaresSentence() {
        // CommonCLI::handleRegionCmd: " default scope is %s".
        assertEquals("mi", Regions.parseDefaultScope(" default scope is mi"))
        assertEquals("mi", Regions.parseDefaultScope(" default scope is now mi"))
        // No default is "<null>", which is the global scope: untagged floods.
        assertEquals("*", Regions.parseDefaultScope(" default scope is <null>"))
        assertEquals("*", Regions.parseDefaultScope(" default scope is now <null>"))
    }

    @Test
    fun anUnrecognisedDefaultScopeIsUnknownNotCleared() {
        // Null is "unknown", never "" or "*": every word in "??: region
        // default" is itself a valid region name.
        assertNull(Regions.parseDefaultScope("??: region default"))
        assertNull(Regions.parseDefaultScope("> mi"))
        assertNull(Regions.parseDefaultScope("Err - region table full"))
        assertNull(Regions.parseDefaultScope(""))
        assertNull(Regions.parseDefaultScope(null))
    }

    // ------------------------------------------------------------------
    // Command builders
    // ------------------------------------------------------------------

    @Test
    fun buildersProduceTheFirmwareCommandStrings() {
        assertEquals("region", Regions.tree())
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
