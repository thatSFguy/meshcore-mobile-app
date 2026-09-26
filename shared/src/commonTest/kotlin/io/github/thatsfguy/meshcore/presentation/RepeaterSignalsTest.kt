package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.presentation.RepeaterSignals.RouteSample
import io.github.thatsfguy.meshcore.presentation.RepeaterSignals.Rules
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepeaterSignalsTest {

    // A real route from this mesh's log (2026-09-25), 2-byte hashes.
    private val realPath = "4c467bf618699fe80735b389"
    private val kentHill = "7bf6aa11223344556677889900aabbccddeeff00112233445566778899aabbcc"
    private val sparta = "b389548d314a0000000000000000000000000000000000000000000000000000"

    @Test
    fun `a real route splits into its hops at the mesh's width`() {
        assertEquals(
            listOf("4c46", "7bf6", "1869", "9fe8", "0735", "b389"),
            RepeaterSignals.hopHashes(realPath, 2),
        )
    }

    @Test
    fun `a malformed route yields nothing rather than a guess`() {
        assertTrue(RepeaterSignals.hopHashes("4c467", 2).isEmpty())
        assertTrue(RepeaterSignals.hopHashes("zz46", 2).isEmpty())
        assertTrue(RepeaterSignals.hopHashes(realPath, 0).isEmpty())
        assertTrue(RepeaterSignals.hopHashes(realPath, 4).isEmpty())
        assertTrue(RepeaterSignals.hopHashes("", 2).isEmpty())
    }

    @Test
    fun `a hash is counted once per message`() {
        val counts = RepeaterSignals.relayCounts(
            listOf(
                RouteSample("7bf67bf6", 2),
                RouteSample(realPath, 2),
            ),
        )
        assertEquals(2, counts["7bf6"])
        assertEquals(1, counts["b389"])
    }

    private fun routes(n: Int) = RepeaterSignals.relayCounts(List(n) { RouteSample(realPath, 2) })

    @Test
    fun `a repeater in your routes is credited with them`() {
        val s = RepeaterSignals.signalsFor(kentHill, minHops = 3, counts = routes(3), otherKnownKeys = listOf(sparta))
        assertEquals(3, s.relayed)
        assertTrue(s.provenRelay)
        assertFalse(s.heardDirect)
        assertEquals("relayed 3 messages you received", RepeaterSignals.describe(s))
    }

    @Test
    fun `fewer than three relays is not a pattern`() {
        // The Wisconsin case: one or two hits on a route are not enough
        // to call a repeater yours — or to add it.
        val two = RepeaterSignals.signalsFor(kentHill, minHops = 3, counts = routes(2), otherKnownKeys = emptyList())
        assertEquals(2, two.relayed)
        assertFalse(two.provenRelay)
        assertNull(RepeaterSignals.describe(two))
        assertFalse(RepeaterSignals.matches(two, Rules(relaysMyTraffic = true, heardDirect = false)))
        // ...and exactly the threshold is.
        val three = RepeaterSignals.signalsFor(kentHill, minHops = 3, counts = routes(3), otherKnownKeys = emptyList())
        assertTrue(three.provenRelay)
        assertEquals(RepeaterSignals.MIN_RELAYS, 3)
    }

    @Test
    fun `a shared hash is credited to no one`() {
        // Another known node answers to 7bf6 too: the relays could be its.
        val twin = "7bf6ff00000000000000000000000000000000000000000000000000000000ff"
        val s = RepeaterSignals.signalsFor(kentHill, minHops = 2, counts = routes(3), otherKnownKeys = listOf(twin))
        assertTrue(s.relayAmbiguous)
        assertFalse(s.provenRelay)
        assertFalse(RepeaterSignals.matches(s, Rules(relaysMyTraffic = true, heardDirect = false)))
        assertEquals(
            "its route hash is shared, so relays can't be credited",
            RepeaterSignals.describe(s),
        )
    }

    @Test
    fun `zero hops is heard direct and nothing else is`() {
        val none = emptyMap<String, Int>()
        assertTrue(RepeaterSignals.signalsFor(kentHill, 0, none, emptyList()).heardDirect)
        assertFalse(RepeaterSignals.signalsFor(kentHill, 1, none, emptyList()).heardDirect)
        // Never recorded is not direct.
        assertFalse(RepeaterSignals.signalsFor(kentHill, null, none, emptyList()).heardDirect)
    }

    @Test
    fun `the rules add only what they name`() {
        val direct = RepeaterSignals.Signals(relayed = 0, relayAmbiguous = false, heardDirect = true)
        val relaying = RepeaterSignals.Signals(relayed = 4, relayAmbiguous = false, heardDirect = false)
        val neither = RepeaterSignals.Signals(relayed = 0, relayAmbiguous = false, heardDirect = false)
        val onlyRelay = Rules(relaysMyTraffic = true, heardDirect = false)
        val onlyDirect = Rules(relaysMyTraffic = false, heardDirect = true)
        assertTrue(RepeaterSignals.matches(relaying, onlyRelay))
        assertFalse(RepeaterSignals.matches(direct, onlyRelay))
        assertTrue(RepeaterSignals.matches(direct, onlyDirect))
        assertFalse(RepeaterSignals.matches(relaying, onlyDirect))
        assertFalse(RepeaterSignals.matches(neither, Rules(relaysMyTraffic = true, heardDirect = true)))
        // The positive control for "nothing switched on adds nothing".
        assertFalse(RepeaterSignals.matches(relaying, Rules(relaysMyTraffic = false, heardDirect = false)))
    }

    @Test
    fun `a node with nothing to show says nothing`() {
        assertNull(RepeaterSignals.describe(RepeaterSignals.Signals(0, false, false)))
        // One relay is below the threshold, so only "heard direct" is said.
        assertEquals("heard direct", RepeaterSignals.describe(RepeaterSignals.Signals(1, false, true)))
        assertEquals(
            "heard direct · relayed 4 messages you received",
            RepeaterSignals.describe(RepeaterSignals.Signals(4, false, true)),
        )
    }

    // --- the auto-add pass ------------------------------------------------

    private val repeaterDirect = RepeaterSignals.Candidate(kentHill, Codes.ADV_TYPE_REPEATER, 0)
    private val repeaterFar = RepeaterSignals.Candidate(sparta, Codes.ADV_TYPE_REPEATER, 4)
    private val chatDirect = RepeaterSignals.Candidate("cc" + "0".repeat(62), Codes.ADV_TYPE_CHAT, 0)
    private val radioOff = Codes.AUTO_ADD_CHAT          // repeaters bit clear
    private val directRule = Rules(relaysMyTraffic = false, heardDirect = true)

    private fun plan(
        rules: Rules = directRule,
        flags: Int? = radioOff,
        full: Boolean = false,
        tried: Set<String> = emptySet(),
        counts: Map<String, Int> = emptyMap(),
    ) = RepeaterSignals.toAutoAdd(
        candidates = listOf(repeaterDirect, repeaterFar, chatDirect),
        rules = rules,
        radioAutoAddFlags = flags,
        contactsFull = full,
        counts = counts,
        knownKeys = listOf(kentHill, sparta, chatDirect.keyHex),
        tried = tried,
    )

    @Test
    fun `a switched-on rule adds the repeaters it names and nothing else`() {
        // The positive control: without it, every "adds nothing" below
        // would pass against a pass that never adds anything.
        assertEquals(listOf(kentHill), plan())
        // A chat node heard direct is not a repeater.
        val relayRule = Rules(relaysMyTraffic = true, heardDirect = false)
        // sparta's b389 is in the route; kentHill's 7bf6 is too.
        assertEquals(listOf(kentHill, sparta), plan(rules = relayRule, counts = routes(3)))
        assertTrue(plan(rules = relayRule, counts = routes(2)).isEmpty())
    }

    @Test
    fun `nothing is added while the radio adds repeaters itself`() {
        assertTrue(plan(flags = Codes.AUTO_ADD_REPEATER or Codes.AUTO_ADD_CHAT).isEmpty())
    }

    @Test
    fun `nothing is added before the radio's policy is known`() {
        assertTrue(plan(flags = null).isEmpty())
    }

    @Test
    fun `nothing is added to a full radio`() {
        assertTrue(plan(full = true).isEmpty())
    }

    @Test
    fun `nothing is added with no rule on`() {
        assertTrue(plan(rules = Rules(relaysMyTraffic = false, heardDirect = false)).isEmpty())
    }

    @Test
    fun `a repeater that failed once is not tried again`() {
        assertTrue(plan(tried = setOf(kentHill)).isEmpty())
    }
}
