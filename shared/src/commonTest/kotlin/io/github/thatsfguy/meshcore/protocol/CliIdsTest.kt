package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The CLI ids the app sends must be ids the radio has.
 *
 * These strings decide what a repeater does, and they were hand-typed
 * in two files with nothing checking that the two agreed. A typo is
 * silent in both directions — a documented command that is never sent,
 * or a sent command no node answers — and only shows up as somebody's
 * repeater not doing what the screen said.
 */
class CliIdsTest {

    private val catalogue = CliCatalog.all.map { it.id }.toSet()

    @Test
    fun `every shared id exists in the catalogue`() {
        // The positive control: if CliIds drifted from the catalogue,
        // these would be commands the radio silently ignores.
        assertTrue(CliIds.ALL.size >= 24, "the id set shrank unexpectedly")
        for (id in CliIds.ALL) {
            assertTrue(id in catalogue, "CliIds has \"$id\", the catalogue does not")
        }
    }

    @Test
    fun `form-only field keys are NOT catalogue ids`() {
        // These are composed into other commands and must never be sent
        // as `set <key>`. If one ever becomes a real id, this fails and
        // the two namespaces need untangling rather than merging.
        for (key in CliFormFields.ALL) {
            assertFalse(
                key in catalogue,
                "\"$key\" is a form field AND a real CLI id — one of them has to change",
            )
        }
    }

    @Test
    fun `the radio fields compose a command that does exist`() {
        // radio.freq/bw/sf/cr are edited separately and saved as one
        // `set radio <csv>`, so the thing they compose has to be real.
        assertTrue(CliIds.RADIO in catalogue)
        assertTrue(CliFormFields.RADIO_FREQ.startsWith(CliIds.RADIO + "."))
        assertTrue(CliFormFields.RADIO_BW.startsWith(CliIds.RADIO + "."))
    }

    @Test
    fun `no two constants share a value`() {
        val all = CliFormFields.ALL.toList() +
            listOf(CliIds.NAME, CliIds.LAT, CliIds.LON, CliIds.RADIO, CliIds.TX)
        assertEquals(all.size, all.distinct().size, "two keys share a spelling")
    }

    @Test
    fun `catalogue ids are unique`() {
        val ids = CliCatalog.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "the catalogue lists an id twice")
    }
}
