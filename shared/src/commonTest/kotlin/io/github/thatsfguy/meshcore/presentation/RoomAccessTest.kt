package io.github.thatsfguy.meshcore.presentation

import io.github.thatsfguy.meshcore.protocol.NodeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a room login granted, and what the room conversation says about
 * it. The grant bytes are the ones the firmware writes:
 * `simple_room_server/MyMesh.cpp:387` for `[1]`, `:388` (`permissions`)
 * for the ACL byte the companion appends at `[12]`.
 */
class RoomAccessTest {

    // --- positive controls: the member must be recognised -----------------

    @Test
    fun roomPasswordIsAMemberWhoMayPost() {
        // Room password -> PERM_ACL_READ_WRITE (2); [1] is 0 for it.
        assertEquals(
            AdminSession.Member,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 0, aclPermissions = 2),
        )
    }

    @Test
    fun aRoomMemberIsNoLongerReportedAsReadOnly() {
        // The defect: every non-admin room login was AdminSession.Guest,
        // announced as "read-only", while the room accepted its posts.
        assertEquals(
            null,
            roomAccessNotice(
                grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 0, aclPermissions = 2),
            ),
        )
    }

    @Test
    fun readOnlyAclMayPostInARoom() {
        // The room drops posts only for PERM_ACL_GUEST (MyMesh.cpp:480);
        // READ_ONLY, which only `setperm` can give, still posts.
        assertEquals(
            AdminSession.Member,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 0, aclPermissions = 1),
        )
    }

    @Test
    fun adminWinsForEveryRole() {
        for (role in listOf(NodeRole.Room, NodeRole.Repeater, NodeRole.Sensor)) {
            assertEquals(
                AdminSession.Admin,
                grantedSession(role, isAdmin = true, legacyPermission = 1, aclPermissions = 3),
            )
        }
    }

    // --- the withheld cases ------------------------------------------------

    @Test
    fun allowReadOnlyIsAGuestWhosePostsAreDropped() {
        // allow.read.only -> PERM_ACL_GUEST (0); [1] is 2 for it.
        val s = grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 2, aclPermissions = 0)
        assertEquals(AdminSession.Guest, s)
        assertNotNull(roomAccessNotice(s))
    }

    @Test
    fun theAclByteIsMaskedToItsRoleBits() {
        // Upper bits are flags, not the role.
        assertEquals(
            AdminSession.Guest,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 2, aclPermissions = 0xF0),
        )
        assertEquals(
            AdminSession.Member,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 0, aclPermissions = 0xF2),
        )
    }

    @Test
    fun olderFirmwareFallsBackToTheLegacyByte() {
        assertEquals(
            AdminSession.Guest,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 2, aclPermissions = null),
        )
        assertEquals(
            AdminSession.Member,
            grantedSession(NodeRole.Room, isAdmin = false, legacyPermission = 0, aclPermissions = null),
        )
    }

    @Test
    fun aRepeaterNeverGrantsMember() {
        // A repeater's non-admin runs no CLI, so read-write buys nothing
        // the app could show; it stays Guest and the hub gating is unchanged.
        assertEquals(
            AdminSession.Guest,
            grantedSession(NodeRole.Repeater, isAdmin = false, legacyPermission = 0, aclPermissions = 2),
        )
        assertEquals(
            AdminSession.Guest,
            grantedSession(NodeRole.Sensor, isAdmin = false, legacyPermission = 0, aclPermissions = 2),
        )
    }

    // --- the notice ---------------------------------------------------------

    @Test
    fun aRoomOpenedWithoutSigningInSaysSoAndOffersSignIn() {
        assertNotNull(roomAccessNotice(AdminSession.None))
        assertEquals("Sign in", roomAccessAction(AdminSession.None))
    }

    @Test
    fun aReadOnlyGuestIsOfferedAnotherSignIn() {
        assertEquals("Sign in again", roomAccessAction(AdminSession.Guest))
    }

    @Test
    fun aSessionThatMayPostShowsNoNotice() {
        assertNull(roomAccessNotice(AdminSession.Member))
        assertNull(roomAccessNotice(AdminSession.Admin))
        assertNull(roomAccessAction(AdminSession.Member))
        assertNull(roomAccessAction(AdminSession.Admin))
    }

    @Test
    fun memberHasItsOwnChip() {
        assertEquals("MEMBER", AdminSession.Member.chipLabel)
    }

    @Test
    fun aMemberGetsStatusButNoCliTools() {
        // Signed in, not admin: the room answers status, and nothing that
        // speaks CLI (MyMesh.cpp:467).
        assertEquals(
            listOf("status"),
            repeaterHubTiles(NodeRole.Room, AdminSession.Member).map { it.route },
        )
    }

    @Test
    fun theDefaultRoomPasswordIsTheFirmwares() {
        // variants/*/platformio.ini room envs: -D ROOM_PASSWORD='"hello"'.
        assertEquals("hello", ROOM_DEFAULT_PASSWORD)
        assertTrue(roomPasswordHint().contains("\"hello\""))
    }
}
