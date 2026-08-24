package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring around the neighbour store — the parts that are not pure
 * functions and so cannot be pinned by `NeighbourLinksTest`.
 *
 * Source assertions, in the shape `OtaFlowWiringTest` uses: a
 * ViewModel with a live radio and a Room database behind it needs
 * instrumentation, and the mistakes worth catching here are structural
 * — a reading recorded on one screen and not the other, a password
 * sealed that was never meant to be, a `total` off the mesh driving
 * round trips without a ceiling.
 */
class NeighbourMapWiringTest {

    private fun read(path: String) = File(path).readText()

    private val viewModel =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/MeshCoreViewModel.kt")
    private val map =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/MapScreen.kt")
    private val sheet =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/MapNodeSheet.kt")
    private val repository =
        read("src/main/kotlin/io/github/thatsfguy/meshcore/android/storage/MessageRepository.kt")

    // --- Recording ----------------------------------------------------

    @Test
    fun `every neighbour fetch is recorded, whichever screen asked`() {
        // Recording sits on the one call both screens go through. A
        // table costs a login and a round trip over the air; keeping it
        // only when the user happened to be on the map would make the
        // map look empty right after the repeater panel had just read
        // the very same rows.
        val fetch = viewModel.substringAfter("suspend fun repeaterNeighbours(")
            .substringBefore("\n    /**")
        assertTrue(
            "repeaterNeighbours does not record what it read",
            fetch.contains("recordNeighbours(keyHex, table)"),
        )
    }

    @Test
    fun `what a page does to the stored table is the shared decision`() {
        val record = viewModel.substringAfter("private suspend fun recordNeighbours(")
            .substringBefore("\n    /**")
        assertTrue(
            "the write rule is inlined here instead of neighbourWrite",
            record.contains("neighbourWrite("),
        )
        assertTrue(
            "a rejected page must not be allowed to clear a real reading",
            record.contains("rejected = table.isEmptyButNotEmpty"),
        )
        assertTrue(
            "the page is stamped with the local clock",
            record.contains("collectedAt = collectedAt") &&
                record.contains("System.currentTimeMillis()"),
        )
    }

    // --- Signing in to read it ----------------------------------------

    @Test
    fun `a fetch spends the saved password or a blank one, and seals neither`() {
        val collect = viewModel.substringAfter("suspend fun collectNeighbours(")
            .substringBefore("\n    /**")
        assertTrue(
            "the saved password is not preferred over a blank one",
            collect.contains("savedLoginPassword(keyHex)") && collect.contains("saved ?: \"\""),
        )
        // A blank password is nothing to keep, and sealing one would put
        // an empty credential in the keystore for a node the user never
        // chose to save.
        assertTrue(
            "a neighbour fetch must never seal the credential it used",
            collect.contains("savePassword = false"),
        )
        assertTrue(
            "an open session is spent on another login",
            collect.contains("if (!session.signedIn)"),
        )
    }

    @Test
    fun `paging cannot be talked into an unbounded loop`() {
        // `total` arrives off the mesh. Without a ceiling a node
        // claiming 65535 neighbours would drive round trips until the
        // user gave up.
        val collect = viewModel.substringAfter("suspend fun collectNeighbours(")
            .substringBefore("\n    /**")
        assertTrue(
            "the page loop is not capped",
            collect.contains("repeat(NEIGHBOUR_PAGE_LIMIT)"),
        )
        assertTrue(
            "a rejected page keeps being asked for",
            collect.contains("isEmptyButNotEmpty"),
        )
    }

    // --- Forgetting ---------------------------------------------------

    @Test
    fun `a removed contact does not leave lines on the map`() {
        val remove = viewModel.substringAfter("fun removeContact(").substringBefore("\n    fun ")
        assertTrue(
            "removing a node keeps its neighbour table",
            remove.contains("db.neighbours().clear(selfKey.value, keyHex)"),
        )
        assertTrue(
            "a local purge leaves the neighbour store behind",
            repository.contains("db.neighbours().clearAll(key)"),
        )
    }

    // --- Drawing ------------------------------------------------------

    @Test
    fun `links resolve against every contact, not the visible pins`() {
        // The type filter hides pins; it does not make a node unknown.
        // Resolving against the filtered list would report a neighbour
        // as "not a known node" because the user ticked "Only repeaters".
        val resolve = map.substringAfter("val endpoints =").substringBefore("fun toggleType")
        assertTrue(
            "links are resolved against the filtered pins",
            resolve.startsWith(" contacts.map {") && !resolve.contains("located.map {"),
        )
    }

    @Test
    fun `lines go under the pins and their readings go over everything`() {
        val lines = map.indexOf("map.overlays.add(linkLine(")
        val pins = map.indexOf("for (c in located)")
        val chips = map.indexOf("map.overlays.addAll(chips)")
        assertTrue("no neighbour lines are drawn at all", lines > 0)
        assertTrue(
            "lines are drawn over the markers, hiding the nodes they are about",
            lines < pins,
        )
        // Seen on hardware: a chip added with its line sat under a
        // neighbouring node's always-on name label, which hides the one
        // number the line exists to report.
        assertTrue("the reading chips are drawn under the pin labels", chips > pins)
    }

    @Test
    fun `the line says its quality in words, not only in colour`() {
        // There is no legend and there must not be one: a key at the
        // edge of the screen asks the reader to hold five colours in
        // their head and look away from the line they are reading.
        assertTrue(
            "the chip on the line carries only the number",
            map.contains("NodeMarkers.buildChip(map.context, link.mapLabel"),
        )
        assertTrue("a colour key is back on the map", !map.contains("LinkLegend"))
    }

    @Test
    fun `a tap on a pin opens the node popup`() {
        assertTrue(
            "the marker tap does not open the sheet",
            map.contains("onTap = { selectedKey = c.keyHex }") &&
                map.contains("setOnMarkerClickListener"),
        )
        assertTrue("the map never shows the popup", map.contains("MapNodeSheet("))
    }

    @Test
    fun `closing the popup leaves the links drawn`() {
        // The whole point of turning them on is to look at them, which
        // cannot be done through a sheet covering the map. So the
        // drawn-links state is separate from the selected pin, and
        // dismissing touches only the pin.
        assertTrue(
            "the sheet's dismiss clears more than the selection",
            map.contains("onDismiss = { selectedKey = null }"),
        )
        assertTrue(
            "there is no way to put the links away again",
            map.contains("MenuAction(\"Hide neighbour links\")"),
        )
    }

    @Test
    fun `the popup only offers neighbours where a table exists`() {
        // Room servers and sensors have no 0x06 handler at all, so the
        // control could only ever time out into "no reply, in range?",
        // which blames the link for a question the node cannot be asked.
        assertTrue(
            "the sheet decides for itself which nodes keep neighbours",
            sheet.contains("neighbourOffer(") &&
                sheet.contains("isRepeater = contact.type == Codes.ADV_TYPE_REPEATER"),
        )
        assertTrue(
            "the fetch button is live with no radio attached",
            sheet.contains("enabled = offer.canFetch && !busy"),
        )
    }

    @Test
    fun `the popup lists what cannot be drawn as well as what can`() {
        // A neighbour with no position, or with a prefix matching two
        // nodes, is still something the repeater heard. The popup is the
        // only place it can be read, so it is listed there whether or
        // not the lines are switched on.
        assertTrue(
            "the popup only lists neighbours while its links are drawn",
            map.contains("links = linksOf(key),"),
        )
        assertTrue(
            "the sheet does not say why a row is missing from the map",
            sheet.contains("link.undrawable?.let"),
        )
    }

    @Test
    fun `the reading says how old it is`() {
        assertTrue(
            "the popup shows rows without saying when they were collected",
            sheet.contains("collectedLabel(records, System.currentTimeMillis())") &&
                sheet.contains("offer.collected?.let"),
        )
    }
}
