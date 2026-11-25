package io.github.thatsfguy.meshcore.platform

import android.bluetooth.BluetoothDevice

/**
 * The decision half of pairing a radio, kept free of Android callbacks
 * so it can be tested off-device.
 *
 * Every MeshCore companion requires an authenticated (PIN, MITM) bond
 * before it will exchange a frame, but the two platforms differ in
 * whether anything *starts* that bond:
 *
 *  - **nRF52** guards the NUS CCCD with `SECMODE_ENC_WITH_MITM`
 *    (`src/helpers/nrf52/SerialBLEInterface.cpp`, `bleuart.setPermission`),
 *    so subscribing is refused with insufficient-authentication and
 *    Android pairs by itself.
 *  - **ESP32** (T-LoRa, Heltec, T-Beam…) guards only the characteristics
 *    (`src/helpers/esp32/SerialBLEInterface.cpp:46,50`) and adds a
 *    `BLE2902` CCCD with no security. Subscribing succeeds unencrypted,
 *    our no-response writes to the encrypted RX characteristic are
 *    dropped without an error, and the radio queues no reply until
 *    `onAuthenticationComplete` sets `deviceConnected` (`:80`, `:174`).
 *    Nothing ever triggers pairing, and the session sits in
 *    "handshaking" forever. A radio that worked before had a bond from
 *    an earlier pairing; a new one never gets one.
 *
 * A bond alone is not enough either. Android encrypts an LE link only
 * when an operation is refused for want of it, and a no-response write
 * is never refused out loud — so a phone already bonded to an ESP32
 * radio still talks to it in the clear, and is still ignored. Proven on
 * hardware 2026-11-24: a T-LoRa that had been bonded since its RNode
 * days connected, took three APP_STARTs and answered none.
 *
 * So the transport does two things: bonds when the device is not
 * bonded, and then reads the TX characteristic, which an ESP32 guards
 * with `ESP_GATT_PERM_READ_ENC_MITM`. That refusal is the trigger the
 * ESP32 never provides: Android encrypts with the bond it holds, or
 * pairs, and then retries the read. [readOutcome] interprets the result.
 */
internal object BlePairing {
    enum class Step { Paired, Failed, Waiting }

    /** True when the transport must ask for a bond before subscribing. */
    fun needsBond(bondState: Int): Boolean = bondState != BluetoothDevice.BOND_BONDED

    enum class ReadOutcome {
        /** The read succeeded, so the link is now encrypted. */
        Secured,

        /** The radio does not allow reading it (nRF52) — nothing to learn, carry on. */
        NotApplicable,

        /** Security was required and could not be established. */
        Refused,
    }

    /** GATT statuses, spelled out because the API hides two of them. */
    const val GATT_SUCCESS = 0
    const val GATT_READ_NOT_PERMITTED = 2
    const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    const val GATT_INSUFFICIENT_ENCRYPTION = 15
    const val GATT_AUTH_FAIL = 0x89

    /**
     * What the security-probe read tells us. Anything unrecognised is
     * treated as [NotApplicable] rather than as a refusal: the probe
     * exists to *trigger* security, and a radio that answers it oddly
     * should still get its handshake attempt.
     */
    fun readOutcome(status: Int): ReadOutcome = when (status) {
        GATT_SUCCESS -> ReadOutcome.Secured
        GATT_INSUFFICIENT_AUTHENTICATION,
        GATT_INSUFFICIENT_ENCRYPTION,
        GATT_AUTH_FAIL,
        -> ReadOutcome.Refused
        else -> ReadOutcome.NotApplicable
    }

    /**
     * Interpret one bond-state broadcast, given the state seen before it.
     * BONDING → NONE is how Android reports a cancelled dialog or a
     * wrong PIN; a NONE that was never preceded by BONDING is just the
     * starting point and is waited past.
     */
    fun next(state: Int, previous: Int): Step = when {
        state == BluetoothDevice.BOND_BONDED -> Step.Paired
        state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING -> Step.Failed
        else -> Step.Waiting
    }
}
