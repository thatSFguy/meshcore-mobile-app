package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the address out of a node's answer to `start ota`.
 *
 * Pinned against the firmware's own `sprintf`
 * (`NRF52Board::startOTAUpdate`), which prints `mac_addr[5]` first — so
 * the string is in the same order a phone displays a BLE address, and
 * needs no reversing.
 *
 * This matters because it is the difference between matching the node
 * the operator asked and picking whichever board on the bench happens
 * to be advertising a name ending in OTA.
 */
class OtaReplyTest {

    @Test
    fun `the address is read out of the reply the firmware sends`() {
        assertEquals(
            "C4:8A:1B:2D:3E:4F",
            OtaReply.advertisingAddress("OK - mac: C4:8A:1B:2D:3E:4F"),
        )
        assertTrue(OtaReply.accepted("OK - mac: C4:8A:1B:2D:3E:4F"))
    }

    @Test
    fun `a lowercase or padded reply is read the same way`() {
        // The reply arrives as a CLI message and may be trimmed, padded
        // or wrapped by anything between here and the node.
        assertEquals(
            "C4:8A:1B:2D:3E:4F",
            OtaReply.advertisingAddress("  ok - mac:  c4:8a:1b:2d:3e:4f  "),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            OtaReply.advertisingAddress("OK - mac: aa:bb:cc:dd:ee:ff\n"),
        )
    }

    @Test
    fun `an all-zero address is not an address`() {
        // The firmware memsets the buffer before reading; all-zero is
        // what a failed read leaves. Scanning for it finds nothing, and
        // treating it as valid hides why.
        assertNull(OtaReply.advertisingAddress("OK - mac: 00:00:00:00:00:00"))
        assertFalse(OtaReply.accepted("OK - mac: 00:00:00:00:00:00"))
    }

    @Test
    fun `a node that did not take the command reports no address`() {
        // `Error` is what CommonCLI answers when startOTAUpdate returns
        // false; an ESP32 raises a WiFi hotspot and says something else
        // entirely; and an unknown command comes back as `??: …`.
        for (reply in listOf(
            null,
            "",
            "   ",
            "Error",
            "??: start ota",
            "OK",
            "OK - mac:",
            "OK - mac: C4:8A:1B:2D:3E",
            "OK - mac: GG:8A:1B:2D:3E:4F",
            "connect to WiFi 'MeshCore OTA'",
        )) {
            assertNull(OtaReply.advertisingAddress(reply), "read an address out of \"$reply\"")
            assertFalse(OtaReply.accepted(reply))
        }
    }

    @Test
    fun `the address is not reversed on the way in`() {
        // The firmware prints mac_addr[5] first, which is the same order
        // Android and iOS print. Reversing it here would send the scan
        // looking for an address nothing advertises — and the low octet
        // is the one the bootloader increments, so a reversed address
        // would break the +1 as well.
        val address = OtaReply.advertisingAddress("OK - mac: 10:06:1C:31:42:2E")
        assertEquals("10:06:1C:31:42:2E", address)
        assertEquals("10:06:1C:31:42:2F", BootloaderPeer.expectedAddress(address!!))
    }
}
