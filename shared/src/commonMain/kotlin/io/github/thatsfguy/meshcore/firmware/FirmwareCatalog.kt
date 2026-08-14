package io.github.thatsfguy.meshcore.firmware

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a node runs, which is a different release series for each. */
enum class FirmwareRole(val tagPrefix: String, val assetToken: String) {
    Companion("companion-", "_companion_radio_"),
    Repeater("repeater-", "_repeater"),
    RoomServer("room-server-", "_room_server"),
}

/** How a companion build talks to a phone. Not applicable to the other roles. */
enum class CompanionLink { Ble, Usb }

/**
 * One over-the-air-flashable file from a MeshCore release.
 *
 * Only `.zip` assets appear here. That is not a simplification — the
 * `.bin` and `.uf2` files in the same release cannot be sent over BLE
 * at all, and offering them would be offering a brick.
 */
data class FirmwareAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    /** From the release API's `digest` field; null on older assets. */
    val sha256: String?,
    /** The PlatformIO environment name, e.g. `Heltec_t114`. */
    val boardPrefix: String,
    val role: FirmwareRole,
    val link: CompanionLink?,
    val version: String,
)

/**
 * A published version of one role's firmware, from the tag list.
 *
 * Cheap: knowing a version exists costs nothing, and the release itself
 * is only fetched once someone picks one.
 */
data class FirmwareVersion(
    val role: FirmwareRole,
    /** e.g. `v1.17.0`, matching what a radio reports for itself. */
    val version: String,
    /** e.g. `companion-v1.17.0`. */
    val tag: String,
)

data class FirmwareRelease(
    val role: FirmwareRole,
    val version: String,
    val tag: String,
    val prerelease: Boolean,
    val assets: List<FirmwareAsset>,
)

/**
 * The MeshCore release index, read from the GitHub releases API.
 *
 * Releases are tagged per role — `companion-v1.17.0`, `repeater-v1.17.0`,
 * `room-server-v1.17.0` — and each carries one asset per board, named
 * after the PlatformIO environment that built it:
 * `Heltec_t114_companion_radio_ble-v1.17.0-727fc05.zip`.
 *
 * Everything here is parsing. Fetching is [FirmwareDownloader], and the
 * two are separate so the index logic is testable against a captured
 * API response with no network at all.
 */
object FirmwareCatalog {

    /**
     * Every published version, in 50 KB.
     *
     * The releases endpoint is not usable for this. Its response
     * carries every asset of every release, each with a full uploader
     * object, and a MeshCore release has 100+ assets — measured
     * 2026-08-13, `?per_page=30` is **9.3 MB** and even `?per_page=6` is
     * 2.3 MB for two versions. The tag list is 50 KB for a hundred, and
     * a single release fetched by tag is 633 KB.
     *
     * So: list versions from the tags, and pull exactly one release once
     * the user has chosen. Cheaper than the old path *and* it offers
     * every version rather than the newest two.
     */
    const val TAGS_URL =
        "https://api.github.com/repos/meshcore-dev/MeshCore/tags?per_page=100"

    const val TAGS_MAX_BYTES = 2L * 1024 * 1024

    /** One release, by tag. ~633 KB. */
    fun releaseUrlForTag(tag: String): String =
        "https://api.github.com/repos/meshcore-dev/MeshCore/releases/tags/$tag"

    const val RELEASE_MAX_BYTES = 8L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Versions for [role], newest first.
     *
     * Sorted here rather than trusting the API's order — which is not
     * chronological — and by number rather than as text, because
     * `v1.9.0` sorts above `v1.17.0` as a string and would offer an
     * eight-month-old release as the newest.
     */
    fun parseTags(body: String, role: FirmwareRole): List<FirmwareVersion> {
        val tags = try {
            json.decodeFromString<List<GithubTag>>(body)
        } catch (e: Exception) {
            throw FirmwareCatalogException("The version list could not be read.")
        }
        return tags
            .filter { it.name.startsWith(role.tagPrefix) }
            .map { FirmwareVersion(role, it.name.removePrefix(role.tagPrefix), it.name) }
            // A tag shape nobody anticipated ("room-v6" is in there)
            // sorts to zero, so it is dropped rather than offered as if
            // it were a version.
            .filter { VersionOrder.key(it.version) > 0 }
            .distinctBy { it.tag }
            .sortedByDescending { VersionOrder.key(it.version) }
    }

    /** One release, from the by-tag endpoint (an object, not a list). */
    fun parseRelease(body: String): FirmwareRelease {
        val raw = try {
            json.decodeFromString<GithubRelease>(body)
        } catch (e: Exception) {
            throw FirmwareCatalogException("That release could not be read.")
        }
        val role = FirmwareRole.entries.firstOrNull { raw.tagName.startsWith(it.tagPrefix) }
            ?: throw FirmwareCatalogException("${raw.tagName} is not a firmware release.")
        val version = raw.tagName.removePrefix(role.tagPrefix)
        val assets = raw.assets.mapNotNull { parseAsset(it, role, version) }
        if (assets.isEmpty()) {
            throw FirmwareCatalogException(
                "${raw.tagName} publishes nothing that can be sent over Bluetooth.",
            )
        }
        return FirmwareRelease(role, version, raw.tagName, raw.prerelease, assets)
    }

    fun parseReleases(body: String): List<FirmwareRelease> {
        val raw = try {
            json.decodeFromString<List<GithubRelease>>(body)
        } catch (e: Exception) {
            throw FirmwareCatalogException("The release index could not be read.")
        }
        return raw.filterNot { it.draft }.mapNotNull { release ->
            val role = FirmwareRole.entries.firstOrNull { release.tagName.startsWith(it.tagPrefix) }
                ?: return@mapNotNull null
            val version = release.tagName.removePrefix(role.tagPrefix)
            val assets = release.assets.mapNotNull { asset -> parseAsset(asset, role, version) }
            if (assets.isEmpty()) {
                null
            } else {
                FirmwareRelease(role, version, release.tagName, release.prerelease, assets)
            }
        }
    }

    private fun parseAsset(
        asset: GithubAsset,
        role: FirmwareRole,
        version: String,
    ): FirmwareAsset? {
        if (!asset.name.endsWith(".zip", ignoreCase = true)) return null
        val token = asset.name.indexOf(role.assetToken)
        if (token <= 0) return null
        val prefix = asset.name.substring(0, token)
        val link = when {
            role != FirmwareRole.Companion -> null
            asset.name.contains("_companion_radio_ble") -> CompanionLink.Ble
            asset.name.contains("_companion_radio_usb") -> CompanionLink.Usb
            else -> return null
        }
        return FirmwareAsset(
            name = asset.name,
            url = asset.browserDownloadUrl,
            sizeBytes = asset.size,
            sha256 = asset.digest?.removePrefix("sha256:")?.takeIf { it.length == 64 },
            boardPrefix = prefix,
            role = role,
            link = link,
            version = version,
        )
    }

    /** The newest non-prerelease for [role], or null if there is none. */
    fun latestFor(releases: List<FirmwareRelease>, role: FirmwareRole): FirmwareRelease? =
        releases.filter { it.role == role && !it.prerelease }
            .maxByOrNull { VersionOrder.key(it.version) }

    @Serializable
    private data class GithubTag(val name: String)

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
        val size: Long = 0,
        val digest: String? = null,
    )
}

class FirmwareCatalogException(message: String) : Exception(message)

/**
 * Comparing `v1.9.0` with `v1.17.0`.
 *
 * String order gets this wrong — `"v1.9.0" > "v1.17.0"` — which would
 * offer an eight-month-old release as the newest one. Numbers are
 * compared as numbers; anything unparseable sorts below everything, so
 * a tag nobody anticipated is never mistaken for the latest.
 */
object VersionOrder {
    fun key(version: String): Long {
        val parts = version.trimStart('v', 'V').split('.', '-', '+')
        val nums = parts.take(3).map { part ->
            part.takeWhile { it.isDigit() }.toLongOrNull() ?: return 0L
        }
        if (nums.isEmpty()) return 0L
        val major = nums.getOrElse(0) { 0 }
        val minor = nums.getOrElse(1) { 0 }
        val patch = nums.getOrElse(2) { 0 }
        return major * 1_000_000 + minor * 1_000 + patch
    }

    /** True when [candidate] is a strictly newer release than [current]. */
    fun isNewer(candidate: String, current: String?): Boolean {
        if (current.isNullOrBlank()) return false
        val a = key(candidate)
        val b = key(current)
        // A version we cannot parse is never announced as an upgrade.
        return a != 0L && b != 0L && a > b
    }
}
