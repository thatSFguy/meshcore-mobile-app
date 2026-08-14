package io.github.thatsfguy.meshcore.firmware

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads every entry into memory. A firmware package is ~500 KB and is
 * about to be held in memory for the whole transfer anyway, so there is
 * nothing to stream to.
 *
 * Two guards, because the zip comes from a file picker or a download:
 * entries are capped so a zip bomb cannot exhaust the heap before the
 * user has agreed to anything, and directory traversal in an entry name
 * is irrelevant here (nothing is written to disk) but a name is still
 * taken verbatim and only matched against the manifest.
 */
actual fun readZipEntries(zip: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
        while (true) {
            val entry = stream.nextEntry ?: break
            if (entry.isDirectory) continue
            if (out.size >= MAX_ZIP_ENTRIES) {
                throw DfuPackageException("The package holds more than $MAX_ZIP_ENTRIES files.")
            }
            val bytes = stream.readBytes()
            total += bytes.size
            if (total > MAX_ZIP_TOTAL_BYTES) {
                throw DfuPackageException("The package expands to more than 8 MB.")
            }
            out[entry.name] = bytes
        }
    }
    return out
}

private const val MAX_ZIP_ENTRIES = 32
private const val MAX_ZIP_TOTAL_BYTES = 8L * 1024 * 1024
