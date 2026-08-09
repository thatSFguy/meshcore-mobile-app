package io.github.thatsfguy.meshcore.protocol

import io.github.thatsfguy.meshcore.protocol.PathRecovery.Stage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The escalation order when a signed-in repeater goes quiet.
 *
 * Two properties carry this suite, and neither is obvious from reading
 * the four-line `when`:
 *
 * The **free probe comes first**. `CMD_SEND_LOGIN` carries the password
 * in cleartext, and the firmware answers a blank-password login for a
 * client already in its ACL without ever comparing a password. So the
 * repair that costs nothing must be tried before the one that spends
 * the credential — an ordering that would be very easy to invert while
 * "simplifying" this, and impossible to notice afterwards, because both
 * orders work.
 *
 * And it **terminates**. The engine loops on escalate() until a repair
 * reports success or the stages run out; a cycle here is an app that
 * retries a dead repeater forever.
 */
class PathRecoveryTest {

    @Test
    fun theFreeProbeAlwaysComesBeforeThePassword() {
        // The property this class exists for.
        val first = PathRecovery.escalate(Stage.Initial, hasPassword = true)
        assertEquals(Stage.PathReset, first)
        assertFalse(
            PathRecovery.sendsPassword(first),
            "the first repair must not put the password on the air",
        )
        assertTrue(PathRecovery.sendsPassword(PathRecovery.escalate(first, hasPassword = true)))
    }

    @Test
    fun withoutAPasswordTheReauthStageIsSkippedNotAttempted() {
        // Not "attempted and failed": a stage that cannot act would cost
        // the user another 30-second timeout to reach the same answer.
        assertEquals(
            Stage.Exhausted,
            PathRecovery.escalate(Stage.PathReset, hasPassword = false),
        )
        assertEquals(2, PathRecovery.requestAttempts(hasPassword = false))
        assertEquals(3, PathRecovery.requestAttempts(hasPassword = true))
    }

    @Test
    fun escalationTerminates() {
        // Walk it to a fixed point from every start, both with and
        // without a credential. A cycle is an app that never gives up.
        for (hasPassword in listOf(true, false)) {
            for (start in Stage.entries) {
                var stage = start
                var steps = 0
                while (stage != Stage.Exhausted) {
                    stage = PathRecovery.escalate(stage, hasPassword)
                    steps++
                    assertTrue(steps <= Stage.entries.size, "no fixed point from $start")
                }
                assertEquals(
                    Stage.Exhausted,
                    PathRecovery.escalate(Stage.Exhausted, hasPassword),
                    "Exhausted must be absorbing",
                )
            }
        }
    }

    @Test
    fun escalationNeverGoesBackwards() {
        // Ordinal order is the escalation order, and the engine relies
        // on it: a stage that could repeat would re-send the password.
        for (hasPassword in listOf(true, false)) {
            for (from in Stage.entries) {
                assertTrue(
                    PathRecovery.escalate(from, hasPassword).ordinal > from.ordinal ||
                        from == Stage.Exhausted,
                    "$from went backwards or sideways",
                )
            }
        }
    }

    @Test
    fun onlyTheRepairsAreNarrated() {
        // Initial is the ordinary request — narrating it would put
        // "re-establishing the route" on screen before anything had
        // gone wrong. Exhausted has its own message.
        assertNull(PathRecovery.progressLabel(Stage.Initial))
        assertNull(PathRecovery.progressLabel(Stage.Exhausted))
        assertNotNull(PathRecovery.progressLabel(Stage.PathReset))
        assertNotNull(PathRecovery.progressLabel(Stage.Reauthenticated))
    }

    @Test
    fun theMessagesDoNotBlameTheSession() {
        // There is no session to expire — the ACL entry is permanent and
        // survives reboot. Telling the user they were "logged out" would
        // send them to re-enter a password that was never the problem,
        // which is exactly the wild goose chase this feature removes.
        val all: List<String> = Stage.entries.mapNotNull { PathRecovery.progressLabel(it) } +
            listOf(PathRecovery.EXHAUSTED_MESSAGE)
        for (text in all) {
            val lower = text.lowercase()
            assertFalse(lower.contains("session"), "blames the session: $text")
            assertFalse(lower.contains("expired"), "claims expiry: $text")
            assertFalse(lower.contains("logged out"), "claims a logout: $text")
        }
    }
}
