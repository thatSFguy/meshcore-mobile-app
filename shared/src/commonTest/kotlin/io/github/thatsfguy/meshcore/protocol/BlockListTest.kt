package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Blocking (by key) and channel filtering (by name) — PARITY §3.
 *
 * The tests that matter are the ones pinning what each mechanism will
 * NOT do, because the failure mode is a user believing a name filter is
 * a block.
 */
class BlockListTest {

    private val key = "aa".repeat(32)
    private val other = "bb".repeat(32)

    // ------------------------------------------------------------------
    // Direct messages — the real block
    // ------------------------------------------------------------------

    @Test
    fun blocksOnAFullPublicKey() {
        assertTrue(BlockList.isBlockedSender(key, setOf(key)))
        assertFalse(BlockList.isBlockedSender(other, setOf(key)))
    }

    @Test
    fun keyMatchingIsCaseInsensitiveButExact() {
        assertTrue(BlockList.isBlockedSender(key.uppercase(), setOf(key)))
        // One character off is a different person.
        val nearMiss = key.dropLast(1) + "b"
        assertFalse(BlockList.isBlockedSender(nearMiss, setOf(key)))
    }

    @Test
    fun aPrefixIsNeverAcceptedAsABlockEntry() {
        // A 6-byte prefix is 48 bits. Blocking one would block everyone
        // who collides with it — including people the user never met.
        for (prefix in listOf("aabbccddeeff", key.take(32), "", "aa")) {
            assertNull(BlockList.canonicalKey(prefix), "accepted a prefix: $prefix")
        }
        assertEquals(key, BlockList.canonicalKey(key.uppercase()))
    }

    @Test
    fun anUnresolvedSenderIsNotTreatedAsBlocked() {
        // Only a prefix arrived and no contact matched it. Dropping that
        // would silently discard messages from anyone not yet a contact.
        assertFalse(BlockList.isBlockedSender(null, setOf(key)))
        assertFalse(BlockList.isBlockedSender("aabbccddeeff", setOf(key)))
    }

    @Test
    fun anEmptyBlockListBlocksNobody() {
        assertFalse(BlockList.isBlockedSender(key, emptySet()))
        assertFalse(BlockList.isBlockedSender(null, emptySet()))
    }

    @Test
    fun nonHexIsNeverAKey() {
        for (bad in listOf("zz".repeat(32), "gg".repeat(32), " ".repeat(64), "aa".repeat(31) + "!!")) {
            assertNull(BlockList.canonicalKey(bad), "accepted: $bad")
        }
    }

    // ------------------------------------------------------------------
    // Channels — the noise filter
    // ------------------------------------------------------------------

    @Test
    fun filtersAChannelNameCaseAndSpaceInsensitively() {
        val filtered = setOf("spammer")
        assertTrue(BlockList.isFilteredChannelName("spammer", filtered))
        assertTrue(BlockList.isFilteredChannelName("Spammer", filtered))
        assertTrue(BlockList.isFilteredChannelName("  SPAMMER  ", filtered))
    }

    @Test
    fun nameMatchingIsExactNotSubstring() {
        // A substring match would make the filter's effects hard to
        // predict — "spam" would swallow "spam-warning-channel".
        val filtered = setOf("spam")
        assertFalse(BlockList.isFilteredChannelName("spammer", filtered))
        assertFalse(BlockList.isFilteredChannelName("not-spam", filtered))
        assertFalse(BlockList.isFilteredChannelName("spam warning", filtered))
        assertTrue(BlockList.isFilteredChannelName("spam", filtered))
    }

    @Test
    fun anEmptyOrOverlongNameIsNotFilterable() {
        assertNull(BlockList.canonicalName(null))
        assertNull(BlockList.canonicalName(""))
        assertNull(BlockList.canonicalName("   "))
        assertNull(BlockList.canonicalName("x".repeat(BlockList.MAX_NAME_LENGTH + 1)))
        assertEquals("x".repeat(10), BlockList.canonicalName("x".repeat(10)))
    }

    @Test
    fun anEmptyFilterHidesNothing() {
        assertFalse(BlockList.isFilteredChannelName("anyone", emptySet()))
        assertFalse(BlockList.isFilteredChannelName(null, emptySet()))
    }

    // ------------------------------------------------------------------
    // The distinction itself
    // ------------------------------------------------------------------

    @Test
    fun aFilteredNameDoesNotBlockAndABlockedKeyDoesNotFilter() {
        // The two stores are separate on purpose; a key is not a name
        // and matching one against the other must find nothing.
        assertFalse(BlockList.isBlockedSender(key, setOf("spammer")))
        assertFalse(BlockList.isFilteredChannelName("spammer", setOf(key)))
    }

    @Test
    fun theCaveatSaysWhatTheFilterCannotDo() {
        // The UI shows this string verbatim; if it ever stops saying
        // that a name isn't an identity, the feature is mislabelled.
        val caveat = BlockList.CHANNEL_FILTER_CAVEAT.lowercase()
        assertTrue("name, not a key" in caveat || "name" in caveat)
        assertTrue("cannot stop a person" in caveat)
    }
}
