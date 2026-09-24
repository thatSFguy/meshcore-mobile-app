package io.github.thatsfguy.meshcore.android.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection-status notification must not badge the app icon.
 *
 * It is ongoing for as long as the service runs, so a badge on it is a
 * badge that never goes away — which is what users saw. A source pin,
 * because channels only exist on a device: it fails if the badge setting
 * or the channel rename is undone.
 */
class NotificationChannelWiringTest {

    private val service = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/service/MeshCoreService.kt",
    ).readText()

    @Test
    fun `the connection channel is created without a badge`() {
        val create = service.substringAfter("NOTIF_CHANNEL, \"Connection status\"")
            .substringBefore("manager.createNotificationChannel")
        assertTrue("badge not switched off", create.contains("setShowBadge(false)"))
    }

    @Test
    fun `it is a new channel, and the old one is deleted`() {
        // A channel's badge setting is fixed at creation; reusing the old
        // id would leave every existing install badged.
        // Whole word: LEGACY_NOTIF_CHANNEL legitimately holds the old id.
        assertFalse(Regex("""\bNOTIF_CHANNEL = "meshcore_connection"""").containsMatchIn(service))
        assertTrue(service.contains("deleteNotificationChannel(LEGACY_NOTIF_CHANNEL)"))
    }
}
