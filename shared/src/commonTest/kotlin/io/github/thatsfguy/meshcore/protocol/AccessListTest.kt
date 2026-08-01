package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing `get acl`. The governing rule: a line the parser doesn't
 * understand must survive to the screen, because a silently dropped
 * access-list entry reads as "nobody has that access".
 */
class AccessListTest {

    @Test
    fun `parses colon-separated entries`() {
        val p = AccessList.parse("b389548d: admin\nc985cffd: guest")
        assertEquals(2, p.entries.size)
        assertEquals("b389548d", p.entries[0].keyPrefixHex)
        assertEquals("admin", p.entries[0].permission)
        assertEquals("guest", p.entries[1].permission)
        assertTrue(p.unparsed.isEmpty())
    }

    @Test
    fun `parses space and comma separated entries`() {
        assertEquals("admin", AccessList.parse("b389548d admin").entries.single().permission)
        assertEquals("1", AccessList.parse("b389548d,1").entries.single().permission)
    }

    @Test
    fun `lower-cases the key prefix so it can be matched against contacts`() {
        assertEquals("b389548d", AccessList.parse("B389548D: ADMIN").entries.single().keyPrefixHex)
    }

    @Test
    fun `keeps the permission word the node used`() {
        // Firmware wording varies; don't normalise away what it said.
        assertEquals("read-only", AccessList.parse("aabb: read-only").entries.single().permission)
    }

    @Test
    fun `an unrecognised line is preserved rather than dropped`() {
        val p = AccessList.parse("ACL entries:\nb389548d: admin\n<something new>")
        assertEquals(1, p.entries.size)
        assertTrue(p.unparsed.contains("ACL entries:"))
        assertTrue(p.unparsed.contains("<something new>"))
    }

    @Test
    fun `never invents an entry from a non-hex line`() {
        val p = AccessList.parse("no entries\nerror: not authorised")
        assertTrue(p.entries.isEmpty())
        assertEquals(2, p.unparsed.size)
    }

    @Test
    fun `blank and absent replies parse to nothing`() {
        assertTrue(AccessList.parse(null).isEmpty)
        assertTrue(AccessList.parse("").isEmpty)
        assertTrue(AccessList.parse("   \n  \n").isEmpty)
    }

    @Test
    fun `every input line ends up either parsed or preserved`() {
        // The property that makes this safe to render.
        val reply = "ACL:\nb389548d: admin\ngarbage line\nc985cffd guest\n\n  \nzz: nope"
        val p = AccessList.parse(reply)
        val accounted = p.entries.size + p.unparsed.size
        val nonBlank = reply.lines().count { it.isNotBlank() }
        assertEquals(nonBlank, accounted)
    }

    @Test
    fun `tolerates a full-length key as well as a prefix`() {
        val full = "a".repeat(64)
        assertEquals(full, AccessList.parse("$full: admin").entries.single().keyPrefixHex)
    }
}
