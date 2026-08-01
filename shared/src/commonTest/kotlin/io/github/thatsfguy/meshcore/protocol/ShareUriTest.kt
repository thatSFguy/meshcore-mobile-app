package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShareUriTest {

    private fun blob(size: Int = 100): ByteArray = ByteArray(size) { (it * 7 + 3).toByte() }

    // ---- positive ----------------------------------------------------

    @Test
    fun `round trips an advert blob`() {
        val original = blob()
        val decoded = ShareUri.decode(ShareUri.encode(original))
        assertTrue(decoded is ShareUri.Decoded.Ok)
        assertEquals(original.toHex(), decoded.blob.toHex())
    }

    @Test
    fun `encodes with the meshcore scheme and lowercase hex`() {
        val uri = ShareUri.encode(byteArrayOf(0x00, 0x0f.toByte(), 0xa0.toByte(), 0xff.toByte()))
        assertEquals("meshcore://000fa0ff", uri)
    }

    @Test
    fun `tolerates surrounding whitespace from a paste`() {
        val uri = "  \n${ShareUri.encode(blob())}\t "
        assertTrue(ShareUri.decode(uri) is ShareUri.Decoded.Ok)
    }

    @Test
    fun `accepts an uppercased scheme and payload`() {
        val uri = ShareUri.encode(blob()).uppercase()
        val decoded = ShareUri.decode(uri)
        assertTrue(decoded is ShareUri.Decoded.Ok)
        assertEquals(blob().toHex(), decoded.blob.toHex())
    }

    @Test
    fun `accepts a blob exactly at the minimum size`() {
        val decoded = ShareUri.decode(ShareUri.encode(blob(ShareUri.MIN_BLOB_BYTES)))
        assertTrue(decoded is ShareUri.Decoded.Ok)
    }

    @Test
    fun `accepts a payload exactly at the size cap`() {
        val decoded = ShareUri.decode(ShareUri.encode(blob(ShareUri.MAX_HEX_LENGTH / 2)))
        assertTrue(decoded is ShareUri.Decoded.Ok)
    }

    // ---- negative ----------------------------------------------------

    @Test
    fun `rejects a foreign scheme`() {
        assertEquals(
            ShareUri.Decoded.NotAContactCode,
            ShareUri.decode("https://example.com/${blob().toHex()}"),
        )
    }

    @Test
    fun `rejects bare text and empty input`() {
        assertEquals(ShareUri.Decoded.NotAContactCode, ShareUri.decode("hello"))
        assertEquals(ShareUri.Decoded.NotAContactCode, ShareUri.decode(""))
    }

    @Test
    fun `rejects a scheme lookalike that only shares a prefix`() {
        assertEquals(
            ShareUri.Decoded.NotAContactCode,
            ShareUri.decode("meshcore:/${blob().toHex()}"),
        )
    }

    @Test
    fun `rejects an oversized payload before decoding it`() {
        val huge = ShareUri.SCHEME + "ab".repeat(ShareUri.MAX_HEX_LENGTH)
        assertEquals(ShareUri.Decoded.TooLarge, ShareUri.decode(huge))
    }

    @Test
    fun `rejects non-hex characters`() {
        val bad = ShareUri.SCHEME + "zz".repeat(50)
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(bad))
    }

    @Test
    fun `rejects an odd-length payload`() {
        val odd = ShareUri.encode(blob()) + "a"
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(odd))
    }

    @Test
    fun `rejects an empty payload`() {
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(ShareUri.SCHEME))
    }

    @Test
    fun `rejects a blob too short to be an advert`() {
        val stub = ShareUri.encode(blob(ShareUri.MIN_BLOB_BYTES - 1))
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(stub))
    }

    @Test
    fun `rejects embedded whitespace inside the payload`() {
        val hex = blob().toHex()
        val spaced = ShareUri.SCHEME + hex.substring(0, 10) + " " + hex.substring(11)
        assertEquals(ShareUri.Decoded.Malformed, ShareUri.decode(spaced))
    }
}
