package io.github.thatsfguy.meshcore.firmware

import io.github.thatsfguy.meshcore.model.DeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The "what is this radio running" line. A pure function so the Settings
 * screen's copy is testable off-device, and because the firmware-update
 * tile will render the same identity.
 */
class DeviceIdentityLineTest {

    private fun info(
        board: String? = null,
        version: String? = null,
        built: String? = null,
    ) = DeviceInfo(
        firmwareVerCode = 10,
        maxContacts = 100,
        maxChannels = 8,
        clientRepeat = false,
        pathHashByteWidth = 2,
        blePin = null,
        firmwareBuildDate = built,
        boardName = board,
        firmwareVersion = version,
    )

    @Test
    fun `board version and build date read as one line`() {
        assertEquals(
            "Heltec T114 · v1.17.0 (13 Aug 2026)",
            deviceIdentityLine(info("Heltec T114", "v1.17.0", "13 Aug 2026")),
        )
    }

    @Test
    fun `each part can be missing on its own`() {
        assertEquals("Heltec T114", deviceIdentityLine(info(board = "Heltec T114")))
        assertEquals("v1.17.0", deviceIdentityLine(info(version = "v1.17.0")))
        assertEquals(
            "Heltec T114 · v1.17.0",
            deviceIdentityLine(info("Heltec T114", "v1.17.0")),
        )
        assertEquals("v1.17.0 (13 Aug 2026)", deviceIdentityLine(info(version = "v1.17.0", built = "13 Aug 2026")))
    }

    @Test
    fun `nothing to say draws no row`() {
        // Firmware older than these fields, and no connection at all.
        assertNull(deviceIdentityLine(info()))
        assertNull(deviceIdentityLine(null))
        // A build date alone is not an identity — it names no version.
        assertNull(deviceIdentityLine(info(built = "13 Aug 2026")))
    }

    @Test
    fun `an empty build date does not leave dangling brackets`() {
        assertEquals("v1.17.0", deviceIdentityLine(info(version = "v1.17.0", built = "")))
    }
}
