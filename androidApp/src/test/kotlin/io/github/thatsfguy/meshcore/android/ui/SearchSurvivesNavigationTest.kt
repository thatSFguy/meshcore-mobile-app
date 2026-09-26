package io.github.thatsfguy.meshcore.android.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search term on Nodes and Chats survives opening a result and coming
 * back. Held with `remember`, it was discarded when the screen left the
 * composition, so returning from a node started the search over. A source
 * pin: saved state only exists under a NavHost on a device.
 */
class SearchSurvivesNavigationTest {

    private fun screen(name: String) = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/$name",
    ).readText()

    @Test
    fun `the nodes and chats search terms are saveable`() {
        for (name in listOf("NodesScreen.kt", "ChatsScreen.kt")) {
            val s = screen(name)
            assertTrue("$name: query not saveable", s.contains("var query by rememberSaveable"))
            assertFalse("$name: query back on remember", s.contains("var query by remember {"))
        }
    }
}
