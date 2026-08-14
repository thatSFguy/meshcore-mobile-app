package io.github.thatsfguy.meshcore.firmware

import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Downloading firmware — the one thing this app fetches and then runs
 * on someone else's hardware.
 *
 * The property under test is that a package which is not exactly what
 * the release published never reaches a radio. There is no "proceed
 * anyway": the bootloader will happily flash whatever it is handed, and
 * its own hash check only catches corruption, not substitution.
 */
class FirmwareDownloaderTest {

    /** SHA-256 only; nothing else here needs the real provider. */
    private class Sha256Only(private val digest: (ByteArray) -> ByteArray) : CryptoProvider {
        override fun sha256(data: ByteArray) = digest(data)
        override fun sha512(data: ByteArray) = notNeeded()
        override fun hmacSha256(key: ByteArray, data: ByteArray) = notNeeded()
        override fun aesEcbDecrypt(key16: ByteArray, ciphertext: ByteArray) = notNeeded()
        override fun aesEcbEncrypt(key16: ByteArray, plaintext: ByteArray) = notNeeded()
        override fun generateEd25519Seed() = notNeeded()
        override fun ed25519PublicKey(seed: ByteArray) = notNeeded()
        override fun ed25519Sign(message: ByteArray, seed: ByteArray) = notNeeded()
        override fun ed25519Verify(
            signature: ByteArray,
            message: ByteArray,
            publicKey: ByteArray,
        ) = false

        override fun randomBytes(length: Int) = ByteArray(length)

        override fun aesGcmSeal(
            key32: ByteArray,
            nonce12: ByteArray,
            plaintext: ByteArray,
            aad: ByteArray,
        ) = notNeeded()

        override fun aesGcmOpen(
            key32: ByteArray,
            nonce12: ByteArray,
            ciphertextAndTag: ByteArray,
            aad: ByteArray,
        ): ByteArray? = notNeeded()

        private fun notNeeded(): Nothing = throw UnsupportedOperationException()
    }

    /**
     * Stands in for SHA-256: the "hash" of a payload is its first byte
     * repeated. Enough to tell "matches" from "does not" without
     * needing a real digest in commonTest, where no platform provider
     * exists.
     */
    private val crypto = Sha256Only { data -> ByteArray(32) { data.firstOrNull() ?: 0 } }

    private fun hashOf(firstByte: Int) =
        (0 until 32).joinToString("") { firstByte.toString(16).padStart(2, '0') }

    private class FakeHttp(private val responses: Map<String, ByteArray>) : HttpFetcher {
        var requested = mutableListOf<String>()

        override suspend fun get(url: String, maxBytes: Long): ByteArray {
            requested += url
            return responses[url] ?: throw FirmwareDownloadException("404")
        }
    }

    private fun asset(
        sha: String? = hashOf(0x41),
        size: Long = 4,
    ) = FirmwareAsset(
        name = "RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip",
        url = "https://example.invalid/rak.zip",
        sizeBytes = size,
        sha256 = sha,
        boardPrefix = "RAK_4631",
        role = FirmwareRole.Companion,
        link = CompanionLink.Ble,
        version = "v1.17.0",
    )

    private val payload = byteArrayOf(0x41, 0x42, 0x43, 0x44)

    @Test
    fun `a download matching its published digest is accepted`() = runTest {
        val http = FakeHttp(mapOf("https://example.invalid/rak.zip" to payload))
        val result = FirmwareDownloader(http, crypto).download(asset())
        assertEquals(hashOf(0x41), result.sha256)
        assertTrue(result.verifiedAgainstRelease)
        assertEquals("RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip", result.sourceDescription)
    }

    @Test
    fun `a download that does not match its digest is refused`() = runTest {
        // Substitution, not corruption: the bootloader's own hash check
        // would pass on this file, because whoever swapped it also
        // rebuilt the init packet.
        val http = FakeHttp(mapOf("https://example.invalid/rak.zip" to byteArrayOf(0x99.toByte(), 1, 2, 3)))
        val e = assertFailsWith<FirmwareDownloadException> {
            FirmwareDownloader(http, crypto).download(asset())
        }
        assertTrue(e.message!!.contains("Refusing"), e.message!!)
    }

    @Test
    fun `a download of the wrong length is refused before it is hashed`() = runTest {
        val http = FakeHttp(mapOf("https://example.invalid/rak.zip" to payload))
        val e = assertFailsWith<FirmwareDownloadException> {
            FirmwareDownloader(http, crypto).download(asset(size = 999_999))
        }
        assertTrue(e.message!!.contains("Refusing"), e.message!!)
    }

    @Test
    fun `an asset with no published digest is flagged rather than trusted`() = runTest {
        // Older releases publish no digest. The bytes are still hashed
        // and shown so someone can check them by hand.
        val http = FakeHttp(mapOf("https://example.invalid/rak.zip" to payload))
        val result = FirmwareDownloader(http, crypto).download(asset(sha = null))
        assertFalse(result.verifiedAgainstRelease)
        assertEquals(hashOf(0x41), result.sha256)
    }

    @Test
    fun `a package picked off the device is hashed but never claimed as verified`() {
        val result = FirmwareDownloader(FakeHttp(emptyMap()), crypto)
            .describeLocal(payload, "downloaded-firmware.zip")
        assertFalse(result.verifiedAgainstRelease)
        assertEquals(hashOf(0x41), result.sha256)
        assertEquals("downloaded-firmware.zip", result.sourceDescription)
    }

    @Test
    fun `a failed fetch is reported as a download failure not a bad package`() = runTest {
        val http = FakeHttp(emptyMap())
        assertFailsWith<FirmwareDownloadException> {
            FirmwareDownloader(http, crypto).download(asset())
        }
    }

    @Test
    fun `failing to read the version list is not failing an update`() = runTest {
        // Distinct exception types because they are distinct events:
        // nothing has been sent to a radio when the list cannot be read,
        // and telling someone their update failed — worse, blaming their
        // connection — describes something that did not happen.
        val http = FakeHttp(emptyMap())
        assertFailsWith<FirmwareCatalogException> {
            FirmwareDownloader(http, crypto).listVersions(FirmwareRole.Companion)
        }
    }

    @Test
    fun `versions come from the tag list and a release only when one is chosen`() = runTest {
        // Two requests, in this order, and the second only after the
        // user has picked. The whole point of splitting them is that
        // "is there an update" costs 50 KB instead of megabytes.
        val tags = """[{"name":"repeater-v1.17.0"},{"name":"repeater-v1.16.0"}]"""
        val release = """{"tag_name":"repeater-v1.17.0","assets":[
            {"name":"RAK_4631_repeater-v1.17.0.zip","browser_download_url":"https://x.invalid/a.zip","size":1}]}"""
        val http = FakeHttp(
            mapOf(
                FirmwareCatalog.TAGS_URL to tags.encodeToByteArray(),
                FirmwareCatalog.releaseUrlForTag("repeater-v1.17.0") to release.encodeToByteArray(),
            ),
        )
        val downloader = FirmwareDownloader(http, crypto)

        val versions = downloader.listVersions(FirmwareRole.Repeater)
        assertEquals(listOf("v1.17.0", "v1.16.0"), versions.map { it.version })
        assertEquals(listOf(FirmwareCatalog.TAGS_URL), http.requested)

        val loaded = downloader.loadRelease(versions.first())
        assertEquals("RAK_4631", loaded.assets.single().boardPrefix)
        assertEquals(2, http.requested.size)
        assertTrue(http.requested.all { it.startsWith("https://api.github.com/repos/meshcore-dev/MeshCore/") })
    }
}
