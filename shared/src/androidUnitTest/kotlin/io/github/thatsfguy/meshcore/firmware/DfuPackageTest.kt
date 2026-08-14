package io.github.thatsfguy.meshcore.firmware

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reading an `adafruit-nrfutil` package.
 *
 * The fixtures are the real thing: `MANIFEST` and `INIT_PACKET` below
 * are copied byte for byte out of
 * `RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip`
 * (sha256 `c41430c4…`), so this pins what MeshCore actually ships
 * rather than what a package format document says it might.
 *
 * These live in androidUnitTest rather than commonTest because the zip
 * reader is an expect/actual and iOS has no implementation yet — a
 * commonTest here would fail the `iosSimulatorArm64Test` CI run that
 * exists to catch exactly that kind of platform leak.
 */
class DfuPackageTest {

    private val manifest = """
        {
            "manifest": {
                "application": {
                    "bin_file": "firmware.bin",
                    "dat_file": "firmware.dat",
                    "init_packet_data": {
                        "application_version": 4294967295,
                        "device_revision": 65535,
                        "device_type": 82,
                        "firmware_crc16": 27565,
                        "softdevice_req": [
                            182
                        ]
                    }
                },
                "dfu_version": 0.5
            }
        }
    """.trimIndent()

    /** The 14 bytes of `firmware.dat` from that package. */
    private val initPacket = byteArrayOf(
        0x52, 0x00, // device type 0x0052
        0xFF.toByte(), 0xFF.toByte(), // device revision 0xFFFF
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // app version
        0x01, 0x00, // one SoftDevice requirement
        0xB6.toByte(), 0x00, // s140 6.1.1 = 182
        0xAD.toByte(), 0x6B, // firmware CRC-16 = 27565
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun goodPackage(image: ByteArray = ByteArray(1024) { it.toByte() }) = zipOf(
        "manifest.json" to manifest.encodeToByteArray(),
        "firmware.dat" to initPacket,
        "firmware.bin" to image,
    )

    @Test
    fun `a real package parses into an init packet and an image`() {
        val image = ByteArray(1024) { it.toByte() }
        val pkg = DfuPackage.read(goodPackage(image))
        assertEquals("firmware.bin", pkg.binFileName)
        assertEquals("firmware.dat", pkg.datFileName)
        assertContentEquals(initPacket, pkg.initPacket)
        assertContentEquals(image, pkg.image)
        assertEquals(1024, pkg.imageSize)
    }

    @Test
    fun `the init packet header decodes to the values the manifest declares`() {
        // The manifest and the .dat state the same facts twice; the
        // bootloader only ever reads the .dat, so that is what we parse.
        // If these two ever disagree in a shipped package, this is the
        // test that says which one we followed.
        val pkg = DfuPackage.read(goodPackage())
        assertEquals(82, pkg.deviceType)
        assertEquals(DfuPackage.NRF52_DEVICE_TYPE, pkg.deviceType)
        assertEquals(65535, pkg.deviceRevision)
        assertEquals(4294967295L, pkg.applicationVersion)
        assertEquals(listOf(182), pkg.softDeviceRequirements)
    }

    @Test
    fun `the device type does not identify the board`() {
        // 0x0052 is every nRF52 board Adafruit's bootloader runs on, so
        // a RAK 4631 package and a T114 package are identical here. This
        // is not a gap to fix — it is the reason the update flow makes
        // the user confirm a board name.
        val pkg = DfuPackage.read(goodPackage())
        assertEquals(DfuPackage.NRF52_DEVICE_TYPE, pkg.deviceType)
    }

    // --- files a user can plausibly pick by mistake ------------------------

    @Test
    fun `a uf2 or bin picked instead of the zip says so`() {
        // The release page offers .uf2, .bin and .zip for the same board
        // and only the zip can go over the air.
        val e = assertFailsWith<DfuPackageException> {
            DfuPackage.read(ByteArray(2048) { 0x55 })
        }
        assertTrue(e.message!!.isNotBlank())
    }

    @Test
    fun `a zip with no manifest names the actual problem`() {
        val e = assertFailsWith<DfuPackageException> {
            DfuPackage.read(zipOf("firmware.bin" to ByteArray(16)))
        }
        assertTrue(e.message!!.contains("manifest.json"), e.message!!)
    }

    @Test
    fun `a bootloader or SoftDevice package is refused`() {
        // Those packages carry `softdevice_bootloader`, not
        // `application`. Flashing one through the application path is
        // how a node stops booting.
        val other = """{"manifest":{"softdevice_bootloader":{"bin_file":"sd.bin"}}}"""
        val e = assertFailsWith<DfuPackageException> {
            DfuPackage.read(zipOf("manifest.json" to other.encodeToByteArray()))
        }
        assertTrue(e.message!!.contains("application"), e.message!!)
    }

    @Test
    fun `a manifest naming a file the package does not hold is refused`() {
        val e = assertFailsWith<DfuPackageException> {
            DfuPackage.read(
                zipOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "firmware.dat" to initPacket,
                    // no firmware.bin
                ),
            )
        }
        assertTrue(e.message!!.contains("firmware.bin"), e.message!!)
    }

    @Test
    fun `malformed manifest JSON is refused rather than half-read`() {
        for (bad in listOf("", "{", "[]", "null", "{\"manifest\":42}", "not json at all")) {
            assertFailsWith<DfuPackageException>("accepted \"$bad\"") {
                DfuPackage.read(
                    zipOf(
                        "manifest.json" to bad.encodeToByteArray(),
                        "firmware.dat" to initPacket,
                        "firmware.bin" to ByteArray(16),
                    ),
                )
            }
        }
    }

    @Test
    fun `an empty image or init packet is refused`() {
        assertFailsWith<DfuPackageException> {
            DfuPackage.read(
                zipOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "firmware.dat" to initPacket,
                    "firmware.bin" to ByteArray(0),
                ),
            )
        }
        assertFailsWith<DfuPackageException> {
            DfuPackage.read(
                zipOf(
                    "manifest.json" to manifest.encodeToByteArray(),
                    "firmware.dat" to ByteArray(0),
                    "firmware.bin" to ByteArray(16),
                ),
            )
        }
    }

    @Test
    fun `a truncated init packet is refused rather than read past its end`() {
        for (length in 0 until initPacket.size) {
            val truncated = initPacket.copyOfRange(0, length)
            if (truncated.isEmpty()) continue
            // 10 bytes is a complete header with a zero SoftDevice count
            // only if the count field itself survived; everything short
            // of a whole header must fail.
            val result = runCatching {
                DfuPackage.read(
                    zipOf(
                        "manifest.json" to manifest.encodeToByteArray(),
                        "firmware.dat" to truncated,
                        "firmware.bin" to ByteArray(16),
                    ),
                )
            }
            if (length < 10) {
                assertTrue(
                    result.exceptionOrNull() is DfuPackageException,
                    "accepted a $length-byte init packet",
                )
            }
        }
    }

    @Test
    fun `an absurdly large image is refused before anything is flashed`() {
        val huge = ByteArray(DfuPackage.MAX_IMAGE_BYTES + 1)
        val e = assertFailsWith<DfuPackageException> { DfuPackage.read(goodPackage(huge)) }
        assertTrue(e.message!!.contains("Refusing"), e.message!!)
    }

    @Test
    fun `a package parses into a session that sends exactly the image`() {
        // The two halves of this feature, joined: whatever the package
        // holds is what goes on the wire, unmodified.
        val image = ByteArray(333) { (it % 251).toByte() }
        val pkg = DfuPackage.read(goodPackage(image))
        val session = LegacyDfuSession(pkg.initPacket, pkg.image)
        session.start()
        session.onNotification(byteArrayOf(16, LegacyDfu.OP_START_DFU.toByte(), 1))
        var actions = session.onNotification(byteArrayOf(16, LegacyDfu.OP_RECEIVE_INIT.toByte(), 1))
        val sent = ByteArrayOutputStream()
        actions.filterIsInstance<DfuAction.WritePacket>().forEach { sent.write(it.bytes) }
        while (session.bytesSent < image.size) {
            val n = session.bytesSent
            actions = session.onNotification(
                byteArrayOf(
                    17,
                    (n and 0xFF).toByte(),
                    ((n shr 8) and 0xFF).toByte(),
                    ((n shr 16) and 0xFF).toByte(),
                    ((n shr 24) and 0xFF).toByte(),
                ),
            )
            actions.filterIsInstance<DfuAction.WritePacket>().forEach { sent.write(it.bytes) }
        }
        assertContentEquals(image, sent.toByteArray())
    }
}
