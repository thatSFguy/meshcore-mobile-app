package io.github.thatsfguy.meshcore.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The neighbour reading, kept and drawn.
 *
 * The tests that carry this file are the ones about TIME. Everything
 * else here is arithmetic that fails loudly; a reading that quietly
 * keeps claiming "heard 4 minutes ago" the next morning does not.
 */
class NeighbourLinksTest {

    private val noon = 1_770_000_000_000L   // an arbitrary fixed local clock

    private fun record(
        prefix: String = "aabbccddeeff",
        snr: Double = 3.0,
        heardSecondsAgo: Long = 240,
        collectedAt: Long = noon,
    ) = NeighbourRecord("repeater", prefix, snr, heardSecondsAgo, collectedAt)

    // --- The reason a collected-at stamp exists -----------------------

    @Test
    fun `a stored reading ages with the local clock`() {
        val row = record(heardSecondsAgo = 240, collectedAt = noon)
        // Read back six hours later: the node's 4 minutes is now 6h04m.
        val sixHoursLater = noon + 6 * 3_600_000L
        assertEquals(240 + 6 * 3600, row.secondsAgoAt(sixHoursLater))
        // And this is the whole point — without the stamp it would still
        // be reporting four minutes.
        assertTrue(row.secondsAgoAt(sixHoursLater) > row.heardSecondsAgo)
    }

    @Test
    fun `reading it back immediately is what the node said`() {
        // The positive control for the ageing: with no time elapsed the
        // stored value must survive untouched, not drift by a rounding.
        assertEquals(240, record(heardSecondsAgo = 240).secondsAgoAt(noon))
    }

    @Test
    fun `a clock that steps backwards cannot make a reading younger`() {
        val row = record(heardSecondsAgo = 240, collectedAt = noon)
        assertEquals(240, row.secondsAgoAt(noon - 3_600_000L))
    }

    // --- Link quality -------------------------------------------------

    @Test
    fun `quality bands are pinned at their boundaries`() {
        assertEquals(LinkQuality.Strong, LinkQuality.of(5.0))
        assertEquals(LinkQuality.Good, LinkQuality.of(4.75))
        assertEquals(LinkQuality.Good, LinkQuality.of(0.0))
        assertEquals(LinkQuality.Fair, LinkQuality.of(-0.25))
        assertEquals(LinkQuality.Fair, LinkQuality.of(-7.0))
        assertEquals(LinkQuality.Weak, LinkQuality.of(-7.25))
        assertEquals(LinkQuality.Weak, LinkQuality.of(-15.0))
        assertEquals(LinkQuality.Marginal, LinkQuality.of(-15.25))
    }

    @Test
    fun `a negative SNR is not automatically a bad link`() {
        // LoRa decodes well below the noise floor. A scale that painted
        // everything under 0 dB red would report a healthy mesh as
        // failing, which is the mistake this band table exists to avoid.
        assertEquals(LinkQuality.Fair, LinkQuality.of(-5.0))
    }

    @Test
    fun `SNR renders at the quarter-dB steps the wire actually carries`() {
        assertEquals("0.0 dB", formatSnr(0.0))
        assertEquals("3.8 dB", formatSnr(3.75))
        assertEquals("-4.3 dB", formatSnr(-4.25))
        // Between -1 and 0 the sign lives only in the fraction; dropping
        // it there turns a weak link into a good one.
        assertEquals("-0.8 dB", formatSnr(-0.75))
        assertEquals("-0.3 dB", formatSnr(-0.25))
    }

    // --- Resolving a prefix to a line ---------------------------------

    private val blue = NeighbourEndpoint("aabbccddeeff0011", "Blue Ridge", 42.96, -85.67)
    private val ridge = NeighbourEndpoint("aabbccddeeff9999", "Ridge Two", 43.10, -85.50)
    private val noFix = NeighbourEndpoint("112233445566aaaa", "Shed", 0.0, 0.0)

    @Test
    fun `a unique match with a position is drawn`() {
        val links = neighbourLinks(listOf(record(prefix = "aabbccddeeff")), listOf(blue), noon)
        val link = links.single()
        assertEquals(blue, link.endpoint)
        assertNull(link.undrawable)
        assertTrue(link.isDrawable)
        assertEquals("aabbccddeeff Blue Ridge", link.label)
    }

    @Test
    fun `an ambiguous prefix is never drawn to one of the candidates`() {
        // 6 bytes is 48 bits and a deliberate collision is possible.
        // Drawing a line to whichever matched first would put a real
        // repeater's coverage on the wrong node's roof.
        val links = neighbourLinks(
            listOf(record(prefix = "aabbccddeeff")),
            listOf(blue, ridge),
            noon,
        )
        val link = links.single()
        assertNull(link.endpoint)
        assertEquals(UndrawableLink.Ambiguous, link.undrawable)
        assertEquals("aabbccddeeff (2 matches)", link.label)
    }

    @Test
    fun `a prefix we do not know says so`() {
        val links = neighbourLinks(listOf(record(prefix = "ffffffffffff")), listOf(blue), noon)
        assertEquals(UndrawableLink.Unknown, links.single().undrawable)
        assertEquals("ffffffffffff", links.single().label)
    }

    @Test
    fun `a known node with no fix is reported rather than plotted at the equator`() {
        val links = neighbourLinks(listOf(record(prefix = "112233445566")), listOf(noFix), noon)
        val link = links.single()
        assertNull(link.endpoint)
        assertEquals(UndrawableLink.NoPosition, link.undrawable)
        // Positive control: the same node with a real fix IS drawn, so
        // this test cannot pass by the resolver doing nothing at all.
        val located = noFix.copy(latitude = 42.9, longitude = -85.6)
        assertEquals(located, neighbourLinks(listOf(record(prefix = "112233445566")), listOf(located), noon).single().endpoint)
    }

    @Test
    fun `links are ordered strongest first`() {
        val links = neighbourLinks(
            listOf(
                record(prefix = "aabbccddeeff", snr = -9.0),
                record(prefix = "112233445566", snr = 6.0),
            ),
            listOf(blue, noFix),
            noon,
        )
        assertEquals(listOf(6.0, -9.0), links.map { it.snr })
    }

    @Test
    fun `the chip on the line names the band so no legend is needed`() {
        val row = record(snr = -4.25)
        val link = neighbourLinks(listOf(row), listOf(blue), noon).single()
        // Colour carries it for the glance; the word carries it for the
        // read. A key at the edge of the screen carried it for neither.
        assertEquals("Fair · -4.3 dB", link.mapLabel)
    }

    @Test
    fun `a summary carries the band and the reading and its age`() {
        val row = record(snr = -4.25, heardSecondsAgo = 60, collectedAt = noon - 600_000)
        val link = neighbourLinks(listOf(row), listOf(blue), noon).single()
        assertEquals("Fair · -4.3 dB · 11m ago", link.summary)
    }

    // --- When the reading was taken -----------------------------------

    @Test
    fun `one sweep reports one collection time`() {
        val rows = listOf(
            record(prefix = "aabbccddeeff", collectedAt = noon - 3_600_000 - 2_000),
            record(prefix = "112233445566", collectedAt = noon - 3_600_000),
        )
        assertEquals("Collected 1 hour ago", collectedLabel(rows, noon))
    }

    @Test
    fun `pages fetched far apart are reported as a span`() {
        val rows = listOf(
            record(prefix = "aabbccddeeff", collectedAt = noon - 86_400_000),
            record(prefix = "112233445566", collectedAt = noon - 60_000),
        )
        assertEquals("Collected 1 day ago to 1 min ago", collectedLabel(rows, noon))
    }

    @Test
    fun `no rows means no claim about when`() {
        assertNull(collectedLabel(emptyList(), noon))
    }

    // --- What the popup offers ----------------------------------------

    @Test
    fun `only a repeater is offered neighbours`() {
        assertNull(
            neighbourOffer(
                isRepeater = false, connected = true, session = AdminSession.None,
                hasSavedPassword = false, storedCount = 0, collected = null,
            ),
        )
    }

    @Test
    fun `with no saved password the fetch says it will use a blank one`() {
        val offer = neighbourOffer(
            isRepeater = true, connected = true, session = AdminSession.None,
            hasSavedPassword = false, storedCount = 0, collected = null,
        )!!
        assertEquals("Fetch neighbours", offer.fetchLabel)
        assertEquals("Signs in with a blank password, for read-only access.", offer.fetchHint)
        assertTrue(offer.canFetch)
    }

    @Test
    fun `a saved password is used instead of a blank one`() {
        val offer = neighbourOffer(
            isRepeater = true, connected = true, session = AdminSession.None,
            hasSavedPassword = true, storedCount = 0, collected = null,
        )!!
        assertEquals("Signs in with the password saved for this node.", offer.fetchHint)
    }

    @Test
    fun `an open session is not spent on another login`() {
        val offer = neighbourOffer(
            isRepeater = true, connected = true, session = AdminSession.Guest,
            hasSavedPassword = false, storedCount = 0, collected = null,
        )!!
        assertEquals("Uses the session you are already signed in with.", offer.fetchHint)
    }

    @Test
    fun `stored rows are offered with no radio attached`() {
        // The reason they are stored at all: an offline coverage picture,
        // honest about its age.
        val offer = neighbourOffer(
            isRepeater = true, connected = false, session = AdminSession.None,
            hasSavedPassword = false, storedCount = 3, collected = "Collected 2 hours ago",
        )!!
        assertTrue(offer.hasStored)
        assertEquals(false, offer.canFetch)
        assertEquals("Not connected to a radio.", offer.fetchHint)
        assertEquals("Fetch again", offer.fetchLabel)
    }

    // --- Outcomes -----------------------------------------------------

    @Test
    fun `a silent node is not reported as a refusal`() {
        // The firmware returns 0 for a password it will not take, and
        // the caller then sends nothing (`MyMesh.cpp:594`). So a wrong
        // password and a node out of range are the same silence, and
        // claiming a refusal would send the user to fix the wrong thing.
        val silent = NeighbourFetch.SignInRefused(blank = true, answered = false).message
        assertTrue(silent.contains("No answer"))
        assertTrue(silent.contains("out of reach"))
        assertTrue(!silent.startsWith("The node"), "silence must not be reported as a refusal")

        // A node that DID answer is the one case where refusal is a fact.
        val answered = NeighbourFetch.SignInRefused(blank = true, answered = true).message
        assertTrue(answered.contains("would not take a blank password"))
    }

    @Test
    fun `a refusal names the credential that was refused`() {
        assertTrue(
            NeighbourFetch.SignInRefused(blank = true, answered = true)
                .message.contains("blank password"),
        )
        assertTrue(
            NeighbourFetch.SignInRefused(blank = false, answered = true)
                .message.contains("saved password"),
        )
    }

    @Test
    fun `a partial page says so and a rejection does not invite a retry`() {
        assertEquals(
            "Recorded 2 of the 5 neighbours it knows.",
            NeighbourFetch.Collected(count = 2, total = 5).message,
        )
        assertEquals("Recorded 1 neighbour.", NeighbourFetch.Collected(1, 1).message)
        assertEquals("The node reported no neighbours.", NeighbourFetch.Collected(0, 0).message)
        assertTrue(NeighbourFetch.Rejected(total = 2).message.contains("not a paged table"))
    }
}

/** How a reply page joins what is already stored. */
class NeighbourWriteTest {

    @Test
    fun `a first page replaces the table`() {
        assertEquals(
            NeighbourWrite(clearFirst = true, store = true),
            neighbourWrite(offset = 0, entryCount = 3, rejected = false),
        )
    }

    @Test
    fun `an honest empty answer clears the stale lines`() {
        // Nothing expires a neighbour entry on the node, so a repeater
        // that now reports nobody must not keep yesterday's links drawn.
        assertEquals(
            NeighbourWrite(clearFirst = true, store = false),
            neighbourWrite(offset = 0, entryCount = 0, rejected = false),
        )
    }

    @Test
    fun `a rejected page spends nothing`() {
        // "Knows 2, returned none" is a malformed request, not a
        // finding. Treating it as an empty table would throw away a
        // real reading to record that we asked wrong.
        assertEquals(
            NeighbourWrite(clearFirst = false, store = false),
            neighbourWrite(offset = 0, entryCount = 0, rejected = true),
        )
    }

    @Test
    fun `a later page adds to the sweep it belongs to`() {
        assertEquals(
            NeighbourWrite(clearFirst = false, store = true),
            neighbourWrite(offset = 11, entryCount = 2, rejected = false),
        )
    }
}
