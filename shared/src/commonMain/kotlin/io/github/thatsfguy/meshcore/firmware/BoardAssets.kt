package io.github.thatsfguy.meshcore.firmware

/**
 * Matching the board a radio reports to the release asset built for it.
 *
 * These are two different naming schemes and neither derives from the
 * other. The radio reports `board.getManufacturerName()` — a human
 * string chosen per variant, `"Heltec T114"` — while the release asset
 * is named after the PlatformIO environment, `Heltec_t114`. Lowercasing
 * and swapping spaces for underscores gets many of them and silently
 * fumbles the rest: `Seeed Tracker T1000-E` builds as `t1000e`,
 * `Heltec MeshPocket` as `Mesh_pocket`, `muzi works R1 Neo` as `R1Neo`.
 *
 * So this is a table, and it is deliberately **not** authoritative:
 * several boards ship more than one build (`Heltec_t114` and
 * `Heltec_t114_without_display`; `WioTrackerL1` and `WioTrackerL1Eink`)
 * and no reported name can tell them apart. The contract is therefore
 * *suggest, never choose* — [suggest] ranks, and a human confirms the
 * filename before anything is flashed.
 *
 * Only nRF52 boards appear. ESP32 boards cannot be updated over BLE at
 * all, and their releases carry no `.zip` to offer.
 */
object BoardAssets {

    /**
     * Reported name → asset prefixes, from the MeshCore firmware's
     * `getManufacturerName()` implementations and the asset names of
     * the v1.17.0 releases.
     */
    private val TABLE: Map<String, List<String>> = mapOf(
        "rak 4631" to listOf("RAK_4631"),
        "rak 3401" to listOf("RAK_3401"),
        "rak wismesh tag" to listOf("RAK_WisMesh_Tag"),
        "heltec t114" to listOf("Heltec_t114", "Heltec_t114_without_display"),
        "heltec t1" to listOf("Heltec_t1"),
        "heltec mesh solar" to listOf("Heltec_mesh_solar"),
        "heltec meshpocket" to listOf("Mesh_pocket"),
        "seeed tracker t1000-e" to listOf("t1000e"),
        "seeed wio tracker l1" to listOf("WioTrackerL1", "WioTrackerL1Eink"),
        "seeed xiao-nrf52" to listOf("Xiao_nrf52"),
        "seeed sensecap solar" to listOf("SenseCap_Solar"),
        "seeed sensecap meshtracker x1" to listOf("MeshTracker_X1"),
        "promicro diy" to listOf("ProMicro"),
        "nano g2 ultra" to listOf("Nano_G2_Ultra"),
        "lilygo t-echo" to listOf("LilyGo_T-Echo"),
        "lilygo t-echo lite" to listOf("LilyGo_T-Echo-Lite", "LilyGo_T-Echo-Lite_non_shell"),
        "lilygo t-echo card" to listOf("LilyGo_T-Echo_Card"),
        "elecrow thinknode-m1" to listOf("ThinkNode_M1"),
        "elecrow thinknode m3" to listOf("ThinkNode_M3"),
        "elecrow thinknode m6" to listOf("ThinkNode_M6"),
        "meshtiny" to listOf("Meshtiny"),
        "keepteen lt1" to listOf("KeepteenLT1"),
        "muzi works r1 neo" to listOf("R1Neo"),
        "ikoka handheld e22 30dbm (xiao_nrf52)" to listOf(
            "ikoka_handheld_nrf_e22_30dbm_096",
            "ikoka_handheld_nrf_e22_30dbm_096_rotated",
        ),
    )

    /** Asset prefixes known for [boardName], or empty if it is not in the table. */
    fun prefixesFor(boardName: String?): List<String> {
        val key = boardName?.trim()?.lowercase() ?: return emptyList()
        return TABLE[key].orEmpty()
    }

    /** True when we have a definite answer, rather than a resemblance. */
    fun isKnown(boardName: String?): Boolean = prefixesFor(boardName).isNotEmpty()

    /**
     * Rank [assets] by how well they fit [boardName], most likely first.
     * Assets that resemble it in no way are dropped entirely — a list
     * of forty boards to scroll is not a safety feature.
     *
     * Ranking, in order:
     * 1. an exact prefix from the table
     * 2. a prefix sharing every word of the reported name
     * 3. a prefix sharing a distinctive word (a model number)
     */
    fun suggest(boardName: String?, assets: List<FirmwareAsset>): List<FirmwareAsset> {
        val known = prefixesFor(boardName).toSet()
        val words = wordsOf(boardName)
        val exact = boardName?.trim().orEmpty()
        return assets
            .map { it to score(it.boardPrefix, known, words, exact) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<FirmwareAsset, Int>> { it.second }
                .thenBy { it.first.name })
            .map { it.first }
    }

    private fun score(
        prefix: String,
        known: Set<String>,
        words: List<String>,
        exact: String,
    ): Int {
        if (prefix in known) return 100
        // The remembered value may be the ASSET PREFIX rather than the
        // manufacturer string — that is what gets stored when a user
        // picks the board by hand for a node whose name could not be
        // read. Compared whole, not word by word: `wordsOf` splits on
        // the underscore that half these prefixes contain.
        if (exact.isNotEmpty() && exact.equals(prefix, ignoreCase = true)) return 100
        if (words.isEmpty()) return 0
        // A model name ("t114", "thinknode") identifies a board; a maker
        // name ("heltec", "seeed", "lilygo") does not — a third of the
        // list shares it, so a name made only of maker words matches
        // nothing rather than everything.
        val distinctive = words.filterNot { it in MAKERS }
        if (distinctive.isEmpty()) return 0
        val prefixWords = wordsOf(prefix)
        if (prefixWords.isEmpty()) return 0
        val shared = words.count { word -> prefixWords.any { it == word } }
        if (shared == words.size) return 50
        val sharedDistinctive = distinctive.count { word -> prefixWords.any { it == word } }
        return if (sharedDistinctive > 0) 10 + sharedDistinctive else 0
    }

    private val MAKERS = setOf(
        "heltec", "seeed", "lilygo", "rak", "elecrow", "muzi", "works", "ikoka", "keepteen",
    )

    private fun wordsOf(value: String?): List<String> = value.orEmpty()
        .lowercase()
        .split(' ', '_', '-', '(', ')', '.')
        .filter { it.isNotBlank() }
}
