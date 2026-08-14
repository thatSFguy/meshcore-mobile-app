package io.github.thatsfguy.meshcore.firmware

/**
 * Per-board transfer settings, from the MeshCore FAQ.
 *
 * §7.1 of the FAQ walks an operator through doing this by hand in
 * Nordic's own DFU app, and step 7 is the only published statement of
 * what these values should be:
 *
 * > Enable `Packet receipt notifications`, and change `Number of
 * > Packets` to 10 for RAK, 8 for T114. 8 also works for RAK.
 *
 * That is a setting someone has to find in a settings screen and change
 * before every flash, and getting it wrong is one of the documented
 * causes of a failed transfer. Applying it from the board the node
 * reported is most of the reason for having an updater of our own — the
 * operator has already told us what the board is, in the `board` reply.
 *
 * Deliberately conservative: a board that is not named here gets the
 * documented default rather than a guess extrapolated from a family
 * resemblance. See [BoardAssets] for why resemblance is not enough to
 * pick a firmware image, which goes double for a flow-control value that
 * fails silently and slowly.
 */
object DfuTuning {

    /**
     * Packets to send between receipt notifications, for a node
     * reporting [boardName] from `getManufacturerName()`.
     *
     * Never zero. Zero disables flow control, and a stock bootloader
     * without it takes packets faster than `hci_mem_pool` flushes them
     * to flash — which surfaces as [LegacyDfu.RESP_OPER_FAILED] a few
     * hundred bytes in, a failure that reads like a bad image.
     */
    fun packetsPerNotification(boardName: String?): Int {
        val name = boardName?.lowercase() ?: return LegacyDfu.DEFAULT_PRN_INTERVAL
        // Matched on the model number, not the whole name: a board that
        // ships more than one build reports a different string for each
        // ("Heltec T114", and the display-less variant), and they are
        // the same silicon with the same bootloader.
        if (name.contains("t114")) return 8
        return LegacyDfu.DEFAULT_PRN_INTERVAL
    }

    /**
     * Whether an OTAFIX bootloader exists for [boardName].
     *
     * FAQ §7.3: `Adafruit_nRF52_Bootloader_OTAFIX` falls back into
     * update mode when it finds the application image invalid, instead
     * of sitting there with nothing to boot. It is the single biggest
     * reduction in the risk of a half-flashed node — and it can only be
     * installed over USB, which means the advice is worth nothing unless
     * it reaches someone *before* the node goes up somewhere awkward.
     *
     * The list is the FAQ's, verbatim in coverage. A board that is not
     * on it gets no claim either way.
     */
    fun hasOtafixBootloader(boardName: String?): Boolean {
        val name = boardName?.lowercase() ?: return false
        return OTAFIX.any { name.contains(it) }
    }

    /** Where to get it, for the hint that offers it. */
    const val OTAFIX_URL = "https://github.com/oltaco/Adafruit_nRF52_Bootloader_OTAFIX"

    /**
     * Distinctive fragments of the boards FAQ §7.3 lists, as they appear
     * in `getManufacturerName()`.
     */
    private val OTAFIX = listOf(
        "t114",
        "promicro",
        "t1000-e",
        "wio tracker l1",
        "xiao",
        "rak 4631",
        "rak wismesh tag",
    )
}
