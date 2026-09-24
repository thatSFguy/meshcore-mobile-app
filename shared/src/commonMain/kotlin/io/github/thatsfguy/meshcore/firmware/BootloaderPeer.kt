package io.github.thatsfguy.meshcore.firmware

/** A BLE peer that is (or might be) a node sitting in bootloader DFU mode. */
data class DfuPeer(val address: String, val name: String?, val rssi: Int? = null) {

    /**
     * Is the link strong enough to attempt a firmware transfer at all?
     *
     * This is not a connectivity question — a connection holds at signal
     * levels a 400 KB transfer may not survive. It matters because of
     * the ORDER the bootloader does things in: it erases the application
     * before it writes the new one, so a transfer that dies part-way
     * leaves a node with no firmware, waiting in its bootloader for
     * someone to walk to it.
     */
    val signalIsAdequate: Boolean get() = (rssi ?: 0) > WEAK_SIGNAL_DBM

    /** Which bootloader this is, from its advertised name. See [BootloaderKind]. */
    val bootloaderKind: BootloaderKind get() = BootloaderPeer.kindOf(name)

    companion object {
        /**
         * The floor below which a transfer is not started automatically.
         *
         * **Set to -95 dBm on the operator's instruction (2026-08-13),
         * raised from -80.** The reason is worth keeping, because -95 is
         * close enough to the noise floor that a reader will be tempted
         * to "correct" it: some nodes in this deployment cannot be
         * approached any closer than that. They are on masts and roofs.
         * A guard that refuses every attempt on the nodes that most need
         * updating is not a safety feature — it just moves the whole job
         * back to a USB cable and a ladder.
         *
         * The risk it was guarding against has not gone away, and the
         * screen still explains it. What changed is who decides: the
         * person who knows where the node is.
         */
        const val WEAK_SIGNAL_DBM = -95
    }
}

/**
 * Which bootloader a peer is running, as far as its name can tell.
 *
 * The distinction decides what may be done with a node whose firmware is
 * already erased, and it is the difference between "flash it again" and
 * "fetch a USB cable":
 *
 * - [Stock] — Adafruit's own. With no valid application and no
 *   over-the-air magic in `GPREGRET` it starts in **USB** mode (`main.c`,
 *   `usb_init`), and a restart clears that magic. So an erased node must
 *   never be restarted.
 * - [Otafix] — oltaco's fork. With no valid application it defaults to
 *   **Bluetooth** update mode (`main.c`: "set default to OTA only when no
 *   explicit UF2/serial/dbl-reset"), so a restart is safe and is what
 *   clears an interrupted session. Proven on the test RAK 2026-09-24.
 * - [Unknown] — anything else, treated as [Stock]: guessing wrong in that
 *   direction costs a retry, and in the other a trip to the node.
 */
enum class BootloaderKind {
    Stock,
    Otafix,
    Unknown;

    /** Whether restarting it with its firmware erased keeps it on Bluetooth. */
    val restartKeepsItReachable: Boolean get() = this == Otafix
}

/**
 * What to look for after a node reboots into its bootloader.
 *
 * [companionAddress] is the address the node advertised in app mode, if
 * we had it. [nameHint] is a board name to prefer when several nodes
 * are in DFU mode at once — a real hazard at a site with a stack of
 * spare boards on the bench.
 */
data class BootloaderExpectation(
    val companionAddress: String? = null,
    val nameHint: String? = null,
    /**
     * An address to match **as-is**, with no +1 applied.
     *
     * Used for a node that has just run `start ota` and is advertising
     * from its own running firmware: `NRF52Board::startOTAUpdate`
     * answers with `"OK - mac: …"`, so the address is known exactly and
     * the bootloader's +1 has not happened yet.
     */
    val exactAddress: String? = null,
)

/**
 * Recognising the bootloader after the jump.
 *
 * The bootloader is a **different BLE peer** from the application: it
 * advertises under its own name and its own address. Both are pinned to
 * the bootloader's source rather than to observation, because getting
 * this wrong means connecting to the wrong node and flashing it.
 *
 * - Address: `dfu_transport_ble.c` does `addr.addr[0] += 1` before
 *   advertising. `addr[0]` is the least-significant octet, which is the
 *   LAST field of the address as Android and iOS print it — and it
 *   wraps, so `…:FF` becomes `…:00`, not `…:100`.
 * - Name: `DEVICE_NAME` defaults to `AdaDFU`, and no stock Adafruit
 *   board overrides it. OTAFIX overrides it per board as `<code>_DFU`
 *   (`4631_DFU`, `PROM_DFU`, `T114_DFU` — each board's `board.mk`).
 *   Nordic's own SDK bootloader uses `DfuTarg`. All are accepted.
 *   (`<board>_OTA` is NOT a bootloader: it is MeshCore's own firmware
 *   after `start ota`. This comment said otherwise until 2026-09-24,
 *   and OTAFIX's `_DFU` names were recognised by address alone.)
 *
 * Either signal alone is enough — a stock bootloader that was never
 * given a per-board name still increments its address, and a board that
 * was put into DFU mode with the button (so we never saw its app-mode
 * address) still announces itself by name.
 */
object BootloaderPeer {

    private val KNOWN_NAMES = listOf("AdaDFU", "DfuTarg")

    /**
     * The address the bootloader will advertise on, or null if
     * [companionAddress] is not a six-octet BLE address.
     */
    fun expectedAddress(companionAddress: String): String? {
        val parts = companionAddress.trim().split(":")
        if (parts.size != 6) return null
        val octets = parts.map { it.toIntOrNull(16) ?: return null }
        if (octets.any { it !in 0..0xFF }) return null
        val bumped = octets.toMutableList()
        // The last printed octet is addr[0] — the one the bootloader
        // increments — and it is a byte, so 0xFF rolls over to 0x00.
        bumped[5] = (bumped[5] + 1) and 0xFF
        return bumped.joinToString(":") {
            it.toString(16).uppercase().padStart(2, '0')
        }
    }

    /**
     * The node's own address, given a [peer] it was flashed through.
     *
     * A bootloader advertises on the node's address + 1, so one that is
     * [isCertainlyBootloader] is stepped back by one (wrapping, as the
     * bootloader's increment does); anything else is already the node's
     * own. Null for an address that is not six octets.
     */
    fun nodeAddressOf(peer: DfuPeer): String? {
        val parts = peer.address.trim().split(":")
        if (parts.size != 6) return null
        val octets = parts.map { it.toIntOrNull(16) ?: return null }
        if (octets.any { it !in 0..0xFF }) return null
        if (!isCertainlyBootloader(peer.name)) {
            return octets.joinToString(":") { it.toString(16).uppercase().padStart(2, '0') }
        }
        val stepped = octets.toMutableList()
        stepped[5] = (stepped[5] - 1) and 0xFF
        return stepped.joinToString(":") { it.toString(16).uppercase().padStart(2, '0') }
    }

    /**
     * Does this advertised name belong to a node ready for an update —
     * either a bootloader, or firmware that has run `start ota`?
     *
     * Both wear the same shape of name. The board supplies it as a
     * constructor argument to `NRF52Board` in its `variants` header, and
     * while nearly every one is `<BOARD>_OTA`, `Meshtiny OTA` uses a
     * space — so this cannot be an `endsWith("_OTA")`.
     */
    fun looksLikeBootloader(name: String?): Boolean {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (KNOWN_NAMES.any { it.equals(trimmed, ignoreCase = true) }) return true
        if (trimmed.equals("OTA", ignoreCase = true)) return false
        if (isOtafixName(trimmed)) return true
        return trimmed.endsWith("_OTA", ignoreCase = true) ||
            trimmed.endsWith(" OTA", ignoreCase = true)
    }

    /**
     * Which bootloader [name] belongs to. `_DFU` names are OTAFIX's
     * alone — every stock Adafruit board keeps `AdaDFU` — so the suffix
     * is the signal, and anything unrecognised is [BootloaderKind.Unknown].
     */
    fun kindOf(name: String?): BootloaderKind {
        val trimmed = name?.trim().orEmpty()
        return when {
            trimmed.equals("AdaDFU", ignoreCase = true) -> BootloaderKind.Stock
            isOtafixName(trimmed) -> BootloaderKind.Otafix
            else -> BootloaderKind.Unknown
        }
    }

    /** `4631_DFU`: something before the suffix, and not just the suffix. */
    private fun isOtafixName(trimmed: String): Boolean =
        trimmed.length > "_DFU".length && trimmed.endsWith("_DFU", ignoreCase = true)

    /**
     * Is this peer **certainly** the bootloader rather than firmware
     * that has merely run `start ota`?
     *
     * The two states cannot be told apart by a `<board>_OTA` name — both
     * wear it — but `AdaDFU` and `DfuTarg` belong to the bootloader
     * alone, and its address is the node's own **plus one**.
     *
     * That distinction matters wherever an address is recorded for later
     * use. Storing a bootloader's address as though it were the node's
     * makes every later expectation one too high, and turns the
     * already-in-the-bootloader test into its own opposite — so a node
     * sitting in DFU mode gets written the app-mode jump byte, which the
     * bootloader reads as a malformed start-DFU.
     */
    fun isCertainlyBootloader(name: String?): Boolean =
        KNOWN_NAMES.any { it.equals(name?.trim(), ignoreCase = true) } ||
            isOtafixName(name?.trim().orEmpty())

    /**
     * The addresses a [companionAddress] can legitimately turn up on:
     * its own, and the bootloader's one higher.
     *
     * **Both, not just the second one.** Which of the two states the
     * node is in is exactly what the scan is trying to find out — a
     * node told to `start ota` is advertising from its own running
     * firmware on its own address, and only becomes the +1 once it has
     * taken the jump. Matching solely the +1 left the app-mode case with
     * nothing but a `<board>_OTA` NAME to go on, which is not an
     * identity: with two boards in update mode it matched both, and
     * [choose] then declined to pick between them and reported the node
     * we had been given the address of as "not advertising".
     *
     * Ordered by how far through the sequence the node would be, most
     * advanced first: a peer sitting in its bootloader is the one to
     * flash, and its app-mode self may still be in a stale scan result.
     * Callers that have to pick one take the first that is present.
     */
    fun addressesFor(expectation: BootloaderExpectation): List<String> =
        listOfNotNull(
            expectation.companionAddress?.let { expectedAddress(it) },
            expectation.exactAddress,
            expectation.companionAddress,
        )

    /** Is [candidate] the node we are looking for? */
    fun matches(expectation: BootloaderExpectation, candidate: DfuPeer): Boolean {
        if (expectation.exactAddress != null) {
            // An address the node itself reported is the whole answer:
            // matching anything else would be picking a different node.
            return addressesFor(expectation).any { candidate.address.equals(it, true) }
        }
        val addresses = addressesFor(expectation)
        if (addresses.any { candidate.address.equals(it, true) }) return true
        return looksLikeBootloader(candidate.name)
    }

    /**
     * Pick one of several candidates. An address match always wins over
     * a name match; among name matches, one carrying the board name we
     * expect wins over one that does not. With nothing to separate them
     * this returns null rather than guessing — flashing the wrong node
     * is worse than asking.
     */
    fun choose(expectation: BootloaderExpectation, candidates: List<DfuPeer>): DfuPeer? {
        val viable = candidates.filter { matches(expectation, it) }
        if (viable.isEmpty()) return null
        for (address in addressesFor(expectation)) {
            viable.firstOrNull { it.address.equals(address, true) }?.let { return it }
        }
        if (viable.size == 1) return viable.single()
        val hint = expectation.nameHint?.trim()?.lowercase()
        if (!hint.isNullOrEmpty()) {
            val byName = viable.filter { it.name?.lowercase()?.contains(hint) == true }
            if (byName.size == 1) return byName.single()
        }
        return null
    }
}
