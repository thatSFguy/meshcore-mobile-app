package io.github.thatsfguy.meshcore.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.thatsfguy.meshcore.firmware.BootloaderExpectation
import io.github.thatsfguy.meshcore.firmware.BootloaderPeer
import io.github.thatsfguy.meshcore.firmware.DfuPeer
import io.github.thatsfguy.meshcore.firmware.DfuScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a node advertising in bootloader DFU mode.
 *
 * The scan runs unfiltered and matches in the callback, for the same
 * reason `BleScanner` does: not every bootloader puts its service UUID
 * in the advertisement, and the name is often all there is.
 *
 * It keeps scanning for the whole window rather than taking the first
 * hit. An address match is worth waiting for — the alternative is
 * connecting to whichever bench board happened to answer first, and
 * flashing it.
 *
 * Caller holds BLUETOOTH_SCAN.
 */
@SuppressLint("MissingPermission")
class AndroidDfuScanner(
    private val context: Context,
    /**
     * Where to record what the scan saw.
     *
     * "The node is not advertising for an update" is a claim about the
     * hardware, made from the absence of evidence — and with nothing
     * logged there is no way to tell a node that is genuinely silent
     * from one that answered under a name or an address this expectation
     * would not take. That distinction is the difference between
     * power-cycling a repeater and fixing a filter.
     */
    private val log: (String) -> Unit = {},
) : DfuScanner {

    /**
     * The `BluetoothDevice` objects the scan produced, by address.
     *
     * Kept because **rebuilding one from its address string loses the
     * address type**. `BluetoothAdapter.getRemoteDevice(String)` assumes
     * a public address, and nRF boards advertise from a random static
     * one — connecting to a random-address peer through a device object
     * that claims public fails with GATT status 133 and no other
     * explanation. The object handed out by the scan carries the right
     * type, so it is the one to connect with.
     */
    private val seenDevices = mutableMapOf<String, BluetoothDevice>()

    /** The scanned device for [address], if this scanner saw it. */
    fun deviceFor(address: String): BluetoothDevice? = seenDevices[address]

    override suspend fun findBootloader(
        expectation: BootloaderExpectation,
        timeoutMs: Long,
    ): DfuPeer? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = mgr.adapter?.bluetoothLeScanner ?: return null
        val seen = LinkedHashMap<String, DfuPeer>()
        // Every address this node could be advertising on — its own and
        // its bootloader's. Asked of [BootloaderPeer] rather than worked
        // out again here: this fast path used to derive only the +1
        // while `matches` accepted both, so a node found the moment it
        // appeared on its OWN address was instead waited out for the
        // full scan window before the same answer was reached.
        val expectedAddresses = BootloaderPeer.addressesFor(expectation)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
                seen[device.address] = DfuPeer(device.address, name, result.rssi)
                seenDevices[device.address] = device
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        return try {
            scanner.startScan(null, settings, callback)
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    // The address we expect settles it; anything else
                    // waits out the window in case the right node is
                    // still erasing its flash before it advertises.
                    // In the order the node moves through them: already
                    // in its bootloader beats still in app mode.
                    val exact = expectedAddresses.firstNotNullOfOrNull { addr ->
                        seen.values.firstOrNull { it.address.equals(addr, ignoreCase = true) }
                    }
                    if (exact != null) return@withTimeoutOrNull exact
                    delay(250)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            } ?: BootloaderPeer.choose(expectation, seen.values.toList())
        } finally {
            runCatching { scanner.stopScan(callback) }
            // Named peers first — an unnamed address is almost always a
            // phone or a beacon, and the useful question is whether
            // anything nearby is calling itself a bootloader.
            val named = seen.values.filter { !it.name.isNullOrBlank() }
            log(
                "scan saw ${seen.size} peers (${named.size} named), wanted " +
                    (
                        expectedAddresses.joinToString(" or ").ifEmpty {
                            expectation.nameHint ?: "anything in update mode"
                        }
                        ) +
                    named.joinToString("") { "\n  ${it.address} ${it.name} ${it.rssi}dBm" },
            )
        }
    }
}
