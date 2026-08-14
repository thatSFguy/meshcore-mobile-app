package io.github.thatsfguy.meshcore.firmware

/**
 * iOS Phase 2 stub, following the same convention as the crypto and BLE
 * actuals: it links so the framework builds, and throws if reached.
 * Firmware updates need `IosBleTransport` and a CoreBluetooth DFU
 * client before a zip reader is worth anything.
 */
actual fun readZipEntries(zip: ByteArray): Map<String, ByteArray> =
    throw UnsupportedOperationException(
        "Reading firmware packages is not implemented on iOS yet.",
    )
