package io.github.thatsfguy.meshcore.android.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Refreshing a contact from an advert we heard — the actual SQL.
 *
 * The bug this is here for, reported from the field 2026-09-07: a
 * repeater's stored position was 31 days old and wrong, while the node
 * itself was advertising a correct one the whole time. The radio's own
 * record was current — the firmware rewrites name, position and
 * `lastmod` on every advert it hears (`BaseChatMesh.cpp:220-226`) — but
 * this app re-read that list only when it connected, and a foreground
 * service means it may not reconnect for weeks. Meanwhile every one of
 * those adverts reached the app, signature-verified, and was dropped on
 * the floor because the node was already a contact.
 *
 * Written against the real query rather than a model of it. Two of the
 * three behaviours below are `COALESCE`/`NULLIF` semantics — exactly the
 * kind of thing that reads correct and behaves otherwise, and that a
 * test asserting our own idea of the SQL would never catch.
 */
@RunWith(RobolectricTestRunner::class)
class AdvertRefreshQueriesTest {

    private lateinit var db: MeshCoreDatabase
    private val self = "aa".repeat(32)
    private val key = "6eb85e829a78"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(
        name: String = "KCEST-GRR-Downtown-01",
        lat: Double? = null,
        lon: Double? = null,
    ) = db.contacts().upsert(
        ContactEntity(
            selfKey = self,
            keyHex = key,
            name = name,
            type = 2,
            flags = 0,
            pathLen = 0x40,
            latitude = lat,
            longitude = lon,
            lastSeen = 1_000,
            lastModified = 1_000,
        ),
    )

    private suspend fun stored() = db.contacts().allOnce(self).single()

    @Test
    fun `an advert gives a contact the position it never had`() = runTest {
        // The positive control, and the reported case: a repeater whose
        // stored position was absent, advertising a real one.
        seed(lat = null, lon = null)
        db.contacts().refreshFromAdvert(
            selfKey = self,
            keyHex = key,
            name = "KCEST-GRR-Downtown-01",
            latitude = 42.9634,
            longitude = -85.6681,
            lastSeen = 9_000,
            heardAt = 9_100,
        )
        val row = stored()
        assertEquals(42.9634, row.latitude!!, 1e-9)
        assertEquals(-85.6681, row.longitude!!, 1e-9)
        assertEquals(9_100, row.lastModified)
        assertEquals(9_000, row.lastSeen)
    }

    @Test
    fun `a moved node overwrites the position we had`() = runTest {
        seed(lat = 1.0, lon = 2.0)
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "n",
            latitude = 42.9634, longitude = -85.6681,
            lastSeen = 9_000, heardAt = 9_100,
        )
        assertEquals(42.9634, stored().latitude!!, 1e-9)
    }

    @Test
    fun `an advert with no position is not a claim that the node has none`() = runTest {
        // A position is optional in an advert (§9). Writing NULL through
        // would erase a good position every time the node sent a
        // location-less advert — turning a fix into "unknown" on the
        // node's own next transmission.
        seed(lat = 42.9634, lon = -85.6681)
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "n",
            latitude = null, longitude = null,
            lastSeen = 9_000, heardAt = 9_100,
        )
        val row = stored()
        assertEquals(42.9634, row.latitude!!, 1e-9)
        assertEquals(-85.6681, row.longitude!!, 1e-9)
        // It IS still evidence we heard the node, which is the other
        // half of the report: "last heard 31 days ago" for a node on
        // the air every few hours.
        assertEquals(9_100, row.lastModified)
    }

    @Test
    fun `an advert with no name does not blank the name we have`() = runTest {
        // The name flag is optional too, and a nameless advert arriving
        // would otherwise leave a contact called "".
        seed(name = "KCEST-GRR-Downtown-01")
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "",
            latitude = null, longitude = null,
            lastSeen = 9_000, heardAt = 9_100,
        )
        assertEquals("KCEST-GRR-Downtown-01", stored().name)
    }

    @Test
    fun `a renamed node takes the new name`() = runTest {
        seed(name = "old")
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "new",
            latitude = null, longitude = null,
            lastSeen = 9_000, heardAt = 9_100,
        )
        assertEquals("new", stored().name)
    }

    @Test
    fun `locally learned facts are not disturbed`() = runTest {
        // The refresh is a targeted UPDATE, not a rebuild of the row.
        // ContactRefreshTest exists because rebuilding one from the
        // radio threw away the OTA address that made a bricked node
        // recoverable; this must not reintroduce that by another route.
        seed()
        db.contacts().upsert(
            stored().copy(otaAddress = "FF:5C:EF:28:2A:92", unread = 3, lastMessageAt = 5_555),
        )
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "n",
            latitude = 1.0, longitude = 2.0,
            lastSeen = 9_000, heardAt = 9_100,
        )
        val row = stored()
        assertEquals("FF:5C:EF:28:2A:92", row.otaAddress)
        assertEquals(3, row.unread)
        assertEquals(5_555, row.lastMessageAt)
    }

    @Test
    fun `another radio's copy of the same node is untouched`() = runTest {
        // Rows are keyed by (selfKey, keyHex). A refresh heard on one
        // radio must not rewrite what a different radio knows.
        seed()
        val other = "bb".repeat(32)
        db.contacts().upsert(stored().copy(selfKey = other, name = "theirs"))
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = key, name = "ours",
            latitude = null, longitude = null,
            lastSeen = 9_000, heardAt = 9_100,
        )
        assertEquals("theirs", db.contacts().allOnce(other).single().name)
        assertEquals("ours", db.contacts().allOnce(self).single().name)
    }

    @Test
    fun `an advert from a node we do not hold adds nothing`() = runTest {
        // The refresh must never create a contact. Adding one is the
        // radio's decision, gated by its own auto-add setting, and the
        // discovery inbox is where an unheld node belongs.
        db.contacts().refreshFromAdvert(
            selfKey = self, keyHex = "deadbeef", name = "stranger",
            latitude = 1.0, longitude = 2.0,
            lastSeen = 9_000, heardAt = 9_100,
        )
        assertEquals(0, db.contacts().allOnce(self).size)
        assertNull(db.contacts().allOnce(self).firstOrNull())
    }
}
