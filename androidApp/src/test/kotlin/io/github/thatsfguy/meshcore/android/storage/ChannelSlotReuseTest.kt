package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.meshcore.model.Channel
import io.github.thatsfguy.meshcore.presentation.Inbox
import io.github.thatsfguy.meshcore.protocol.Retention
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A channel slot changing hands takes its history with it.
 *
 * Bug report 2026-11-20: a user deleted #wardriving, joined #tumb, and
 * saw #wardriving's messages in #tumb. Messages are stored under the
 * SLOT index; deleting the channel removed its row and preferences but
 * not its messages, and the join took the freed slot.
 *
 * Every route by which a slot changes hands is pinned, and so is the
 * case where history must SURVIVE — a rename over the same key. Without
 * that one, "delete history whenever anything changes" would pass.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelSlotReuseTest {

    private val self = "11".repeat(32)
    private lateinit var db: MeshCoreDatabase
    private lateinit var prefs: Preferences

    private val wardriving = Channel(2, "#wardriving", ByteArray(16) { 0x5A })
    private val tumb = Channel(2, "#tumb", ByteArray(16) { 0x3C })

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().build()
        context.getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Preferences(context)
    }

    @After
    fun tearDown() = db.close()

    private class WorkingVault : SecretVault {
        override suspend fun seal(plaintext: ByteArray) = byteArrayOf(1) + plaintext
        override suspend fun unseal(sealed: ByteArray) = sealed.copyOfRange(1, sealed.size)
    }

    private class DeadVault : SecretVault {
        override suspend fun seal(plaintext: ByteArray): ByteArray = error("no keystore")
        override suspend fun unseal(sealed: ByteArray): ByteArray = error("no keystore")
    }

    private fun repo(scope: CoroutineScope, vault: SecretVault = WorkingVault()) =
        MessageRepository(db, SecretsRepository(prefs, vault), scope).also { r ->
            r.onChannelSlotForgotten = { prefs.forgetChannelSlot(it) }
        }

    private var n = 0
    private suspend fun post(idx: Int, text: String) {
        db.messages().insert(
            MessageEntity(
                selfKey = self, kind = MessageRepository.KIND_CHANNEL, peerKey = idx.toString(),
                senderName = "someone", text = text, timestamp = 1_700_000_000L + n,
                receivedAt = 1_700_000_000_000L + n, outgoing = false, status = 0,
                ackHash = null, contentKey = "k${n++}", snr = null,
            ),
        )
    }

    private suspend fun history(idx: Int): List<String> =
        db.messages().thread(self, MessageRepository.KIND_CHANNEL, idx.toString()).first().map { it.text }

    @Test
    fun deleteThenJoinIntoTheSameSlotStartsEmpty() = runTest {
        // The report, end to end: delete leaves the slot absent from the
        // sweep, then the next join reuses it.
        val r = repo(backgroundScope)
        r.reconcileChannels(self, listOf(wardriving))
        post(2, "wardriving traffic")

        r.reconcileChannels(self, emptyList())
        r.reconcileChannels(self, listOf(tumb))

        assertEquals(emptyList(), history(2))
        assertEquals("#tumb", db.channels().byIdx(self, 2)?.name)
    }

    @Test
    fun aSlotOverwrittenWithADifferentKeyLosesTheOldHistory() = runTest {
        // No empty sweep in between — another app wrote straight over the
        // slot, or this one's sweep after the delete timed out.
        val r = repo(backgroundScope)
        r.reconcileChannels(self, listOf(wardriving))
        post(2, "wardriving traffic")
        prefs.setChannelMuted(2, true)

        r.reconcileChannels(self, listOf(tumb))

        assertEquals(emptyList(), history(2))
        assertFalse(prefs.isChannelMuted(2), "the new channel must not inherit the mute")
        assertEquals(0, db.channels().byIdx(self, 2)?.unread)
    }

    @Test
    fun aRenameOverTheSameKeyKeepsItsHistory() = runTest {
        // The positive control: the key is the channel, the name a label.
        val r = repo(backgroundScope)
        r.reconcileChannels(self, listOf(wardriving))
        post(2, "kept")
        db.channels().bumpUnread(self, 2, 1L)

        r.reconcileChannels(self, listOf(wardriving.copy(name = "Wardriving (local)")))

        assertEquals(listOf("kept"), history(2))
        assertEquals(1, db.channels().byIdx(self, 2)?.unread)
    }

    @Test
    fun anUnchangedSweepKeepsEverything() = runTest {
        val r = repo(backgroundScope)
        r.reconcileChannels(self, listOf(wardriving))
        post(2, "kept")
        r.reconcileChannels(self, listOf(wardriving))
        assertEquals(listOf("kept"), history(2))
    }

    @Test
    fun orphanedHistoryFromBeforeTheFixIsClearedBeforeASlotIsReused() = runTest {
        // A channel deleted on an older build: row gone, messages left.
        // They must not be waiting there for the next join.
        val r = repo(backgroundScope)
        post(2, "left behind")
        r.reconcileChannels(self, emptyList())
        assertEquals(emptyList(), history(2))
    }

    @Test
    fun otherSlotsAndOtherRadiosAreUntouched() = runTest {
        val r = repo(backgroundScope)
        val public = Channel(0, "Public", ByteArray(16) { 0x01 })
        r.reconcileChannels(self, listOf(public, wardriving))
        post(0, "public post")
        post(2, "wardriving traffic")
        val other = "22".repeat(32)
        db.messages().insert(
            MessageEntity(
                selfKey = other, kind = MessageRepository.KIND_CHANNEL, peerKey = "2",
                senderName = null, text = "another radio", timestamp = 1L, receivedAt = 1L,
                outgoing = false, status = 0, ackHash = null, contentKey = "other", snr = null,
            ),
        )

        r.reconcileChannels(self, listOf(public))

        assertEquals(listOf("public post"), history(0))
        assertEquals(emptyList(), history(2))
        assertEquals(
            listOf("another radio"),
            db.messages().thread(other, MessageRepository.KIND_CHANNEL, "2").first().map { it.text },
        )
    }

    @Test
    fun withoutAKeystoreTheNameDecides() = runTest {
        // No sealed PSK to compare: same name keeps, a new name forgets.
        val r = repo(backgroundScope, DeadVault())
        r.reconcileChannels(self, listOf(wardriving))
        post(2, "kept")
        r.reconcileChannels(self, listOf(wardriving))
        assertEquals(listOf("kept"), history(2))

        r.reconcileChannels(self, listOf(tumb))
        assertEquals(emptyList(), history(2))
    }

    @Test
    fun forgettingASlotClearsEverySlotKeyedPreference() {
        val pin = Inbox.threadKey(MessageRepository.KIND_CHANNEL, "3")
        prefs.setChannelRegion(3, "us-mi")
        prefs.setChannelRetention(3, Retention.Policy(Retention.Mode.Count, 10))
        prefs.setChannelMuted(3, true)
        prefs.setThreadPinned(pin, true)
        prefs.setChannelMuted(4, true)

        prefs.forgetChannelSlot(3)

        assertNull(prefs.channelRegion(3))
        assertFalse(prefs.isChannelMuted(3))
        assertFalse(pin in prefs.pinnedThreads)
        assertTrue(prefs.slotKeyedPreferenceKeys(3).isEmpty())
        assertTrue(prefs.isChannelMuted(4), "a neighbouring slot keeps its mute")
    }
}
