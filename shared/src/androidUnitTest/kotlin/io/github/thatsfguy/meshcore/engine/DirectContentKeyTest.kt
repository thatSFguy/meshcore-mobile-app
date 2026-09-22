package io.github.thatsfguy.meshcore.engine

import io.github.thatsfguy.meshcore.platform.AndroidCryptoProvider
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The dedup key that turns a sender's retries into one message.
 *
 * A retry is a deliberately distinct packet — its attempt number goes
 * into the encrypted payload, so the mesh's seen-table passes it and
 * the receiving firmware queues every copy — and the attempt byte is
 * gone before the frame reaches us. What every copy still shares is the
 * sender, the sender's own timestamp, and the text.
 */
class DirectContentKeyTest {

    private val crypto = AndroidCryptoProvider()
    private val sender = "a1b2c3d4e5f6"

    // The key is a pure function of its arguments; the engine is here
    // only because that is where the crypto provider lives.
    private val engine = MeshCoreEngine(CoroutineScope(Dispatchers.Unconfined), crypto, { 0L })

    private fun key(
        prefix: String = sender,
        timestamp: Long = 1_764_000_000,
        txtType: Int = Codes.TXT_TYPE_PLAIN,
        text: String = "on my way",
    ): String? = engine.directContentKey(prefix, timestamp, txtType, text)

    @Test
    fun `every copy of one message keys the same`() {
        // THE POSITIVE CONTROL. The rest of this file says which things
        // must NOT collapse; this is the one that fails if the feature
        // does nothing.
        assertEquals(key(), key())
    }

    @Test
    fun `a resend a second later is a different message`() {
        // A person hitting send again composes a new message with a new
        // timestamp. Collapsing that would hide a deliberate act — the
        // duplicate the user actually meant.
        assertNotEquals(key(timestamp = 1_764_000_000), key(timestamp = 1_764_000_001))
    }

    @Test
    fun `different senders never share a key`() {
        // Two contacts saying "ok" in the same second is ordinary.
        assertNotEquals(key(prefix = sender), key(prefix = "f6e5d4c3b2a1"))
    }

    @Test
    fun `different text never shares a key`() {
        assertNotEquals(key(text = "yes"), key(text = "no"))
    }

    @Test
    fun `a room post and a plain DM with the same words stay apart`() {
        assertNotEquals(
            key(txtType = Codes.TXT_TYPE_PLAIN),
            key(txtType = Codes.TXT_TYPE_SIGNED),
        )
    }

    @Test
    fun `a CLI reply is never deduplicated`() {
        // Console output repeats legitimately: two identical answers to
        // two `get freq` commands are two answers, and swallowing one
        // would silently eat a line of the console.
        assertNull(key(txtType = Codes.TXT_TYPE_CLI_DATA))
    }

    @Test
    fun `the sender prefix is compared without case`() {
        assertEquals(key(prefix = "A1B2C3D4E5F6"), key(prefix = "a1b2c3d4e5f6"))
    }

    @Test
    fun `a fixed-width sender field keeps the text from impersonating it`() {
        // The prefix sits between a fixed header and the text. Were it
        // variable-width, a crafted prefix and text could hash the same
        // input as a different pair — a stranger's message silently
        // collapsing into someone else's thread.
        assertNotEquals(key(prefix = "a1b2", text = "c3d4e5f6hello"), key(text = "hello"))
    }

    @Test
    fun `a key is short, hex, and stable`() {
        val k = key()!!
        assertEquals(16, k.length)
        assertEquals(k, key())
        assertEquals(k.lowercase(), k)
    }
}
