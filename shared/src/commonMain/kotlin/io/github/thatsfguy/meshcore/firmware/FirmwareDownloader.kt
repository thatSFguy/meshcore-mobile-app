package io.github.thatsfguy.meshcore.firmware

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import io.github.thatsfguy.meshcore.util.toHex

/**
 * Fetches a URL over HTTPS. The one network call this feature makes,
 * behind an interface so the catalogue and the verification logic can
 * be tested without one.
 */
interface HttpFetcher {
    /**
     * GET [url], following redirects, failing on any non-2xx status.
     * [maxBytes] is a hard cap — a response that exceeds it is abandoned
     * rather than buffered.
     */
    suspend fun get(url: String, maxBytes: Long): ByteArray
}

class FirmwareDownloadException(message: String) : Exception(message)

/**
 * Downloading firmware, which is the one thing this app fetches that it
 * then executes on someone else's hardware.
 *
 * Two rules follow from that, and both are enforced here rather than
 * left to the UI:
 *
 * 1. **The download is verified against the hash the release API
 *    reported**, not against a hash from the same response that carried
 *    the bytes. The index and the asset are separate requests, so a
 *    tampered download has to survive a comparison with something
 *    fetched earlier.
 * 2. **An asset with no published digest is not silently trusted.** It
 *    is reported as unverified so the UI can say so before a user
 *    commits to flashing it.
 *
 * This is the app's second outbound network surface after map tiles. It
 * runs only when a user asks it to.
 */
class FirmwareDownloader(
    private val http: HttpFetcher,
    private val crypto: CryptoProvider,
) {

    /** Hard ceiling on any release asset. The largest is well under 1 MB. */
    private val maxAssetBytes = 8L * 1024 * 1024

    /**
     * Every published version of [role]'s firmware, newest first.
     *
     * Read from the tag list, which is 50 KB — the releases endpoint
     * carries every asset of every release and runs to megabytes. See
     * [FirmwareCatalog.TAGS_URL].
     */
    suspend fun listVersions(role: FirmwareRole): List<FirmwareVersion> {
        val body = try {
            http.get(FirmwareCatalog.TAGS_URL, FirmwareCatalog.TAGS_MAX_BYTES)
        } catch (e: Exception) {
            throw FirmwareCatalogException(
                e.message ?: "Could not reach the MeshCore version list.",
            )
        }
        return FirmwareCatalog.parseTags(body.decodeToString(), role)
    }

    /** The assets of one release, fetched only once a version is chosen. */
    suspend fun loadRelease(version: FirmwareVersion): FirmwareRelease {
        val body = try {
            http.get(
                FirmwareCatalog.releaseUrlForTag(version.tag),
                FirmwareCatalog.RELEASE_MAX_BYTES,
            )
        } catch (e: Exception) {
            throw FirmwareCatalogException(
                e.message ?: "Could not read the ${version.version} release.",
            )
        }
        return FirmwareCatalog.parseRelease(body.decodeToString())
    }

    /**
     * Download [asset] and check it against its published digest.
     *
     * Throws rather than returning a flag on mismatch: there is no
     * "proceed anyway" for a firmware image that is not the one the
     * release published.
     */
    suspend fun download(asset: FirmwareAsset): DownloadedFirmware {
        val bytes = try {
            http.get(asset.url, maxBytes = maxAssetBytes)
        } catch (e: Exception) {
            throw FirmwareDownloadException(e.message ?: "The download failed.")
        }
        if (asset.sizeBytes > 0 && bytes.size.toLong() != asset.sizeBytes) {
            throw FirmwareDownloadException(
                "The download is ${bytes.size} bytes but the release lists " +
                    "${asset.sizeBytes}. Refusing to flash it.",
            )
        }
        val actual = crypto.sha256(bytes).toHex()
        val expected = asset.sha256
        if (expected != null && !actual.equals(expected, ignoreCase = true)) {
            throw FirmwareDownloadException(
                "The download does not match the checksum the release published. " +
                    "Refusing to flash it.",
            )
        }
        return DownloadedFirmware(
            bytes = bytes,
            sha256 = actual,
            verifiedAgainstRelease = expected != null,
            sourceDescription = asset.name,
        )
    }

    /** Hash a package the user picked off their own device. */
    fun describeLocal(bytes: ByteArray, name: String): DownloadedFirmware = DownloadedFirmware(
        bytes = bytes,
        sha256 = crypto.sha256(bytes).toHex(),
        verifiedAgainstRelease = false,
        sourceDescription = name,
    )
}

/**
 * A firmware package in hand, with its provenance.
 *
 * [verifiedAgainstRelease] is false for anything picked off the device
 * and for a release asset that published no digest. The UI shows the
 * hash either way — someone who wants to check it against
 * flasher.meshcore.io can.
 */
data class DownloadedFirmware(
    val bytes: ByteArray,
    val sha256: String,
    val verifiedAgainstRelease: Boolean,
    val sourceDescription: String,
) {
    override fun equals(other: Any?): Boolean =
        other is DownloadedFirmware && sha256 == other.sha256 &&
            sourceDescription == other.sourceDescription

    override fun hashCode(): Int = sha256.hashCode()
}
