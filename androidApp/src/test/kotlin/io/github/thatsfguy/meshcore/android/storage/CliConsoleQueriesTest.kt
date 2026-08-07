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
import kotlin.test.assertTrue

/**
 * The repeater/room admin console's SQL, against an in-memory database.
 *
 * The console used to read the whole DM thread, which is wrong twice
 * over, and both defects were only visible against a real node:
 *
 * 1. **A room server's CLI and its chat are one thread.** Room posts
 *    arrive as TXT_TYPE_SIGNED (2) addressed to the room's key, so
 *    "hey", "hi" and "check" showed up in the room's console — and
 *    "Clear console" deleted them.
 * 2. **`timestamp` is the sender's clock.** Repeaters and room servers
 *    have no GPS and usually no correct time, so ordering a console by
 *    it separates a reply from the command that caused it.
 *
 * Every test here fixes one of those in terms of what the user sees.
 */
@RunWith(RobolectricTestRunner::class)
class CliConsoleQueriesTest {

    private lateinit var db: MeshCoreDatabase
    private val self = "aa".repeat(32)
    private val room = "b389548d314a"

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
        text: String,
        timestamp: Long,
        receivedAt: Long,
        txtType: Int,
        outgoing: Boolean = false,
        peer: String = room,
    ) = db.messages().insert(
        MessageEntity(
            selfKey = self, kind = MessageRepository.KIND_DM, peerKey = peer,
            senderName = null, text = text, timestamp = timestamp,
            receivedAt = receivedAt, outgoing = outgoing, status = 0,
            ackHash = null, contentKey = null, snr = null, txtType = txtType,
        ),
    )

    private suspend fun console() =
        db.messages().cliThread(self, MessageRepository.KIND_DM, room).first().map { it.text }

    private suspend fun chat() =
        db.messages().threadPaged(self, MessageRepository.KIND_DM, room, 50)
            .first().map { it.text }

    @Test
    fun theConsoleShowsCliTrafficAndNotTheRoomsConversation() = runTest {
        insert("get radio", 100, 1_000, txtType = 1, outgoing = true)
        insert("radio: 910.525", 100, 2_000, txtType = 1)
        // Real room posts, seen on hardware in a room server's console.
        insert("hey", 101, 3_000, txtType = 2)
        insert("hi", 102, 4_000, txtType = 2)
        insert("check", 103, 5_000, txtType = 2)
        // And a plain DM, for good measure.
        insert("plain", 104, 6_000, txtType = 0)

        assertEquals(listOf("get radio", "radio: 910.525"), console())
    }

    @Test
    fun theConsoleAndTheChatPartitionTheThread() = runTest {
        // Neither view may drop a row, and neither may show the other's.
        insert("cmd", 100, 1_000, txtType = 1, outgoing = true)
        insert("reply", 100, 2_000, txtType = 1)
        insert("hey", 101, 3_000, txtType = 2)
        insert("plain", 102, 4_000, txtType = 0)

        val inConsole = console().toSet()
        val inChat = chat().toSet()
        assertEquals(emptySet(), inConsole intersect inChat, "a row showed in both views")
        assertEquals(setOf("cmd", "reply", "hey", "plain"), inConsole + inChat)
    }

    @Test
    fun theConsoleOrdersByLocalArrivalNotTheNodesClock() = runTest {
        // The real shape: our command is stamped by the phone (correct,
        // ~now) and the node answers stamped by a clock that never got
        // set. Sorted by `timestamp` the reply sorts to the very top,
        // hours away from the command that produced it.
        val now = 1_754_000_000L
        insert("get radio", now, receivedAt = 1_000, txtType = 1, outgoing = true)
        insert("radio: 910.525", 0, receivedAt = 2_000, txtType = 1)
        insert("get freq", now + 5, receivedAt = 3_000, txtType = 1, outgoing = true)
        insert("freq: 910.525", 3_600, receivedAt = 4_000, txtType = 1)

        assertEquals(
            listOf("get radio", "radio: 910.525", "get freq", "freq: 910.525"),
            console(),
            "each reply must follow its own command",
        )
    }

    @Test
    fun aNodeClockInTheFutureCannotJumpTheConsole() = runTest {
        // The mirror case, and the one that would be blamed on the app:
        // a node whose clock reads 2038 pins its reply to the bottom
        // forever, under every later command.
        insert("first", 2_145_916_800, receivedAt = 1_000, txtType = 1)
        insert("second", 100, receivedAt = 2_000, txtType = 1, outgoing = true)
        insert("third", 100, receivedAt = 3_000, txtType = 1)

        assertEquals(listOf("first", "second", "third"), console())
    }

    @Test
    fun rowsInTheSameMillisecondKeepInsertionOrder() = runTest {
        // BLE delivers a multi-line reply in one burst; identical
        // receivedAt must not shuffle the lines of a `get radio` dump.
        insert("line 1", 0, receivedAt = 5_000, txtType = 1)
        insert("line 2", 0, receivedAt = 5_000, txtType = 1)
        insert("line 3", 0, receivedAt = 5_000, txtType = 1)

        assertEquals(listOf("line 1", "line 2", "line 3"), console())
    }

    @Test
    fun clearingTheConsoleLeavesTheRoomsMessagesAlone() = runTest {
        insert("get radio", 100, 1_000, txtType = 1, outgoing = true)
        insert("radio: 910.525", 100, 2_000, txtType = 1)
        insert("hey", 101, 3_000, txtType = 2)
        insert("plain", 102, 4_000, txtType = 0)

        db.messages().clearCliThread(self, MessageRepository.KIND_DM, room)

        assertEquals(emptyList(), console())
        // The whole point: the conversation survives.
        assertEquals(setOf("hey", "plain"), chat().toSet())
    }

    @Test
    fun clearingOneNodesConsoleLeavesAnothersAlone() = runTest {
        insert("mine", 100, 1_000, txtType = 1)
        insert("theirs", 100, 1_000, txtType = 1, peer = "ffffffffffff")

        db.messages().clearCliThread(self, MessageRepository.KIND_DM, room)

        assertEquals(emptyList(), console())
        assertTrue(
            db.messages().cliThread(self, MessageRepository.KIND_DM, "ffffffffffff")
                .first().map { it.text } == listOf("theirs"),
        )
    }

    @Test
    fun anEmptyConsoleIsEmptyRatherThanTheWholeThread() = runTest {
        // A node we have only ever chatted with has no console history —
        // it must not fall back to showing the conversation.
        insert("hey", 101, 3_000, txtType = 2)
        insert("plain", 102, 4_000, txtType = 0)
        assertEquals(emptyList(), console())
    }
}
