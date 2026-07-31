package io.github.thatsfguy.meshcore.android.platform

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import io.github.thatsfguy.meshcore.platform.BleTransport
import io.github.thatsfguy.meshcore.protocol.MESHCORE_BLE_NAME_PREFIXES
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Active scan for MeshCore companion radios. A device qualifies when it
 * advertises the Nordic UART Service UUID OR its advertised name starts
 * with a known MeshCore prefix (`MeshCore-`, `Whisper-`, `WisCore-`,
 * `Seeed`, `Lilygo`, `HT-`, `LowMesh_MC_`) — some firmwares omit the
 * service UUID from the advertisement, so the scan runs unfiltered and
 * matches in the callback.
 *
 * Structure carried over from reticulum-mobile-app's BleScanner.
 * Caller MUST hold BLUETOOTH_SCAN (Android 12+); [BlePermissions]
 * handles the prompt.
 */
data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
)

object BleScanner {

    fun matchesMeshCoreName(name: String?): Boolean =
        name != null && MESHCORE_BLE_NAME_PREFIXES.any { name.startsWith(it) }

    /** Cold flow emitting the running set of discovered radios,
     *  deduplicated by MAC, sorted by RSSI. Cancelling stops the scan. */
    @SuppressLint("MissingPermission")
    fun scan(context: Context): Flow<List<DiscoveredDevice>> = callbackFlow {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = mgr.adapter?.bluetoothLeScanner
        if (scanner == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val nusUuid = ParcelUuid(BleTransport.NUS_SERVICE_UUID)
        val seen = mutableMapOf<String, DiscoveredDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val displayName = dev.name ?: result.scanRecord?.deviceName
                val advertisesNus = result.scanRecord?.serviceUuids?.contains(nusUuid) == true
                if (!advertisesNus && !matchesMeshCoreName(displayName)) return
                seen[dev.address] = DiscoveredDevice(
                    name = displayName,
                    address = dev.address,
                    rssi = result.rssi,
                )
                trySend(seen.values.sortedByDescending { it.rssi })
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Unfiltered scan + callback-side matching (see class KDoc).
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
