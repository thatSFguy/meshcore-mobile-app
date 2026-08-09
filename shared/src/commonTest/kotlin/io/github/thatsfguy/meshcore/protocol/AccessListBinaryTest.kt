package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The binary access list (`REQ_TYPE_GET_ACCESS_LIST`).
 *
 * This replaced a `get acl` CLI query that could never have worked over
 * the air: the firmware guards that command with `sender_timestamp == 0`
 * — true only for the serial console — and writes its output with
 * `Serial.println`, leaving `reply[0] = 0`. Remotely the node answered
 * "??: acl", which reads like firmware too old to support the feature
 * rather than a question it was never able to answer. Same shape as the
 * neighbours bug: a plausible failure from the wrong mechanism.
 *
 * An access list is the most security-relevant thing this app renders —
 * it says who can control a repeater — so the parser refuses anything it
 * cannot account for exactly. A dropped row reads as "nobody has that
 * access", which is the wrong way to be wrong.
 */
class AccessListBinaryTest {

    private fun entry(prefix: String, perms: Int): ByteArray =
        ByteArray(6) { prefix.substring(it * 2, it * 2 + 2).toInt(16).toByte() } +
            byteArrayOf(perms.toByte())

    @Test
    fun theRequestIsTheTypeAndTwoZeroReservedBytes() {
        // The firmware checks `res1 == 0 && res2 == 0` and silently does
        // not reply otherwise — the same shape of trap as the neighbours
        // request, where a short payload made the node read garbage.
        val p = AccessList.requestPayload()
        assertEquals(3, p.size)
        assertEquals(Codes.REQ_TYPE_GET_ACCESS_LIST, p[0].toInt() and 0xFF)
        assertEquals(0, p[1].toInt())
        assertEquals(0, p[2].toInt())
    }

    @Test
    fun aWellFormedListParses() {
        val body = entry("aabbccddeeff", 3) + entry("112233445566", 1)
        val list = AccessList.parseBinary(body)!!
        assertEquals(2, list.size)
        assertEquals("aabbccddeeff", list[0].keyPrefixHex)
        assertEquals("Admin", list[0].roleLabel)
        assertEquals("112233445566", list[1].keyPrefixHex)
        assertEquals("Read-only", list[1].roleLabel)
    }

    @Test
    fun everyRoleInTheMaskIsNamed() {
        // 0 Guest / 1 Read-only / 2 Read-write / 3 Admin, from
        // ClientACL.h. Getting these the wrong way round would show an
        // admin as a guest, which is the worst available error here.
        // Role 0 (Guest) can only appear with another bit set, because a
        // permissions byte of exactly 0 is a deleted row.
        val labels = listOf(0x04, 1, 2, 3).map {
            AccessList.parseBinary(entry("aabbccddeeff", it))!!.single().roleLabel
        }
        assertEquals(listOf("Guest", "Read-only", "Read-write", "Admin"), labels)
    }

    @Test
    fun theRoleIsTheLowTwoBitsAndTheRestIsFlagged() {
        // PERM_ACL_ROLE_MASK is 3. A byte carrying anything above that
        // still has a role, but the extra bits must be surfaced rather
        // than masked away silently.
        val e = AccessList.parseBinary(entry("aabbccddeeff", 0x83))!!.single()
        assertEquals("Admin", e.roleLabel)
        assertEquals(0x83, e.permissions)
        assertTrue(e.hasUnknownFlags, "0x83 has a bit outside the role mask")

        val plain = AccessList.parseBinary(entry("aabbccddeeff", 0x03))!!.single()
        assertTrue(!plain.hasUnknownFlags)
    }

    @Test
    fun anEmptyListIsValidAndDistinctFromAFailure() {
        // "Nobody is on the list" and "the node did not answer" are
        // different facts about a repeater.
        val list = AccessList.parseBinary(ByteArray(0))
        assertTrue(list != null && list.isEmpty())
    }

    @Test
    fun cipherPaddingIsNotAnAccessListEntry() {
        // THE LIVE CASE. SpartaMI has three admins: 3 x 7 = 21 bytes,
        // plus the 4-byte tag is 25, which the cipher rounds up to 32 —
        // leaving 28 bytes of body. That is exactly four entries, and
        // the fourth was rendered as "000000000000  Guest": an account
        // with access that does not exist.
        val body = entry("e3ce15cdd9f8", 3) + entry("9d00125ab6a7", 3) +
            entry("d1f53635ff33", 3) + ByteArray(AccessList.BIN_ENTRY_BYTES)
        val list = AccessList.parseBinary(body)!!
        assertEquals(3, list.size, "padding was parsed as an entry")
        assertEquals(
            listOf("e3ce15cdd9f8", "9d00125ab6a7", "d1f53635ff33"),
            list.map { it.keyPrefixHex },
        )
        assertTrue(list.all { it.roleLabel == "Admin" })
    }

    @Test
    fun aPartialTrailingEntryIsPaddingWhenItIsZero() {
        // 7 does not divide 16, so a padded body is usually NOT a whole
        // number of entries. One admin is 4 + 7 = 11 bytes padded to 16,
        // leaving 12 — one entry and five zero bytes. Refusing that
        // would break every single-entry list.
        val body = entry("aabbccddeeff", 3) + ByteArray(5)
        val list = AccessList.parseBinary(body)!!
        assertEquals(1, list.size)
        assertEquals("aabbccddeeff", list[0].keyPrefixHex)
    }

    @Test
    fun aTruncatedListIsRefusedRatherThanShortened() {
        // Padding is zeros; a cut-off reply is not. A body ending in
        // real bytes mid-entry means rows were lost, and a missing row
        // reads as "nobody has that access".
        val whole = entry("aabbccddeeff", 3) + entry("112233445566", 2)
        for (n in 1 until whole.size) {
            if (n % AccessList.BIN_ENTRY_BYTES == 0) continue
            assertNull(AccessList.parseBinary(whole.copyOfRange(0, n)), "accepted $n bytes")
        }
    }

    @Test
    fun anAbsurdCountIsRefused() {
        val huge = ByteArray((AccessList.MAX_ENTRIES + 1) * AccessList.BIN_ENTRY_BYTES)
        assertNull(AccessList.parseBinary(huge))
    }

    @Test
    fun everyByteValueRoundTripsWithoutSignExtension() {
        // Kotlin bytes are signed; 0xFF read carelessly becomes -1 and a
        // prefix renders as "ffffff..." or worse, throws.
        for (v in 1..255) {
            val e = AccessList.parseBinary(
                ByteArray(6) { v.toByte() } + byteArrayOf(v.toByte()),
            )!!.single()
            assertEquals(v, e.permissions, "permissions $v")
            assertEquals(12, e.keyPrefixHex.length)
            assertTrue(e.keyPrefixHex.all { it in "0123456789abcdef" }, e.keyPrefixHex)
            assertEquals(v and 0x03, e.role)
        }
    }

    @Test
    fun thePrefixIsSixBytesAndNeverAnIdentity() {
        // 48 bits. Enough to be useful, not enough to name anyone, and
        // the UI must not imply otherwise.
        assertEquals(6, AccessList.KEY_PREFIX_BYTES)
        val e = AccessList.parseBinary(entry("aabbccddeeff", 3))!!.single()
        assertEquals(AccessList.KEY_PREFIX_BYTES * 2, e.keyPrefixHex.length)
    }
}
