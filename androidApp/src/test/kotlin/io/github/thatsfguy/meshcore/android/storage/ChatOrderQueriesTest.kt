package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Chat threads and the Chats list are ordered by when a message ARRIVED
 * here, never by the sender's clock.
 *
 * Reported 2026-11-26: some nodes send dates in the future, and their
 * messages were placed ahead of current conversations. The chat queries
 * had sorted by `timestamp` — sender-claimed, unauthenticated — since the
 * first commit, although the console, retention and iOS had all moved to
 * arrival time. One message from a clock days ahead sat under every later
 * reply, and pinned its thread to the top of Chats until the date came.
 *
 * The key is the row id — insertion order — not `receivedAt`, so the
 * phone's clock is out of it too (see [aPhoneClockSetBackDoesNotReorderTheThread]).
 */
@RunWith(RobolectricTestRunner::class)
class ChatOrderQueriesTest {

    private lateinit var db: MeshCoreDatabase
    private val self = "aa".repeat(32)
    private val now = 1_764_000_000L
    private val threeDaysAhead = now + 3 * 86_400

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
        peer: String,
        text: String,
        timestamp: Long,
        receivedAt: Long,
        kind: String = MessageRepository.KIND_CHANNEL,
        outgoing: Boolean = false,
    ) = db.messages().insert(
        MessageEntity(
            selfKey = self, kind = kind, peerKey = peer,
            senderName = if (kind == MessageRepository.KIND_CHANNEL && !outgoing) "Node" else null,
            text = text, timestamp = timestamp,
            receivedAt = receivedAt, outgoing = outgoing, status = 0,
            ackHash = null, contentKey = null, snr = null, txtType = 0,
        ),
    )

    @Test
    fun aMessageFromAClockAheadStaysWhereItArrived() = runTest {
        insert("1", "before", now, receivedAt = now * 1000)
        insert("1", "from the future", threeDaysAhead, receivedAt = now * 1000 + 10_000)
        insert("1", "reply", now + 20, receivedAt = now * 1000 + 20_000, outgoing = true)
        insert("1", "later", now + 30, receivedAt = now * 1000 + 30_000)

        val expected = listOf("before", "from the future", "reply", "later")
        assertEquals(expected, db.messages().thread(self, MessageRepository.KIND_CHANNEL, "1").first().map { it.text })
        // The screen's paged window is newest first.
        assertEquals(
            expected.reversed(),
            db.messages().threadPaged(self, MessageRepository.KIND_CHANNEL, "1", 50).first().map { it.text },
        )
    }

    @Test
    fun thePagedWindowHoldsTheNewestArrivalsNotTheFutureStamp() = runTest {
        // With a window of 2, the future-stamped message must drop out of
        // the newest two once two later ones have arrived.
        insert("1", "from the future", threeDaysAhead, receivedAt = 1_000)
        insert("1", "a", now, receivedAt = 2_000)
        insert("1", "b", now + 1, receivedAt = 3_000)
        assertEquals(
            listOf("b", "a"),
            db.messages().threadPaged(self, MessageRepository.KIND_CHANNEL, "1", 2).first().map { it.text },
        )
    }

    @Test
    fun aThreadWithAFutureStampDoesNotPinItselfToTheTopOfChats() = runTest {
        // Channel 1 heard from a fast clock an hour ago; channel 2 and a
        // DM are what is actually current.
        // Inserted in the order they arrived, as the repository does.
        insert("1", "future", threeDaysAhead, receivedAt = (now - 3_600) * 1000)
        insert("bb".repeat(32), "dm", now - 60, receivedAt = (now - 60) * 1000, kind = MessageRepository.KIND_DM)
        insert("2", "current", now, receivedAt = now * 1000)

        val latest = db.messages().latestPerThread(self).first()
        assertEquals(listOf("current", "dm", "future"), latest.map { it.text })
    }

    @Test
    fun theChatsRowCarriesTheNewestArrivalOfItsThread() = runTest {
        // The bare columns must come from the row with the newest ARRIVAL,
        // so the preview is the last thing heard — not the future stamp.
        insert("1", "future", threeDaysAhead, receivedAt = 1_000)
        insert("1", "newest", now, receivedAt = 2_000)
        assertEquals(listOf("newest"), db.messages().latestPerThread(self).first().map { it.text })
    }

    @Test
    fun sameMillisecondArrivalsKeepInsertionOrder() = runTest {
        insert("1", "one", 0, receivedAt = 5_000)
        insert("1", "two", 0, receivedAt = 5_000)
        insert("1", "three", 0, receivedAt = 5_000)
        assertEquals(
            listOf("one", "two", "three"),
            db.messages().thread(self, MessageRepository.KIND_CHANNEL, "1").first().map { it.text },
        )
    }

    @Test
    fun aPhoneClockSetBackDoesNotReorderTheThread() = runTest {
        // The reason the key is the row id and not receivedAt: the second
        // message arrived after a manual clock change (or an NTP correction)
        // put the phone an hour behind. Arrival order still holds.
        insert("1", "first", now, receivedAt = now * 1000)
        insert("1", "second", now + 5, receivedAt = (now - 3_600) * 1000)
        assertEquals(
            listOf("first", "second"),
            db.messages().thread(self, MessageRepository.KIND_CHANNEL, "1").first().map { it.text },
        )
        insert("2", "other thread, earlier", now, receivedAt = now * 1000 - 1)
        // Thread 2's row arrived last, so it heads Chats although its
        // receivedAt is earlier than thread 1's first message.
        assertEquals(
            listOf("other thread, earlier", "second"),
            db.messages().latestPerThread(self).first().map { it.text },
        )
    }
}
