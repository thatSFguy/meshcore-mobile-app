package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding the right node after the reboot.
 *
 * The bootloader is a different BLE peer from the application, on a
 * different address and under a different name. Both rules come from
 * `dfu_transport_ble.c` (`addr.addr[0] += 1`, `DEVICE_NAME`), and
 * getting either wrong means connecting to some other node and flashing
 * that instead.
 */
class BootloaderPeerTest {

    @Test
    fun `the bootloader address is the last octet plus one`() {
        // addr[0] is the least-significant octet, which is the last one
        // printed — not the first.
        assertEquals("C4:8A:1B:2D:3E:50", BootloaderPeer.expectedAddress("C4:8A:1B:2D:3E:4F"))
        assertEquals("AA:BB:CC:DD:EE:01", BootloaderPeer.expectedAddress("AA:BB:CC:DD:EE:00"))
    }

    @Test
    fun `an address ending in FF wraps to 00 rather than overflowing`() {
        // The firmware does this in a uint8. A client that produced
        // ":100" or carried into the next octet would scan for an
        // address no node will ever advertise.
        assertEquals("AA:BB:CC:DD:EE:00", BootloaderPeer.expectedAddress("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `every low octet round-trips`() {
        for (low in 0..0xFF) {
            val hex = low.toString(16).uppercase().padStart(2, '0')
            val expected = ((low + 1) and 0xFF).toString(16).uppercase().padStart(2, '0')
            assertEquals(
                "AA:BB:CC:DD:EE:$expected",
                BootloaderPeer.expectedAddress("AA:BB:CC:DD:EE:$hex"),
                "low octet $hex",
            )
        }
    }

    @Test
    fun `input that is not an address yields nothing rather than a guess`() {
        for (bad in listOf(
            "",
            "not an address",
            "AA:BB:CC:DD:EE",
            "AA:BB:CC:DD:EE:FF:00",
            "AA:BB:CC:DD:EE:ZZ",
            "AA-BB-CC-DD-EE-FF",
            "::::::",
        )) {
            assertNull(BootloaderPeer.expectedAddress(bad), "accepted \"$bad\"")
        }
    }

    @Test
    fun `bootloader names are recognised across bootloader builds`() {
        // Stock Adafruit, Nordic SDK, and the per-board names MeshCore
        // and OTAFIX builds use.
        assertTrue(BootloaderPeer.looksLikeBootloader("AdaDFU"))
        assertTrue(BootloaderPeer.looksLikeBootloader("DfuTarg"))
        assertTrue(BootloaderPeer.looksLikeBootloader("RAK4631_OTA"))
        assertTrue(BootloaderPeer.looksLikeBootloader("ProMicro_OTA"))
        assertTrue(BootloaderPeer.looksLikeBootloader("t114_ota"))
    }

    @Test
    fun `only the bootloader-only names identify an address as the plus-one`() {
        // Both states advertise `<board>_OTA`, so that name says nothing
        // about WHICH address is in hand — 13 Mile was seen as
        // `ProMicro_OTA` on its own address and as `AdaDFU` on that
        // address + 1, hours apart. Only the second is certain, and only
        // certainty may be acted on: recording a bootloader's address as
        // the node's makes every later derivation one too high.
        assertTrue(BootloaderPeer.isCertainlyBootloader("AdaDFU"))
        assertTrue(BootloaderPeer.isCertainlyBootloader("adadfu"))
        assertTrue(BootloaderPeer.isCertainlyBootloader(" DfuTarg "))
        for (ambiguous in listOf("ProMicro_OTA", "RAK4631_OTA", "Meshtiny OTA", null, "")) {
            assertFalse(
                BootloaderPeer.isCertainlyBootloader(ambiguous),
                "claimed certainty about \"$ambiguous\"",
            )
        }
    }

    @Test
    fun `an ordinary node is not mistaken for a bootloader`() {
        for (name in listOf(null, "", "   ", "MeshCore-Blue", "OTA", "_OTAgrapher", "MC-Repeater")) {
            assertFalse(BootloaderPeer.looksLikeBootloader(name), "matched \"$name\"")
        }
    }

    @Test
    fun `either the address or the name is enough on its own`() {
        val expectation = BootloaderExpectation(companionAddress = "AA:BB:CC:DD:EE:10")
        // Stock bootloader: right address, generic name.
        assertTrue(
            BootloaderPeer.matches(expectation, DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU")),
        )
        // Put into DFU mode with the buttons, so we never saw its
        // app-mode address.
        assertTrue(
            BootloaderPeer.matches(BootloaderExpectation(), DfuPeer("11:22:33:44:55:66", "RAK4631_OTA")),
        )
        // Neither: some unrelated peripheral.
        assertFalse(
            BootloaderPeer.matches(expectation, DfuPeer("11:22:33:44:55:66", "Fitness Band")),
        )
    }

    @Test
    fun `the expected address wins over any name match`() {
        val expectation = BootloaderExpectation("AA:BB:CC:DD:EE:10", "RAK 4631")
        val chosen = BootloaderPeer.choose(
            expectation,
            listOf(
                DfuPeer("99:99:99:99:99:99", "RAK4631_OTA"),
                DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU"),
            ),
        )
        assertEquals("AA:BB:CC:DD:EE:11", chosen?.address)
    }

    @Test
    fun `a node that announced its address is recognised in either state`() {
        // `start ota` answers "OK - mac: …" with the address the node is
        // advertising on RIGHT NOW, from its own still-running firmware.
        // The same node, once it has taken the jump, is one higher.
        // Which of the two it is in is precisely what we do not know at
        // the time of the scan — so an announced address has to mean
        // "this node", not "this node before it jumped".
        val expectation = BootloaderExpectation(companionAddress = "AA:BB:CC:DD:EE:10")
        // Named or not. A scan record frequently carries no local name
        // at all, and then the address the node gave us is the ONLY
        // identity there is — so this must not lean on the `_OTA`
        // suffix to pass.
        assertTrue(
            BootloaderPeer.matches(expectation, DfuPeer("AA:BB:CC:DD:EE:10", null)),
            "the address the node announced was not matched on its own",
        )
        assertTrue(
            BootloaderPeer.matches(expectation, DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA")),
        )
        assertTrue(
            BootloaderPeer.matches(expectation, DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU")),
        )
        // And it is still an address, not a licence: one further along
        // is a different node.
        assertFalse(
            BootloaderPeer.matches(expectation, DfuPeer("AA:BB:CC:DD:EE:12", "Fitness Band")),
        )
    }

    @Test
    fun `the announced address outranks another node advertising for an update`() {
        // The case this exists for, and the one that was broken: two
        // boards in update mode, ours known by the address it told us.
        // An announced address matched only at +1 left both candidates
        // matching on NAME alone, with nothing to separate them — so a
        // node whose address we had been given was reported as "not
        // advertising".
        val expectation = BootloaderExpectation("AA:BB:CC:DD:EE:10", "ProMicro DIY")
        assertEquals(
            "AA:BB:CC:DD:EE:10",
            BootloaderPeer.choose(
                expectation,
                listOf(
                    DfuPeer("99:99:99:99:99:99", "RAK4631_OTA"),
                    DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA"),
                ),
            )?.address,
        )
        // Once it has jumped, the bootloader's address wins over its
        // own — it is the same node, further along.
        assertEquals(
            "AA:BB:CC:DD:EE:11",
            BootloaderPeer.choose(
                expectation,
                listOf(
                    DfuPeer("AA:BB:CC:DD:EE:10", "ProMicro_OTA"),
                    DfuPeer("AA:BB:CC:DD:EE:11", "AdaDFU"),
                ),
            )?.address,
        )
    }

    @Test
    fun `two nodes in update mode and nothing to tell them apart is not a guess`() {
        // A bench with several spare boards on it. Flashing the wrong
        // one is unrecoverable over the air, so this must decline.
        val chosen = BootloaderPeer.choose(
            BootloaderExpectation(),
            listOf(DfuPeer("11:11:11:11:11:11", "AdaDFU"), DfuPeer("22:22:22:22:22:22", "AdaDFU")),
        )
        assertNull(chosen)
    }

    @Test
    fun `a board name separates two candidates when it can`() {
        val chosen = BootloaderPeer.choose(
            BootloaderExpectation(nameHint = "RAK4631"),
            listOf(
                DfuPeer("11:11:11:11:11:11", "Heltec_t114_OTA"),
                DfuPeer("22:22:22:22:22:22", "RAK4631_OTA"),
            ),
        )
        assertEquals("22:22:22:22:22:22", chosen?.address)
    }

    @Test
    fun `nothing viable is null rather than the first thing seen`() {
        assertNull(BootloaderPeer.choose(BootloaderExpectation(), emptyList()))
        assertNull(
            BootloaderPeer.choose(
                BootloaderExpectation("AA:BB:CC:DD:EE:10"),
                listOf(DfuPeer("00:00:00:00:00:01", "MeshCore-Blue")),
            ),
        )
    }

    // --- a node that has run `start ota` ----------------------------------

    @Test
    fun `a name with a space before OTA is still an update-mode node`() {
        // Almost every board names itself <BOARD>_OTA, and `Meshtiny
        // OTA` does not. An endsWith("_OTA") rule leaves exactly one
        // board in the catalogue unreachable.
        assertTrue(BootloaderPeer.looksLikeBootloader("Meshtiny OTA"))
        assertTrue(BootloaderPeer.looksLikeBootloader("T1000E_OTA"))
        assertTrue(BootloaderPeer.looksLikeBootloader("MESH_POCKET_OTA"))
        assertTrue(BootloaderPeer.looksLikeBootloader("NANO_G2_OTA"))
    }

    @Test
    fun `an address the node reported for itself is matched as-is`() {
        // `start ota` answers "OK - mac: …" with the address the node is
        // advertising on RIGHT NOW, from its own running firmware. The
        // bootloader's +1 has not happened yet, so applying it here
        // would scan for an address nothing is using.
        val expectation = BootloaderExpectation(exactAddress = "C4:8A:1B:2D:3E:4F")
        assertTrue(
            BootloaderPeer.matches(expectation, DfuPeer("C4:8A:1B:2D:3E:4F", "RAK4631_OTA")),
        )
        assertFalse(
            BootloaderPeer.matches(expectation, DfuPeer("C4:8A:1B:2D:3E:50", "RAK4631_OTA")),
        )
    }

    @Test
    fun `a reported address outranks every other node advertising for an update`() {
        // The bench case: three boards in update mode, and the one we
        // asked is known by address. Nothing else may be picked.
        val expectation = BootloaderExpectation(exactAddress = "AA:BB:CC:DD:EE:10")
        val chosen = BootloaderPeer.choose(
            expectation,
            listOf(
                DfuPeer("11:11:11:11:11:11", "RAK4631_OTA"),
                DfuPeer("AA:BB:CC:DD:EE:10", "T114_OTA"),
                DfuPeer("22:22:22:22:22:22", "AdaDFU"),
            ),
        )
        assertEquals("AA:BB:CC:DD:EE:10", chosen?.address)
    }

    @Test
    fun `an exact address that is not present matches nothing at all`() {
        // Better to report "not found" than to flash a different node
        // that happens to be advertising.
        val expectation = BootloaderExpectation(exactAddress = "AA:BB:CC:DD:EE:10")
        assertNull(
            BootloaderPeer.choose(expectation, listOf(DfuPeer("99:99:99:99:99:99", "RAK4631_OTA"))),
        )
    }

    // --- which bootloader -------------------------------------------------

    @Test
    fun `the names seen on hardware classify as the bootloaders they were`() {
        // `4631_DFU`: the test RAK's bootloader, 2026-09-24, which then
        // negotiated MTU 247 — OTAFIX. `AdaDFU`: 13 Mile's in August,
        // MTU 23 — stock Adafruit 0.9.2.
        assertEquals(BootloaderKind.Otafix, BootloaderPeer.kindOf("4631_DFU"))
        assertEquals(BootloaderKind.Stock, BootloaderPeer.kindOf("AdaDFU"))
        assertTrue(BootloaderKind.Otafix.restartKeepsItReachable)
        assertFalse(BootloaderKind.Stock.restartKeepsItReachable)
    }

    @Test
    fun `every OTAFIX board name is recognised`() {
        // Each board's `board.mk` in oltaco/Adafruit_nRF52_Bootloader_OTAFIX.
        for (name in listOf(
            "T114_DFU", "MX25_DFU", "LGTE_DFU", "WTL1_DFU", "T096_DFU", "TNM6_DFU",
            "T1KE_DFU", "T1_DFU", "TNM1_DFU", "TNM3_DFU", "RTAG_DFU", "XIAO_DFU",
            "SCAP_DFU", "4631_DFU", "PROM_DFU", "3401_DFU",
        )) {
            assertEquals(BootloaderKind.Otafix, BootloaderPeer.kindOf(name), name)
            assertTrue(BootloaderPeer.looksLikeBootloader(name), name)
            assertTrue(BootloaderPeer.isCertainlyBootloader(name), name)
        }
    }

    @Test
    fun `anything unrecognised is not taken for OTAFIX`() {
        // Guessing OTAFIX wrongly restarts an erased stock node into USB
        // mode; guessing stock wrongly costs a retry. Only the first is
        // expensive, so doubt falls to stock.
        for (name in listOf(null, "", "_DFU", "DfuTarg", "RAK4631_OTA", "Meshtiny OTA", "DFU")) {
            assertFalse(BootloaderPeer.kindOf(name).restartKeepsItReachable, "$name")
        }
        // And MeshCore's own firmware after `start ota` is not a bootloader.
        assertFalse(BootloaderPeer.isCertainlyBootloader("RAK4631_OTA"))
    }

    @Test
    fun `the node's own address is worked back from its bootloader's`() {
        // The test RAK: app mode on …:87:E1, OTAFIX bootloader on …:87:E2.
        assertEquals(
            "E1:AB:65:43:87:E1",
            BootloaderPeer.nodeAddressOf(DfuPeer("E1:AB:65:43:87:E2", "4631_DFU")),
        )
        // Already the node's own: app mode after `start ota`.
        assertEquals(
            "E1:AB:65:43:87:E1",
            BootloaderPeer.nodeAddressOf(DfuPeer("e1:ab:65:43:87:e1", "RAK4631_OTA")),
        )
        // The increment wraps, so the step back does too.
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            BootloaderPeer.nodeAddressOf(DfuPeer("AA:BB:CC:DD:EE:00", "AdaDFU")),
        )
        assertNull(BootloaderPeer.nodeAddressOf(DfuPeer("not an address", "AdaDFU")))
        // And it is the inverse of the address the bootloader is looked for on.
        val node = "12:34:56:78:9A:FF"
        val boot = BootloaderPeer.expectedAddress(node)!!
        assertEquals(node, BootloaderPeer.nodeAddressOf(DfuPeer(boot, "PROM_DFU")))
    }
}
