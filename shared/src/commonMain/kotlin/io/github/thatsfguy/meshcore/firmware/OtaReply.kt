package io.github.thatsfguy.meshcore.firmware

/**
 * What a node says back when it takes `start ota`.
 *
 * `NRF52Board::startOTAUpdate` ends by reading its own BLE address and
 * answering with it:
 *
 * ```
 * sprintf(reply, "OK - mac: %02X:%02X:%02X:%02X:%02X:%02X",
 *         mac_addr[5], mac_addr[4], mac_addr[3],
 *         mac_addr[2], mac_addr[1], mac_addr[0]);
 * ```
 *
 * That address is worth catching. It is the node advertising **right
 * now**, from its own running firmware — so the update can match it
 * exactly instead of picking between whatever else on the bench is
 * advertising a name ending in OTA.
 *
 * A node that answers anything else (an ESP32, which raises a WiFi
 * hotspot instead; a board whose Bluefruit stack refused to start, which
 * answers `Error`) yields null, and the caller falls back to matching by
 * name.
 */
object OtaReply {

    private val MAC = Regex(
        """mac:\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""",
    )

    /** The advertising address in an `OK - mac: …` reply, or null. */
    fun advertisingAddress(reply: String?): String? {
        if (reply.isNullOrBlank()) return null
        val found = MAC.find(reply)?.groupValues?.get(1) ?: return null
        val normalised = found.uppercase()
        // All-zero is what the firmware memsets before asking, so it is
        // what a failed read leaves behind. Scanning for it finds
        // nothing, and treating it as an address hides the real reason.
        if (normalised == "00:00:00:00:00:00") return null
        return normalised
    }

    /** True when the node reported that it is now advertising. */
    fun accepted(reply: String?): Boolean = advertisingAddress(reply) != null
}
