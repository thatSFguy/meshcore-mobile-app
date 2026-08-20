package io.github.thatsfguy.meshcore.android.storage

import androidx.test.core.app.ApplicationProvider
import io.github.thatsfguy.meshcore.presentation.NodeListModel
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Nodes list remembers how it was arranged.
 *
 * Stored by enum NAME rather than ordinal, so inserting a case into
 * [NodeListModel.Sort] cannot silently re-point an existing install at a
 * different order — the failure mode of an ordinal, and a silent one,
 * because a list in the wrong order still looks like a list.
 */
@RunWith(RobolectricTestRunner::class)
class NodeListPreferencesTest {

    private lateinit var prefs: Preferences
    private lateinit var raw: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        raw = context.getSharedPreferences("meshcore_prefs", android.content.Context.MODE_PRIVATE)
        raw.edit().clear().commit()
        prefs = Preferences(context)
    }

    @Test
    fun theDefaultIsTheOrderTheListAlwaysHad() {
        assertEquals(NodeListModel.Sort.Activity, prefs.nodesSort)
        assertEquals(emptySet(), prefs.nodesFilters)
    }

    @Test
    fun everySortRoundTrips() {
        for (sort in NodeListModel.Sort.entries) {
            prefs.nodesSort = sort
            assertEquals(sort, prefs.nodesSort, "$sort did not survive being stored")
        }
    }

    @Test
    fun everyFilterRoundTripsAloneAndTogether() {
        for (filter in NodeListModel.Filter.entries) {
            prefs.nodesFilters = setOf(filter)
            assertEquals(setOf(filter), prefs.nodesFilters)
        }
        val all = NodeListModel.Filter.entries.toSet()
        prefs.nodesFilters = all
        assertEquals(all, prefs.nodesFilters)
    }

    @Test
    fun filtersCanBeClearedBackToNothing() {
        prefs.nodesFilters = NodeListModel.Filter.entries.toSet()
        prefs.nodesFilters = emptySet()
        assertEquals(emptySet(), prefs.nodesFilters)
    }

    @Test
    fun aSortNameThatNoLongerExistsFallsBackToTheDefault() {
        // A renamed or removed case must not leave the list unsortable
        // or crash on read. This is the whole reason it is not an
        // ordinal, so it is the case that carries the choice.
        raw.edit().putString("nodes_sort", "SortedByVibes").commit()
        assertEquals(NodeListModel.Sort.Activity, prefs.nodesSort)
    }

    @Test
    fun anUnknownFilterNameIsIgnoredWithoutLosingTheKnownOnes() {
        raw.edit()
            .putStringSet("nodes_filters", setOf("Favorites", "HideEverythingIDislike"))
            .commit()
        assertEquals(setOf(NodeListModel.Filter.Favorites), prefs.nodesFilters)
    }

    @Test
    fun aStoredSetIsNeverHandedStraightBackToThePreference() {
        // getStringSet returns SharedPreferences' own instance and
        // mutating it is documented as undefined. Reading, deriving and
        // writing back must not corrupt what is stored.
        prefs.nodesFilters = setOf(NodeListModel.Filter.Favorites)
        prefs.nodesFilters = prefs.nodesFilters + NodeListModel.Filter.Unread
        assertEquals(
            setOf(NodeListModel.Filter.Favorites, NodeListModel.Filter.Unread),
            prefs.nodesFilters,
        )
        prefs.nodesFilters = prefs.nodesFilters - NodeListModel.Filter.Favorites
        assertEquals(setOf(NodeListModel.Filter.Unread), prefs.nodesFilters)
    }

    @Test
    fun theStoredFormIsTheEnumNameAndNotItsPosition() {
        prefs.nodesSort = NodeListModel.Sort.LastHeard
        assertEquals("LastHeard", raw.getString("nodes_sort", null))
        prefs.nodesFilters = setOf(NodeListModel.Filter.ActiveDay)
        assertTrue(raw.getStringSet("nodes_filters", emptySet())!!.contains("ActiveDay"))
    }
}
