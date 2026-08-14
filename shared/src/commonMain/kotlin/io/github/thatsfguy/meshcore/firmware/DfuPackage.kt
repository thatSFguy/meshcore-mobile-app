package io.github.thatsfguy.meshcore.firmware

import io.github.thatsfguy.meshcore.protocol.BufferReader
import io.github.thatsfguy.meshcore.protocol.TruncatedFrameException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A firmware package that cannot be used, with a reason a user can act on. */
class DfuPackageException(message: String) : Exception(message)

/**
 * Reads zip entries. Every MeshCore package observed so far stores its
 * three entries uncompressed, but nothing promises that, so this is a
 * real zip reader on each platform rather than an assumption.
 */
expect fun readZipEntries(zip: ByteArray): Map<String, ByteArray>

/**
 * An `adafruit-nrfutil` legacy DFU package — the `.zip` from
 * flasher.meshcore.io or a MeshCore release.
 *
 * Contents, from a real `RAK_4631_companion_radio_ble-v1.17.0` package:
 *
 * ```
 * manifest.json   465 B   names the other two
 * firmware.dat     14 B   the init packet the bootloader validates against
 * firmware.bin    483 KB  the image
 * ```
 *
 * **This file cannot tell you which board it is for.** The init
 * packet's device type is `0x0052` for every nRF52832/nRF52840 board
 * alike (`Adafruit_nRF52_Bootloader/src/dfu_init.c` compares it against
 * a single `ADAFRUIT_DEVICE_TYPE`), so a T114 package and a RAK 4631
 * package are indistinguishable to the bootloader and to us. Only the
 * filename carries the board. That is why the update flow names the
 * board in its confirmation and refuses to guess.
 */
class DfuPackage(
    val binFileName: String,
    val datFileName: String,
    /** The `.dat`, forwarded to the peer verbatim. */
    val initPacket: ByteArray,
    /** The `.bin`, the image itself. */
    val image: ByteArray,
    val deviceType: Int,
    val deviceRevision: Int,
    val applicationVersion: Long,
    val softDeviceRequirements: List<Int>,
) {
    val imageSize: Int get() = image.size

    companion object {
        /** `dfu_init.c`: every nRF52 board Adafruit's bootloader runs on. */
        const val NRF52_DEVICE_TYPE = 0x0052

        /**
         * A package big enough to be a lie. The largest nRF52840 image
         * is under 800 KB; anything beyond a few megabytes is either a
         * mis-picked file or an attempt to exhaust memory before the
         * user has confirmed anything.
         */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        fun read(zip: ByteArray): DfuPackage {
            val entries = try {
                readZipEntries(zip)
            } catch (e: Exception) {
                throw DfuPackageException("This file is not a readable zip archive.")
            }
            val manifestBytes = entries["manifest.json"]
                ?: throw DfuPackageException(
                    "No manifest.json — this is not a DFU package. nRF firmware is the " +
                        "\"zip\" download, not the .uf2 or .bin.",
                )
            val manifest = try {
                json.decodeFromString<ManifestFile>(manifestBytes.decodeToString()).manifest
            } catch (e: Exception) {
                throw DfuPackageException("The package manifest is not valid JSON.")
            }
            val application = manifest.application
                ?: throw DfuPackageException(
                    "This package updates the bootloader or SoftDevice, not the " +
                        "application. Use an application package.",
                )
            val image = entries[application.binFile]
                ?: throw DfuPackageException(
                    "The manifest names ${application.binFile} but the package does not " +
                        "contain it.",
                )
            val initPacket = entries[application.datFile]
                ?: throw DfuPackageException(
                    "The manifest names ${application.datFile} but the package does not " +
                        "contain it.",
                )
            if (image.isEmpty()) throw DfuPackageException("The firmware image is empty.")
            if (image.size > MAX_IMAGE_BYTES) {
                throw DfuPackageException(
                    "The firmware image is ${image.size / 1024} KB, far larger than any " +
                        "nRF52 image. Refusing to flash it.",
                )
            }
            if (initPacket.isEmpty()) throw DfuPackageException("The init packet is empty.")

            val header = parseInitPacket(initPacket)
            return DfuPackage(
                binFileName = application.binFile,
                datFileName = application.datFile,
                initPacket = initPacket,
                image = image,
                deviceType = header.deviceType,
                deviceRevision = header.deviceRevision,
                applicationVersion = header.applicationVersion,
                softDeviceRequirements = header.softDeviceRequirements,
            )
        }

        /**
         * The legacy init packet (`dfu_types.h`, `dfu_init_packet_t`):
         * device type u16, device revision u16, application version u32,
         * then a u16 count of accepted SoftDevice IDs and that many u16s.
         * Anything after is the extended packet — a CRC-16 on unsigned
         * builds, or length/hash/signature on signed ones. We forward it
         * untouched; only the header is read, and only to sanity-check
         * the file before a user commits to flashing it.
         */
        private fun parseInitPacket(bytes: ByteArray): InitPacketHeader = try {
            val r = BufferReader(bytes)
            val deviceType = r.readUInt16LE()
            val deviceRevision = r.readUInt16LE()
            val applicationVersion = r.readUInt32LE()
            val sdCount = r.readUInt16LE()
            if (sdCount > 64) {
                throw DfuPackageException("The init packet declares $sdCount SoftDevices.")
            }
            val softDevices = (0 until sdCount).map { r.readUInt16LE() }
            InitPacketHeader(deviceType, deviceRevision, applicationVersion, softDevices)
        } catch (e: TruncatedFrameException) {
            throw DfuPackageException("The init packet is truncated (${bytes.size} bytes).")
        }
    }

    private data class InitPacketHeader(
        val deviceType: Int,
        val deviceRevision: Int,
        val applicationVersion: Long,
        val softDeviceRequirements: List<Int>,
    )

    @Serializable
    private data class ManifestFile(val manifest: Manifest)

    @Serializable
    private data class Manifest(val application: ImageEntry? = null)

    @Serializable
    private data class ImageEntry(
        @SerialName("bin_file") val binFile: String,
        @SerialName("dat_file") val datFile: String,
    )
}
