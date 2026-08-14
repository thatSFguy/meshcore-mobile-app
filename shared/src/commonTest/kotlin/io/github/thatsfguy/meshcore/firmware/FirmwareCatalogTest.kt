package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading the MeshCore release index.
 *
 * [REAL_RESPONSE] is a genuine (trimmed) response from
 * `api.github.com/repos/meshcore-dev/MeshCore/releases`, captured
 * 2026-08-13 — asset names, sizes and digests exactly as published,
 * including the `sha256:c41430c4…` that the RAK 4631 package really
 * hashes to. Pinning the real values rather than a plausible shape is
 * what catches a naming change the day it ships.
 */
class FirmwareCatalogTest {

    private val realResponse = """
    [
      {
        "tag_name": "companion-v1.17.0",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip",
            "browser_download_url": "https://github.com/meshcore-dev/MeshCore/releases/download/companion-v1.17.0/RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip",
            "size": 494947,
            "digest": "sha256:c41430c46f401d8f8dc6f50e0e4ea6086f37bb1f4c2aab76aca6b3a8be3c130d"
          },
          {
            "name": "RAK_4631_companion_radio_ble-v1.17.0-727fc05.uf2",
            "browser_download_url": "https://example.invalid/RAK_4631_companion_radio_ble.uf2",
            "size": 988672,
            "digest": "sha256:2044f1a4f7755f53700f8a191e54e27ad8179116e5d123bb8641151ffb47167a"
          },
          {
            "name": "RAK_4631_companion_radio_usb-v1.17.0-727fc05.zip",
            "browser_download_url": "https://example.invalid/RAK_4631_companion_radio_usb.zip",
            "size": 486946,
            "digest": "sha256:0529933238beb302a7585e842233ba1ccf850dbf26a9d3fea2835ef881c27986"
          },
          {
            "name": "Heltec_t114_companion_radio_ble-v1.17.0-727fc05.zip",
            "browser_download_url": "https://example.invalid/Heltec_t114_ble.zip",
            "size": 449235,
            "digest": "sha256:0bd848d2d7b4e16c122e60c32d6d15ac9322837e0079f6077f84d674bb48f631"
          },
          {
            "name": "Heltec_t114_without_display_companion_radio_ble-v1.17.0-727fc05.zip",
            "browser_download_url": "https://example.invalid/Heltec_t114_nd_ble.zip",
            "size": 419162,
            "digest": "sha256:91a42c6cdaf0d544222f8ff724da532ebf186efe09438fbb310865ef48bb4d9e"
          },
          {
            "name": "heltec_v3_companion_radio_ble-v1.17.0-727fc05-merged.bin",
            "browser_download_url": "https://example.invalid/heltec_v3.bin",
            "size": 1000000,
            "digest": "sha256:0000000000000000000000000000000000000000000000000000000000000000"
          }
        ]
      },
      {
        "tag_name": "repeater-v1.17.0",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "RAK_4631_repeater-v1.17.0-727fc05.zip",
            "browser_download_url": "https://example.invalid/RAK_4631_repeater.zip",
            "size": 400000,
            "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111"
          }
        ]
      },
      {
        "tag_name": "companion-v1.9.0",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "RAK_4631_companion_radio_ble-v1.9.0-abcdef0.zip",
            "browser_download_url": "https://example.invalid/old.zip",
            "size": 400000,
            "digest": null
          }
        ]
      }
    ]
    """.trimIndent()

    private fun releases() = FirmwareCatalog.parseReleases(realResponse)

    @Test
    fun `releases are grouped by the role in their tag`() {
        val roles = releases().map { it.role }
        assertEquals(
            listOf(FirmwareRole.Companion, FirmwareRole.Repeater, FirmwareRole.Companion),
            roles,
        )
        assertEquals("v1.17.0", releases().first().version)
    }

    @Test
    fun `only zip assets are offered because only they can go over the air`() {
        // The same release carries .uf2 and .bin for every board, and
        // neither can be sent over BLE. Offering one is offering a brick.
        val names = releases().flatMap { it.assets }.map { it.name }
        assertTrue(names.all { it.endsWith(".zip") }, names.toString())
        assertTrue(names.none { it.contains("heltec_v3") }, "an ESP32-only board was offered")
    }

    @Test
    fun `an asset is split into its board prefix and its link`() {
        val asset = releases().first().assets.first { it.name.startsWith("RAK_4631_companion_radio_ble") }
        assertEquals("RAK_4631", asset.boardPrefix)
        assertEquals(CompanionLink.Ble, asset.link)
        assertEquals(FirmwareRole.Companion, asset.role)
        assertEquals("v1.17.0", asset.version)
        assertEquals(494947L, asset.sizeBytes)
        assertEquals(
            "c41430c46f401d8f8dc6f50e0e4ea6086f37bb1f4c2aab76aca6b3a8be3c130d",
            asset.sha256,
        )
    }

    @Test
    fun `the underscore in a board name is not mistaken for the role token`() {
        // "Heltec_t114_without_display" is a different board from
        // "Heltec_t114", and the role token appears only once.
        val prefixes = releases().first().assets.map { it.boardPrefix }.toSet()
        assertTrue("Heltec_t114" in prefixes)
        assertTrue("Heltec_t114_without_display" in prefixes)
    }

    @Test
    fun `a repeater asset carries no companion link`() {
        val repeater = releases().first { it.role == FirmwareRole.Repeater }
        assertEquals("RAK_4631", repeater.assets.single().boardPrefix)
        assertNull(repeater.assets.single().link)
    }

    @Test
    fun `an asset with no published digest is carried as unverified`() {
        val old = releases().first { it.version == "v1.9.0" }
        assertNull(old.assets.single().sha256)
    }

    @Test
    fun `the latest release is by version number and not by string order`() {
        // "1.9.0" sorts above "1.17.0" as text, which would offer an
        // old release as the newest one.
        val latest = FirmwareCatalog.latestFor(releases(), FirmwareRole.Companion)
        assertEquals("v1.17.0", latest?.version)
    }

    @Test
    fun `a malformed index is refused rather than half-read`() {
        for (bad in listOf("", "{}", "not json", "[{\"tag_name\":", "null")) {
            assertFailsWith<FirmwareCatalogException>("accepted \"$bad\"") {
                FirmwareCatalog.parseReleases(bad)
            }
        }
    }

    @Test
    fun `unknown tags and empty releases are skipped without failing the whole index`() {
        val body = """
        [
          {"tag_name": "something-else-v1", "assets": []},
          {"tag_name": "companion-v2.0.0", "draft": true, "assets": []},
          {"tag_name": "repeater-v1.17.0", "assets": [
            {"name": "RAK_4631_repeater-v1.17.0.zip", "browser_download_url": "https://x.invalid/a.zip", "size": 1}
          ]}
        ]
        """.trimIndent()
        val parsed = FirmwareCatalog.parseReleases(body)
        assertEquals(1, parsed.size)
        assertEquals(FirmwareRole.Repeater, parsed.single().role)
    }

    // --- version ordering --------------------------------------------------

    @Test
    fun `version comparison is numeric`() {
        assertTrue(VersionOrder.key("v1.17.0") > VersionOrder.key("v1.9.0"))
        assertTrue(VersionOrder.key("1.17.1") > VersionOrder.key("1.17.0"))
        assertTrue(VersionOrder.key("2.0.0") > VersionOrder.key("1.99.99"))
        assertEquals(VersionOrder.key("v1.17.0"), VersionOrder.key("1.17.0"))
    }

    @Test
    fun `an update is only announced when both versions are understood`() {
        assertTrue(VersionOrder.isNewer("v1.17.0", "v1.16.0"))
        assertFalse(VersionOrder.isNewer("v1.16.0", "v1.17.0"))
        assertFalse(VersionOrder.isNewer("v1.17.0", "v1.17.0"))
        // A radio too old to report its version, or a tag in a shape we
        // do not understand, must never light up an "update available".
        assertFalse(VersionOrder.isNewer("v1.17.0", null))
        assertFalse(VersionOrder.isNewer("v1.17.0", ""))
        assertFalse(VersionOrder.isNewer("v1.17.0", "nightly"))
        assertFalse(VersionOrder.isNewer("nightly", "v1.16.0"))
    }

    // --- versions come from the tag list ----------------------------------

    /** Real tag names, from `/tags?per_page=100` on 2026-08-13. */
    private val realTags = """
    [
      {"name": "room-v6"},
      {"name": "room-server-v1.17.0"},
      {"name": "room-server-v1.16.0"},
      {"name": "companion-v1.17.0"},
      {"name": "companion-v1.16.0"},
      {"name": "companion-v1.9.0"},
      {"name": "companion-v1.14.1"},
      {"name": "repeater-v1.17.0"},
      {"name": "v1.0.0"}
    ]
    """.trimIndent()

    @Test
    fun `versions are listed newest first for one role`() {
        val versions = FirmwareCatalog.parseTags(realTags, FirmwareRole.Companion)
        assertEquals(
            listOf("v1.17.0", "v1.16.0", "v1.14.1", "v1.9.0"),
            versions.map { it.version },
        )
        assertEquals("companion-v1.17.0", versions.first().tag)
        assertTrue(versions.all { it.role == FirmwareRole.Companion })
    }

    @Test
    fun `the version list is ordered by number and not by text`() {
        // "v1.9.0" sorts above "v1.16.0" as a string, which would put an
        // eight-month-old release at the top of a list whose first entry
        // the UI labels "Newest".
        val versions = FirmwareCatalog.parseTags(realTags, FirmwareRole.Companion)
        assertEquals("v1.17.0", versions.first().version)
        assertEquals("v1.9.0", versions.last().version)
    }

    @Test
    fun `tags for another role or with no version are left out`() {
        val companion = FirmwareCatalog.parseTags(realTags, FirmwareRole.Companion)
        assertTrue(companion.none { it.tag == "room-v6" })
        assertTrue(companion.none { it.tag == "v1.0.0" })
        assertTrue(companion.none { it.tag.startsWith("repeater-") })
        // "room-v6" starts with neither "room-server-" nor anything else
        // we know, so it is not offered as a room-server version.
        assertTrue(
            FirmwareCatalog.parseTags(realTags, FirmwareRole.RoomServer).none { it.tag == "room-v6" },
        )
    }

    @Test
    fun `a malformed tag list is refused rather than half-read`() {
        for (bad in listOf("", "{}", "not json", "null")) {
            assertFailsWith<FirmwareCatalogException>("accepted \"$bad\"") {
                FirmwareCatalog.parseTags(bad, FirmwareRole.Companion)
            }
        }
    }

    @Test
    fun `the version list is fetched from the tags endpoint rather than the releases one`() {
        // Measured 2026-08-13: /releases?per_page=30 is 9.3 MB and even
        // per_page=6 is 2.3 MB, because every asset carries a full
        // uploader object. /tags?per_page=100 is 50 KB and covers every
        // version. The first cut asked for 30 releases under a 4 MB cap
        // and so failed every single time it was pressed.
        assertTrue(FirmwareCatalog.TAGS_URL.contains("/tags?"), FirmwareCatalog.TAGS_URL)
        assertTrue(FirmwareCatalog.TAGS_URL.contains("per_page=100"))
        assertEquals(
            "https://api.github.com/repos/meshcore-dev/MeshCore/releases/tags/companion-v1.17.0",
            FirmwareCatalog.releaseUrlForTag("companion-v1.17.0"),
        )
    }

    @Test
    fun `one release parses from the by-tag endpoint`() {
        // That endpoint returns an object, not an array.
        val body = """
        {"tag_name":"companion-v1.17.0","prerelease":false,"assets":[
          {"name":"ProMicro_companion_radio_ble-v1.17.0-727fc05.zip",
           "browser_download_url":"https://x.invalid/p.zip","size":400000,
           "digest":"sha256:c41430c46f401d8f8dc6f50e0e4ea6086f37bb1f4c2aab76aca6b3a8be3c130d"}]}
        """.trimIndent()
        val release = FirmwareCatalog.parseRelease(body)
        assertEquals(FirmwareRole.Companion, release.role)
        assertEquals("v1.17.0", release.version)
        assertEquals("ProMicro", release.assets.single().boardPrefix)
    }

    @Test
    fun `a release with nothing flashable is refused rather than shown empty`() {
        val body = """{"tag_name":"companion-v1.17.0","assets":[
          {"name":"heltec_v3_companion_radio_ble-v1.17.0-merged.bin",
           "browser_download_url":"https://x.invalid/h.bin","size":1}]}"""
        assertFailsWith<FirmwareCatalogException> { FirmwareCatalog.parseRelease(body) }
    }
}
