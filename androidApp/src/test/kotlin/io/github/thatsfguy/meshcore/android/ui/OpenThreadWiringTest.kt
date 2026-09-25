package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A conversation counts as "being read" only while the app is on screen.
 *
 * It was held open by a DisposableEffect, which outlives the screen being
 * switched off, so the last conversation shown stayed "read" in a pocket:
 * its messages never notified and never counted unread. The rule itself
 * is Inbox's and tested in shared; this pins the wiring, which is where
 * the defect was.
 */
class OpenThreadWiringTest {

    private val screen = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/ConversationScreen.kt",
    ).readText()

    @Test
    fun `the thread is opened on start and closed on stop`() {
        val effect = screen.substringAfter("LifecycleStartEffect(kind, peerKey)")
            .substringBefore("\n    }\n")
        assertTrue("no lifecycle-aware effect", screen.contains("LifecycleStartEffect(kind, peerKey)"))
        assertTrue(effect.contains("vm.markThreadOpen(kind, peerKey)"))
        assertTrue(effect.contains("onStopOrDispose { vm.markThreadClosed(kind, peerKey) }"))
    }

    @Test
    fun `it is no longer held open by a DisposableEffect`() {
        assertFalse(
            Regex("""DisposableEffect\(kind, peerKey\)\s*\{\s*vm\.markThreadOpen""")
                .containsMatchIn(screen),
        )
    }
}
