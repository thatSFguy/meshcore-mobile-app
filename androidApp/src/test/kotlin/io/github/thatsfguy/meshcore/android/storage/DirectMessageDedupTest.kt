package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A sender's retries collapsing into one row, against a real database.
 *
 * The rule itself (which copies are the same message) is pinned in
 * `DirectContentKeyTest`; what can only be tested here is that the
 * unique index actually bounces the second copy, that the bounce is
 * counted rather than dropped, and that a later copy can fill in what
 * the first could not carry.
 *
 * Observed 2026-09-22: one contact 4–11 hops out delivered a single
 * message nine times, because its ACK never got home inside the
 * sender's timeout. Before this the thread showed nine messages.
 */
@RunWith(RobolectricTestRunner::class)
class DirectMessageDedupTest {

    private lateinit var db: MeshCoreDatabase
    private val self = "aa".repeat(32)
    private val peer = "bb".repeat(32)
    private val key = "1122334455667788"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun copy(
        contentKey: String? = key,
        hops: Int? = null,
        path: String? = null,
        width: Int? = null,
        text: String = "on my way",
        outgoing: Boolean = false,
    ) = MessageEntity(
        selfKey = self,
        kind = MessageRepository.KIND_DM,
        peerKey = peer,
        senderName = null,
        text = text,
        timestamp = 1_764_000_000,
        receivedAt = System.currentTimeMillis(),
        outgoing = outgoing,
        status = MessageStatus.Delivered.ordinal,
        ackHash = null,
        contentKey = contentKey,
        snr = null,
        hops = hops,
        arrivalPathHex = path,
        arrivalHashWidth = width,
    )

    @Test
    fun `a retry is bounced by the unique index`() = runTest {
        assertEquals(1L, db.messages().insert(copy()))
        assertEquals(-1L, db.messages().insert(copy()))
        assertEquals(1, db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).size)
    }

    @Test
    fun `the nine-copy case becomes one row that says nine`() = runTest {
        db.messages().insert(copy())
        repeat(8) {
            assertEquals(-1L, db.messages().insert(copy()))
            db.messages().countAnotherCopy(self, key)
        }
        val rows = db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50)
        assertEquals(1, rows.size)
        assertEquals(9, rows.single().copies)
    }

    @Test
    fun `an ordinary message says it arrived once`() {
        // The count has to be right in the common case too, or the info
        // sheet starts announcing retries that never happened.
        assertEquals(1, copy().copies)
    }

    @Test
    fun `a later copy fills in the hop count the first could not carry`() = runTest {
        // The sender's early attempts are ROUTED (no count reported,
        // stored as -1) and its last one floods (a real count). The
        // flooded copy is the one that knows how far away they are.
        db.messages().insert(copy(hops = -1))
        db.messages().insert(copy(hops = 7))
        db.messages().fillHops(self, key, 7)
        assertEquals(7, db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).single().hops)
    }

    @Test
    fun `a routed copy never overwrites a hop count already known`() = runTest {
        db.messages().insert(copy(hops = 7))
        db.messages().fillHops(self, key, -1)
        assertEquals(7, db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).single().hops)
    }

    @Test
    fun `a later copy fills in a route the first arrived without`() = runTest {
        db.messages().insert(copy())
        db.messages().fillArrivalPath(self, key, "b389c985", 4)
        val row = db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).single()
        assertEquals("b389c985", row.arrivalPathHex)
        assertEquals(4, row.arrivalHashWidth)
    }

    @Test
    fun `an echo of our own message is not counted as a copy delivered to us`() = runTest {
        // Same key, opposite direction. Crediting it here would turn a
        // repeater rebroadcasting our own send into "they sent it twice".
        db.messages().insert(copy(outgoing = true))
        assertEquals(0, db.messages().countAnotherCopy(self, key))
    }

    // ---- what must still arrive separately -------------------------------

    @Test
    fun `two different messages both land`() = runTest {
        db.messages().insert(copy(contentKey = key, text = "first"))
        db.messages().insert(copy(contentKey = "8877665544332211", text = "second"))
        assertEquals(2, db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).size)
    }

    @Test
    fun `CLI replies carry no key and are never collapsed`() = runTest {
        // Two identical console answers are two answers.
        db.messages().insert(copy(contentKey = null, text = "> freq: 910.525"))
        db.messages().insert(copy(contentKey = null, text = "> freq: 910.525"))
        assertEquals(2, db.messages().recentOnce(self, MessageRepository.KIND_DM, peer, 50).size)
    }

    // ---- the distance signal for AckTimeout ------------------------------

    @Test
    fun `the last stated hop count is what we know about the distance`() = runTest {
        db.messages().insert(copy(contentKey = "a1", hops = 4))
        db.messages().insert(copy(contentKey = "a2", hops = 11))
        assertEquals(11, db.messages().lastHeardHops(self, peer))
    }

    @Test
    fun `a routed arrival is not read as zero distance`() = runTest {
        // -1 means "no count reported". Taken as 0 it would SHORTEN the
        // ACK wait for exactly the contacts that need it lengthened.
        db.messages().insert(copy(contentKey = "a1", hops = -1))
        assertNull(db.messages().lastHeardHops(self, peer))
    }

    @Test
    fun `a contact we have never heard from has no distance`() = runTest {
        assertNull(db.messages().lastHeardHops(self, peer))
    }

    @Test
    fun `our own sends say nothing about how far away they are`() = runTest {
        db.messages().insert(copy(hops = 3, outgoing = true))
        assertNull(db.messages().lastHeardHops(self, peer))
    }
}
