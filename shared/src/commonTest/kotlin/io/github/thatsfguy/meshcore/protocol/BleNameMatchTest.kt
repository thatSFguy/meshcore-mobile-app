package io.github.thatsfguy.meshcore.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which advertised names count as a MeshCore radio.
 *
 * Shared because both platforms scan UNFILTERED — some firmwares leave
 * the NUS service UUID out of the advertisement — and then qualify a
 * device on name. If the two platforms disagreed, a radio would appear
 * on Android and be invisible on iOS, which reads as broken Bluetooth
 * rather than as a mismatched predicate.
 */
class BleNameMatchTest {

    @Test
    fun `the known prefixes match`() {
        for (name in listOf(
            "MeshCore-Blue", "Whisper-1234", "WisCore-abc", "Seeed T1000",
            "Lilygo T-Deck", "HT-CT62", "LowMesh_MC_01",
        )) {
            assertTrue(matchesMeshCoreName(name), "should match: $name")
        }
    }

    @Test
    fun `unrelated devices do not match`() {
        for (name in listOf("AirPods", "Galaxy Buds", "meshcore-lowercase", "")) {
            assertFalse(matchesMeshCoreName(name), "should not match: $name")
        }
    }

    @Test
    fun `a nameless advertisement does not match`() {
        // Plenty of peripherals advertise no local name at all. Those
        // qualify only by advertising the NUS service.
        assertFalse(matchesMeshCoreName(null))
    }

    @Test
    fun `matching is a prefix test rather than a substring one`() {
        assertFalse(matchesMeshCoreName("My MeshCore-Blue"))
        assertTrue(matchesMeshCoreName("MeshCore-Blue in the shed"))
    }
}
