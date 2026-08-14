package io.github.thatsfguy.meshcore.android.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What survives the radio's contact list being re-read.
 *
 * The radio is authoritative for a contact's name, type, flags, path
 * and position, and its list is re-read on every connection. Everything
 * ELSE on the row was learned here, from the mesh or from the operator,
 * and **the radio cannot supply it** — so a refresh that rebuilds the
 * row from the radio alone does not merely lose data, it loses exactly
 * the data that was stored because it could not be fetched again.
 *
 * Seen on hardware 2026-08-14: a repeater answered `start ota` with
 * `OK - mac: FF:5C:EF:28:2A:92`, the address was stored, and the panel
 * displayed it. The transfer then failed, the companion radio
 * reconnected, its contact list synced — and the recovery dialog for
 * that node, minutes later, read "No update-mode address was recorded
 * for this node". The node was in its bootloader by then and could not
 * be asked for anything, which is the entire reason the address is kept.
 */
class ContactRefreshTest {

    private fun fromRadio(name: String = "13 Mile Rd") = ContactEntity(
        selfKey = "self",
        keyHex = "6eb85e829a78",
        name = name,
        type = 2,
        flags = 0,
        pathLen = 0x40,
        latitude = null,
        longitude = null,
        lastSeen = 1_000,
        lastModified = 1_000,
    )

    private fun asStored() = fromRadio().copy(
        unread = 3,
        lastMessageAt = 900,
        otaAddress = "FF:5C:EF:28:2A:92",
        otaAnnouncedAt = 950,
        updateModeSince = 950,
        otaReplyHandledAt = 950_000,
        boardName = "ProMicro DIY",
        firmwareVersion = "v1.15.0-dee3e26",
    )

    @Test
    fun `everything the radio cannot tell us survives its refresh`() {
        val merged = fromRadio().keepingLocalFacts(asStored())

        // The whole point: a node in its bootloader answers nothing, and
        // these are what make it recoverable anyway.
        assertEquals("FF:5C:EF:28:2A:92", merged.otaAddress, "the announced address was lost")
        assertEquals(950, merged.otaAnnouncedAt)
        assertEquals(950, merged.updateModeSince, "the update-mode flag was lost")
        assertEquals(950_000, merged.otaReplyHandledAt, "the reply watermark was lost")
        assertEquals("ProMicro DIY", merged.boardName, "the board name was lost")
        assertEquals("v1.15.0-dee3e26", merged.firmwareVersion, "the firmware version was lost")
        assertEquals(3, merged.unread)
        assertEquals(900, merged.lastMessageAt)
    }

    @Test
    fun `the radio still wins for what the radio owns`() {
        // The merge must not become "keep the old row". A rename, a new
        // path, a new position and a fresh advert time are the reason
        // this sync exists.
        val renamed = fromRadio(name = "13 Mile Rd (N)").copy(
            pathLen = 0x42,
            latitude = 43.0,
            longitude = -85.0,
            lastSeen = 2_000,
            lastModified = 2_000,
            flags = 1,
        )
        val merged = renamed.keepingLocalFacts(asStored())

        assertEquals("13 Mile Rd (N)", merged.name)
        assertEquals(0x42, merged.pathLen)
        assertEquals(43.0, merged.latitude)
        assertEquals(-85.0, merged.longitude)
        assertEquals(2_000, merged.lastSeen)
        assertEquals(2_000, merged.lastModified)
        assertEquals(1, merged.flags)
    }

    @Test
    fun `a contact seen for the first time keeps its own empty values`() {
        val merged = fromRadio().keepingLocalFacts(null)
        assertNull(merged.otaAddress)
        assertNull(merged.boardName)
        assertEquals(0, merged.unread)
        assertEquals(0, merged.updateModeSince)
    }

    @Test
    fun `a newer local fact is not overwritten by an older stored one`() {
        // The merge runs against a row read a moment earlier, and
        // something else may have written in between — the console
        // watching for `start ota` does exactly that. Watermarks move
        // forwards only.
        val fresher = fromRadio().copy(
            otaAddress = "FF:5C:EF:28:2A:99",
            otaAnnouncedAt = 2_000,
            otaReplyHandledAt = 2_000_000,
            updateModeSince = 2_000,
            boardName = "RAK 4631",
        )
        val merged = fresher.keepingLocalFacts(asStored())

        assertEquals("FF:5C:EF:28:2A:99", merged.otaAddress)
        assertEquals(2_000, merged.otaAnnouncedAt)
        assertEquals(2_000_000, merged.otaReplyHandledAt)
        assertEquals(2_000, merged.updateModeSince)
        assertEquals("RAK 4631", merged.boardName)
    }
}
