package io.github.thatsfguy.meshcore.firmware

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matching a radio's reported board to a release asset.
 *
 * The board names here are the real return values of
 * `getManufacturerName()` in the MeshCore firmware's `variants/`
 * directory, and the prefixes are the real asset names of the v1.17.0
 * releases. Both were read off the sources, not guessed, because the
 * two schemes disagree in ways no rule captures: `Seeed Tracker T1000-E`
 * builds as `t1000e`, `Heltec MeshPocket` as `Mesh_pocket`.
 *
 * The property this file defends is that the app **suggests** and never
 * decides. Flashing a T114 image onto a RAK is not a bad guess; it is a
 * dead radio.
 */
class BoardAssetsTest {

    private fun asset(prefix: String, link: CompanionLink = CompanionLink.Ble) = FirmwareAsset(
        name = "${prefix}_companion_radio_${if (link == CompanionLink.Ble) "ble" else "usb"}" +
            "-v1.17.0-727fc05.zip",
        url = "https://example.invalid/$prefix.zip",
        sizeBytes = 400_000,
        sha256 = null,
        boardPrefix = prefix,
        role = FirmwareRole.Companion,
        link = link,
        version = "v1.17.0",
    )

    private val allAssets = listOf(
        "RAK_4631", "RAK_3401", "RAK_WisMesh_Tag", "Heltec_t114",
        "Heltec_t114_without_display", "Heltec_t1", "Heltec_mesh_solar", "t1000e",
        "WioTrackerL1", "WioTrackerL1Eink", "Xiao_nrf52", "ProMicro", "Nano_G2_Ultra",
        "LilyGo_T-Echo", "Mesh_pocket", "Meshtiny", "R1Neo",
    ).map { asset(it) }

    @Test
    fun `the boards in the table map to the assets that exist`() {
        // Every pairing below was read off the firmware's
        // getManufacturerName() and the release's asset list.
        assertEquals(listOf("RAK_4631"), BoardAssets.prefixesFor("RAK 4631"))
        assertEquals(listOf("t1000e"), BoardAssets.prefixesFor("Seeed Tracker T1000-E"))
        assertEquals(listOf("Mesh_pocket"), BoardAssets.prefixesFor("Heltec MeshPocket"))
        assertEquals(listOf("R1Neo"), BoardAssets.prefixesFor("muzi works R1 Neo"))
        assertEquals(listOf("ProMicro"), BoardAssets.prefixesFor("ProMicro DIY"))
        assertEquals(listOf("Nano_G2_Ultra"), BoardAssets.prefixesFor("Nano G2 Ultra"))
    }

    @Test
    fun `none of these would survive a naive transformation`() {
        // Lowercase-and-underscore is the rule someone reaches for when
        // they skip the table. It gets every one of these wrong.
        val naive = { name: String -> name.lowercase().replace(' ', '_') }
        for (board in listOf("Seeed Tracker T1000-E", "Heltec MeshPocket", "muzi works R1 Neo", "ProMicro DIY")) {
            val real = BoardAssets.prefixesFor(board).single()
            assertFalse(
                naive(board).equals(real, ignoreCase = true),
                "$board would have worked by accident",
            )
        }
    }

    @Test
    fun `the reported name is matched case and whitespace insensitively`() {
        assertEquals(listOf("RAK_4631"), BoardAssets.prefixesFor("  rak 4631 "))
        assertEquals(listOf("RAK_4631"), BoardAssets.prefixesFor("RAK 4631"))
    }

    @Test
    fun `a board with two builds is not narrowed to one`() {
        // A T114 with a screen and one without report the same name.
        // Nothing on the wire distinguishes them, so both are offered.
        val prefixes = BoardAssets.prefixesFor("Heltec T114")
        assertEquals(listOf("Heltec_t114", "Heltec_t114_without_display"), prefixes)
        val suggested = BoardAssets.suggest("Heltec T114", allAssets)
        assertEquals(
            listOf("Heltec_t114", "Heltec_t114_without_display"),
            suggested.take(2).map { it.boardPrefix },
        )
    }

    @Test
    fun `the exact board is ranked above one that merely resembles it`() {
        val suggested = BoardAssets.suggest("RAK 4631", allAssets)
        assertEquals("RAK_4631", suggested.first().boardPrefix)
        // The other RAK boards must not outrank it.
        assertTrue(suggested.first().boardPrefix != "RAK_3401")
    }

    @Test
    fun `a board absent from the table still gets a useful shortlist`() {
        // New boards ship faster than this table is updated. An unknown
        // name should narrow the list, not give up and show forty.
        assertFalse(BoardAssets.isKnown("Heltec T96"))
        val suggested = BoardAssets.suggest("Heltec T1", allAssets)
        assertEquals("Heltec_t1", suggested.first().boardPrefix)
    }

    @Test
    fun `a maker name alone never suggests anything`() {
        // "Heltec" matches five boards and identifies none. Suggesting
        // on that alone is how the wrong image gets picked.
        val suggested = BoardAssets.suggest("Heltec", allAssets)
        assertTrue(suggested.isEmpty(), suggested.map { it.boardPrefix }.toString())
    }

    @Test
    fun `an unknown or missing board name suggests nothing at all`() {
        for (name in listOf(null, "", "   ", "Some Other Radio")) {
            assertTrue(
                BoardAssets.suggest(name, allAssets).isEmpty(),
                "suggested something for \"$name\"",
            )
            assertFalse(BoardAssets.isKnown(name))
        }
    }

    @Test
    fun `every prefix in the table exists in a real release`() {
        // A typo in the table is a board that can never be updated. The
        // prefix list here is the v1.17.0 companion release, verbatim.
        val real = setOf(
            "GAT562_30S_Mesh_Kit", "GAT562_Mesh_Tracker_Pro", "GAT562_Mesh_Watch13",
            "Heltec_mesh_solar", "Heltec_t096", "Heltec_t1", "Heltec_t114",
            "Heltec_t114_without_display", "Heltec_tower_v2", "KeepteenLT1", "LilyGo_T-Echo",
            "LilyGo_T-Echo-Lite", "LilyGo_T-Echo-Lite_non_shell", "LilyGo_T-Echo_Card",
            "LilyGo_T_Impulse_Plus", "MeshTracker_X1", "Mesh_pocket", "Meshtiny",
            "Minewsemi_me25ls01", "Nano_G2_Ultra", "ProMicro", "R1Neo", "RAK_3401", "RAK_4631",
            "RAK_WisMesh_Tag", "SenseCap_Solar", "ThinkNode_M1", "ThinkNode_M3", "ThinkNode_M6",
            "WioTrackerL1", "WioTrackerL1Eink", "Xiao_nrf52",
            "ikoka_handheld_nrf_e22_30dbm_096", "ikoka_handheld_nrf_e22_30dbm_096_rotated",
            "ikoka_nano_nrf_22dbm", "ikoka_nano_nrf_30dbm", "ikoka_nano_nrf_33dbm",
            "ikoka_stick_nrf_22dbm", "ikoka_stick_nrf_30dbm", "ikoka_stick_nrf_33dbm", "t1000e",
        )
        val boards = listOf(
            "RAK 4631", "RAK 3401", "RAK WisMesh Tag", "Heltec T114", "Heltec T1",
            "Heltec Mesh Solar", "Heltec MeshPocket", "Seeed Tracker T1000-E",
            "Seeed Wio Tracker L1", "Seeed Xiao-nrf52", "Seeed SenseCap Solar",
            "Seeed SenseCAP MeshTracker X1", "ProMicro DIY", "Nano G2 Ultra", "LilyGo T-Echo",
            "LilyGo T-Echo Lite", "LilyGo T-Echo Card", "Elecrow ThinkNode-M1",
            "Elecrow ThinkNode M3", "Elecrow ThinkNode M6", "Meshtiny", "Keepteen LT1",
            "muzi works R1 Neo", "Ikoka Handheld E22 30dBm (Xiao_nrf52)",
        )
        for (board in boards) {
            val prefixes = BoardAssets.prefixesFor(board)
            assertTrue(prefixes.isNotEmpty(), "$board is not in the table")
            for (prefix in prefixes) {
                assertTrue(prefix in real, "$board maps to \"$prefix\", which no release ships")
            }
        }
    }

    @Test
    fun `a board the user picked by hand is matched next time`() {
        // A node in update mode cannot be asked what it is, which is
        // exactly when it is being flashed. So the user picks once and
        // the answer is stored — as the ASSET PREFIX, not the
        // manufacturer string, because the prefix is what they chose
        // from. Both forms have to resolve.
        assertEquals("ProMicro", BoardAssets.suggest("ProMicro", allAssets).first().boardPrefix)
        assertEquals("ProMicro", BoardAssets.suggest("ProMicro DIY", allAssets).first().boardPrefix)
        // An underscore in the prefix must not defeat the comparison:
        // `wordsOf` splits on it, so this has to be matched whole.
        assertEquals("RAK_4631", BoardAssets.suggest("RAK_4631", allAssets).first().boardPrefix)
        assertEquals("RAK_4631", BoardAssets.suggest("rak_4631", allAssets).first().boardPrefix)
        assertEquals("t1000e", BoardAssets.suggest("t1000e", allAssets).first().boardPrefix)
    }

    @Test
    fun `a hand-picked board narrows to exactly one build`() {
        // The point of remembering it: the next run must not ask again.
        val suggested = BoardAssets.suggest("ProMicro", allAssets)
        assertEquals(1, suggested.size, suggested.map { it.boardPrefix }.toString())
    }
}
