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

/**
 * Channel-row reconciliation.
 *
 * Regression test for a bug found on a real radio (2026-08-01): the
 * Chats list showed "Channel 2" through "Channel 7" for slots the user
 * had never configured. The engine had filtered them correctly — each
 * had a blank name and an all-zero PSK — but stale database rows from
 * an earlier sync were never removed, because the reconcile step was
 * skipped whenever it observed an empty selfKey, and skipped again
 * whenever the radio reported no channels.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelReconcileTest {

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
    fun tearDown() = db.close()

    private suspend fun seed(vararg idx: Int) {
        for (i in idx) {
            db.channels().upsert(
                ChannelEntity(selfKey = self, idx = i, name = "", pskSealed = ByteArray(16)),
            )
        }
    }

    @Test
    fun reconcileRemovesSlotsTheRadioNoLongerReports() {
        runTest {
            seed(0, 1, 2, 3, 4, 5, 6, 7)
            assertEquals(8, db.channels().allOnce(self).size)

            // The radio reports only the two configured slots.
            db.channels().deleteAbsent(self, listOf(0, 1))

            assertEquals(listOf(0, 1), db.channels().allOnce(self).map { it.idx })
        }
    }

    @Test
    fun reconcileWithNoLiveChannelsClearsEverything() {
        runTest {
            seed(0, 1, 2)
            // The case the old guard skipped: every slot cleared on the
            // radio left every stale row in place, forever.
            db.channels().deleteAbsent(self, emptyList())
            assertEquals(emptyList(), db.channels().allOnce(self).map { it.idx })
        }
    }

    @Test
    fun reconcileNeverCrossesTheSelfKeyBoundary() {
        runTest {
            val other = "bb".repeat(32)
            seed(0, 1, 2)
            db.channels().upsert(
                ChannelEntity(selfKey = other, idx = 5, name = "Theirs", pskSealed = ByteArray(16)),
            )

            db.channels().deleteAbsent(self, listOf(0))

            assertEquals(listOf(0), db.channels().allOnce(self).map { it.idx })
            assertEquals(listOf(5), db.channels().allOnce(other).map { it.idx })
        }
    }

    @Test
    fun reconcileKeepsEverythingTheRadioStillReports() {
        runTest {
            seed(0, 1, 2)
            db.channels().deleteAbsent(self, listOf(0, 1, 2))
            assertEquals(3, db.channels().allOnce(self).size)
        }
    }
}
