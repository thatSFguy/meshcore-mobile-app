package io.github.thatsfguy.meshcore.firmware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * `HttpURLConnection` and nothing else — no HTTP client dependency for
 * the two requests this feature makes.
 *
 * HTTPS is required, not preferred: this fetches code that is about to
 * run on a radio, and a plaintext redirect would hand that to anyone on
 * the path. GitHub redirects release downloads to its CDN, so redirects
 * are followed manually to re-check the scheme each hop — `setInstance
 * FollowRedirects` will not cross protocols but will happily cross
 * hosts without telling us.
 */
class AndroidHttpFetcher : HttpFetcher {

    override suspend fun get(url: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        var current = url
        var hops = 0
        while (true) {
            if (!current.startsWith("https://")) {
                throw FirmwareDownloadException("Refusing a firmware download over plain HTTP.")
            }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
                setRequestProperty("User-Agent", "MeshCoreHardened")
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw FirmwareDownloadException("The server redirected to nowhere.")
                    hops++
                    if (hops > 5) throw FirmwareDownloadException("Too many redirects.")
                    current = location
                    continue
                }
                if (status !in 200..299) {
                    throw FirmwareDownloadException("The server answered $status.")
                }
                val declared = connection.contentLengthLong
                if (declared > maxBytes) {
                    throw FirmwareDownloadException(
                        "The download is ${declared / 1024} KB, larger than expected.",
                    )
                }
                return@withContext connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream(
                        if (declared in 1..maxBytes) declared.toInt() else 64 * 1024,
                    )
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw FirmwareDownloadException("The download exceeded its size limit.")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw FirmwareDownloadException("unreachable")
    }
}
