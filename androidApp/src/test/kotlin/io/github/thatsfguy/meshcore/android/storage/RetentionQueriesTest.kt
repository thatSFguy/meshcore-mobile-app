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
import kotlin.test.assertTrue

/**
 * The retention SQL itself (PARITY §3), against an in-memory database.
 *
 * The policy object is tested in shared; what can only be tested here is
 * that the queries delete the rows the policy meant — in particular that
 * count-trimming orders by ARRIVAL, so a peer sending a message stamped
 * in the year 2038 cannot push everyone else's messages out of your
 * history.
 */
@RunWith(RobolectricTestRunner::class)
class RetentionQueriesTest {

    private lateinit var db: MeshCoreDatabase
    private val self = "aa".repeat(32)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insert(
        kind: String = MessageRepository.KIND_DM,
        peer: String = "peer",
        text: String,
        timestamp: Long,
        receivedAt: Long,
    ) = db.messages().insert(
        MessageEntity(
            selfKey = self, kind = kind, peerKey = peer, senderName = null,
            text = text, timestamp = timestamp, receivedAt = receivedAt,
            outgoing = false, status = 0, ackHash = null, contentKey = null, snr = null,
        ),
    )

    @Test
    fun deleteOlderThanRemovesOnlyStaleRows() = runTest {
        insert(text = "old", timestamp = 1_000, receivedAt = 1_000_000)
        insert(text = "edge", timestamp = 2_000, receivedAt = 2_000_000)
        insert(text = "fresh", timestamp = 3_000, receivedAt = 3_000_000)

        val removed = db.messages().deleteOlderThan(self, cutoffSeconds = 2_000)
        assertEquals(1, removed)
        val left = db.messages().recentOnce(self, MessageRepository.KIND_DM, "peer", 10)
            .map { it.text }
        // Strictly older goes; exactly-at-the-cutoff stays.
        assertTrue("old" !in left)
        assertTrue("edge" in left && "fresh" in left)
    }

    @Test
    fun trimKeepsTheNewestArrivalsRegardlessOfClaimedTime() = runTest {
        // A peer claiming a far-future timestamp must not be able to
        // decide which of our messages survive.
        insert(text = "first", timestamp = 1_000, receivedAt = 1_000)
        insert(text = "second", timestamp = 2_000, receivedAt = 2_000)
        insert(text = "liar", timestamp = 9_999_999_999, receivedAt = 3_000)
        insert(text = "newest", timestamp = 4_000, receivedAt = 4_000)

        db.messages().trimThreadTo(self, MessageRepository.KIND_DM, "peer", keep = 2)

        val left = db.messages().recentOnce(self, MessageRepository.KIND_DM, "peer", 10)
            .map { it.text }.toSet()
        // Newest two BY ARRIVAL: "liar" arrived third, "newest" fourth.
        assertEquals(setOf("liar", "newest"), left)
    }

    @Test
    fun trimIsAPerThreadBound() = runTest {
        insert(peer = "a", text = "a1", timestamp = 1, receivedAt = 1)
        insert(peer = "a", text = "a2", timestamp = 2, receivedAt = 2)
        insert(peer = "b", text = "b1", timestamp = 3, receivedAt = 3)
        insert(peer = "b", text = "b2", timestamp = 4, receivedAt = 4)

        db.messages().trimThreadTo(self, MessageRepository.KIND_DM, "a", keep = 1)

        assertEquals(1, db.messages().recentOnce(self, MessageRepository.KIND_DM, "a", 10).size)
        assertEquals(2, db.messages().recentOnce(self, MessageRepository.KIND_DM, "b", 10).size)
    }

    @Test
    fun trimToMoreThanExistsRemovesNothing() = runTest {
        insert(text = "only", timestamp = 1, receivedAt = 1)
        assertEquals(0, db.messages().trimThreadTo(self, MessageRepository.KIND_DM, "peer", 50))
        assertEquals(1, db.messages().countAll(self))
    }

    @Test
    fun retentionNeverCrossesTheSelfKeyBoundary() = runTest {
        // History is scoped per radio; pruning one radio's threads must
        // not touch another's.
        val other = "bb".repeat(32)
        insert(text = "mine", timestamp = 1_000, receivedAt = 1_000)
        db.messages().insert(
            MessageEntity(
                selfKey = other, kind = MessageRepository.KIND_DM, peerKey = "peer",
                senderName = null, text = "theirs", timestamp = 1_000, receivedAt = 1_000,
                outgoing = false, status = 0, ackHash = null, contentKey = null, snr = null,
            ),
        )

        db.messages().deleteOlderThan(self, cutoffSeconds = 9_999)
        assertEquals(0, db.messages().countAll(self))
        assertEquals(1, db.messages().countAll(other))
    }

    @Test
    fun threadKeysListsEachThreadOnce() = runTest {
        insert(peer = "a", text = "1", timestamp = 1, receivedAt = 1)
        insert(peer = "a", text = "2", timestamp = 2, receivedAt = 2)
        insert(kind = MessageRepository.KIND_CHANNEL, peer = "0", text = "c", timestamp = 3, receivedAt = 3)

        val keys = db.messages().threadKeys(self).toSet()
        assertEquals(
            setOf(
                ThreadKey(MessageRepository.KIND_DM, "a"),
                ThreadKey(MessageRepository.KIND_CHANNEL, "0"),
            ),
            keys,
        )
    }
}
