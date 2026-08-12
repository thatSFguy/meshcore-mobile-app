package io.github.thatsfguy.meshcore.android.storage

import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.meshcore.protocol.Regions
import io.github.thatsfguy.meshcore.protocol.Retention
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Region storage (PARITY §8). Two behaviours carry weight:
 *
 *  - everything stored is canonical, including names that arrived from
 *    a repeater's discovery reply;
 *  - forgetting a region clears it from the channels that used it, so a
 *    channel can never keep scoping its traffic to a name the user
 *    believes is gone.
 */
@RunWith(RobolectricTestRunner::class)
class RegionPreferencesTest {

    private lateinit var prefs: Preferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = Preferences(context)
    }

    @Test
    fun regionsStartEmpty() {
        assertEquals(emptyList(), prefs.regions)
        assertNull(prefs.channelRegion(0))
        assertEquals(emptyMap(), prefs.channelRegions())
    }

    @Test
    fun addingCanonicalisesAndSorts() {
        assertEquals("bayarea", prefs.addRegion("#BayArea"))
        assertEquals("socal", prefs.addRegion("  socal  "))
        assertEquals("alpha", prefs.addRegion("alpha"))
        assertEquals(listOf("alpha", "bayarea", "socal"), prefs.regions)
    }

    @Test
    fun addingTheSameRegionTwiceKeepsOneCopy() {
        prefs.addRegion("bayarea")
        prefs.addRegion("#bayarea")
        prefs.addRegion("BAYAREA")
        assertEquals(listOf("bayarea"), prefs.regions)
    }

    @Test
    fun aNameThatIsNotARegionIsRefusedNotStored() {
        for (bad in listOf("bay area", "bay/area", "", "#", "b".repeat(31), "bay\nregion save")) {
            assertNull(prefs.addRegion(bad), "stored: $bad")
        }
        assertEquals(emptyList(), prefs.regions)
    }

    @Test
    fun channelRegionsRoundTripAndCanonicalise() {
        prefs.setChannelRegion(2, "#BayArea")
        assertEquals("bayarea", prefs.channelRegion(2))
        assertEquals(mapOf(2 to "bayarea"), prefs.channelRegions())

        prefs.setChannelRegion(2, null)
        assertNull(prefs.channelRegion(2))
        assertEquals(emptyMap(), prefs.channelRegions())
    }

    @Test
    fun anInvalidChannelRegionIsTreatedAsUnscoped() {
        // Never send a scope we can't name: unscoped is the safe default.
        prefs.setChannelRegion(1, "bay area")
        assertNull(prefs.channelRegion(1))
    }

    @Test
    fun forgettingARegionUnscopesTheChannelsThatUsedIt() {
        prefs.addRegion("bayarea")
        prefs.addRegion("socal")
        prefs.setChannelRegion(0, "bayarea")
        prefs.setChannelRegion(1, "bayarea")
        prefs.setChannelRegion(3, "socal")

        prefs.removeRegion("#BayArea")

        assertEquals(listOf("socal"), prefs.regions)
        // The two channels fall back to unscoped (global flood)…
        assertNull(prefs.channelRegion(0))
        assertNull(prefs.channelRegion(1))
        // …and the unrelated one is untouched.
        assertEquals("socal", prefs.channelRegion(3))
    }

    @Test
    fun forgettingSomethingThatIsNotARegionChangesNothing() {
        prefs.addRegion("bayarea")
        prefs.setChannelRegion(0, "bayarea")
        prefs.removeRegion("bay area")
        assertEquals(listOf("bayarea"), prefs.regions)
        assertEquals("bayarea", prefs.channelRegion(0))
    }

    @Test
    fun forgettingASlotClearsEverythingKeyedByIt() {
        // A deleted channel frees its slot, and the radio hands that
        // slot to the very next join — so anything left here is
        // inherited by an unrelated channel. A region left behind
        // scopes the new channel's traffic to a mesh the user thought
        // they had left, with nothing on screen to say so.
        prefs.addRegion("bayarea")
        prefs.setChannelRegion(2, "bayarea")
        prefs.setChannelRetention(2, Retention.Policy(Retention.Mode.Days, 7))

        prefs.forgetChannelSlot(2)

        assertNull(prefs.channelRegion(2))
        assertNull(prefs.channelRetentions()[2])
        // The REGION itself survives — it belongs to the mesh, not to
        // the channel that happened to use it.
        assertEquals(listOf("bayarea"), prefs.regions)
    }

    @Test
    fun forgettingASlotLeavesTheOtherSlotsAlone() {
        prefs.addRegion("bayarea")
        prefs.addRegion("socal")
        prefs.setChannelRegion(0, "bayarea")
        prefs.setChannelRegion(2, "socal")
        prefs.setChannelRetention(0, Retention.Policy(Retention.Mode.Days, 30))

        prefs.forgetChannelSlot(2)

        assertEquals("bayarea", prefs.channelRegion(0))
        assertEquals(Retention.Policy(Retention.Mode.Days, 30), prefs.channelRetentions()[0])
    }

    @Test
    fun everySlotKeyedPreferenceIsClearedByForgetChannelSlot() {
        // The property, not the list: whatever is keyed by slot must go
        // when the slot does. This fails the day someone adds a third
        // slot-keyed preference and clears only two — which is exactly
        // how the retention policy was missed the first time.
        prefs.addRegion("bayarea")
        prefs.setChannelRegion(5, "bayarea")
        prefs.setChannelRetention(5, Retention.Policy(Retention.Mode.Days, 1))
        val before = prefs.slotKeyedPreferenceKeys(5)
        assertEquals(2, before.size, "expected the known slot-keyed prefs, got $before")

        prefs.forgetChannelSlot(5)

        assertEquals(emptySet(), prefs.slotKeyedPreferenceKeys(5))
    }

    @Test
    fun storedNamesSurviveARereadAndStayValid() {
        prefs.regions = listOf("socal", "bayarea", "bay area", "", "SIERRA")
        val stored = prefs.regions
        assertEquals(listOf("bayarea", "sierra", "socal"), stored)
        for (name in stored) {
            assertEquals(name, Regions.canonical(name), "stored a non-canonical name")
        }
    }
}
