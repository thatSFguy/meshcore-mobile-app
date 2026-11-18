package io.github.thatsfguy.meshcore.messaging

import io.github.thatsfguy.meshcore.model.Contact
import io.github.thatsfguy.meshcore.protocol.Codes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionDiscoveryTest {

    private fun repeater(seed: Int, type: Int = Codes.ADV_TYPE_REPEATER) = Contact(
        publicKey = ByteArray(32) { (seed + it).toByte() },
        type = type, flags = 0, pathLen = 0xFF, path = ByteArray(64),
        name = "r$seed", timestamp = 0, latitude = null, longitude = null, lastModified = 0,
    )

    private class FakeRadio(
        val prefixes: Set<String>,
        val held: List<Contact>,
        val answers: Map<String, List<String>?>,
    ) : RegionDiscovery.Radio {
        val asked = mutableListOf<String>()
        override suspend fun discoverRepeaterPrefixes() = prefixes
        override fun heldRepeaters() = held
        override suspend fun regionsOf(repeater: Contact): List<String>? {
            asked += repeater.name
            return answers[repeater.name]
        }
    }

    @Test
    fun namesAreGatheredCanonicalOnceEachAndSorted() = runTest {
        val a = repeater(1); val b = repeater(0x40)
        val radio = FakeRadio(
            setOf(a.publicKeyHex.take(8), b.publicKeyHex.take(8)),
            listOf(a, b),
            mapOf("r1" to listOf("#BayArea", "mi"), "r64" to listOf("bayarea", "Not Valid!", "x".repeat(40))),
        )
        val r = RegionDiscovery(radio).run()
        assertEquals(listOf("bayarea", "mi"), r.names)
        assertEquals(2, r.answered)
        assertEquals(2, r.asked)
        assertTrue(r.summary.startsWith("Names reported"))
    }

    @Test
    fun onlyHeldRepeatersThatAnsweredTheBroadcastAreAsked() = runTest {
        val a = repeater(1); val unheard = repeater(2)
        val radio = FakeRadio(setOf(a.publicKeyHex.take(8), "deadbeef"), listOf(a, unheard), mapOf("r1" to emptyList()))
        val r = RegionDiscovery(radio).run()
        assertEquals(listOf("r1"), radio.asked)
        assertEquals(1, r.asked)
        assertTrue(r.summary.contains("none of them uses named regions"))
    }

    @Test
    fun silenceIsReportedForWhatItIs() = runTest {
        val a = repeater(1)
        val none = RegionDiscovery(FakeRadio(emptySet(), listOf(a), emptyMap())).run()
        assertTrue(none.summary.startsWith("No repeater answered the discovery broadcast"))
        val quiet = RegionDiscovery(FakeRadio(setOf(a.publicKeyHex.take(8)), listOf(a), mapOf("r1" to null))).run()
        assertEquals(0, quiet.answered)
        assertTrue(quiet.summary.startsWith("Asked 1 repeater(s); none replied"))
    }

    @Test
    fun theRegionListIsCanonicalAndRefusesBadNames() {
        assertEquals(listOf("bayarea", "mi"), RegionList.add(listOf("mi"), "#BayArea"))
        assertEquals(listOf("mi"), RegionList.add(listOf("mi"), "MI"))
        assertNull(RegionList.add(listOf("mi"), "bay area"))
        assertEquals(listOf("mi"), RegionList.remove(listOf("bayarea", "mi"), "#bayarea"))
    }
}
