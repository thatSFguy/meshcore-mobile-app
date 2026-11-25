package io.github.thatsfguy.meshcore.platform

import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import io.github.thatsfguy.meshcore.platform.BlePairing.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An ESP32 companion (T-LoRa) never starts pairing itself, so a phone
 * that has never bonded with it must ask — the regression was a new
 * radio stuck in "handshaking" forever. Values pinned to Android's
 * constants (10/11/12) so a mix-up between them shows here.
 */
class BlePairingTest {
    @Test
    fun androidConstantsAreTheOnesThisReadsAs() {
        assertEquals(10, BOND_NONE)
        assertEquals(11, BOND_BONDING)
        assertEquals(12, BOND_BONDED)
    }

    @Test
    fun anUnpairedRadioIsBondedBeforeSubscribing() {
        assertTrue(BlePairing.needsBond(BOND_NONE))
        assertTrue(BlePairing.needsBond(BOND_BONDING))
    }

    @Test
    fun aPairedRadioIsNotAskedAgain() {
        assertFalse(BlePairing.needsBond(BOND_BONDED))
    }

    @Test
    fun theNormalSequenceEndsPaired() {
        assertEquals(Step.Waiting, BlePairing.next(BOND_BONDING, BOND_NONE))
        assertEquals(Step.Paired, BlePairing.next(BOND_BONDED, BOND_BONDING))
    }

    @Test
    fun aCancelledDialogOrWrongPinIsAFailureNotAnEndlessWait() {
        assertEquals(Step.Failed, BlePairing.next(BOND_NONE, BOND_BONDING))
    }

    @Test
    fun theStartingNoneIsWaitedPast() {
        assertEquals(Step.Waiting, BlePairing.next(BOND_NONE, BOND_NONE))
    }

    @Test
    fun anUnknownStateIsWaitedOnNotTreatedAsPaired() {
        assertEquals(Step.Waiting, BlePairing.next(Int.MIN_VALUE, BOND_BONDING))
    }

    @Test
    fun aSuccessfulProbeMeansTheLinkIsEncrypted() {
        assertEquals(BlePairing.ReadOutcome.Secured, BlePairing.readOutcome(0))
    }

    @Test
    fun authenticationFailuresAreRefusalsNotSilence() {
        // 5, 15 and 0x89 are what Android reports when the PIN was wrong,
        // the dialog was cancelled, or the radio has forgotten our keys.
        for (s in listOf(5, 15, 0x89)) {
            assertEquals(BlePairing.ReadOutcome.Refused, BlePairing.readOutcome(s), "status $s")
        }
    }

    @Test
    fun aNotReadableCharacteristicDoesNotBlockTheHandshake() {
        assertEquals(BlePairing.ReadOutcome.NotApplicable, BlePairing.readOutcome(2))
        assertEquals(BlePairing.ReadOutcome.NotApplicable, BlePairing.readOutcome(-1))
        assertEquals(BlePairing.ReadOutcome.NotApplicable, BlePairing.readOutcome(133))
    }

    @Test
    fun statusConstantsMatchAndroids() {
        assertEquals(android.bluetooth.BluetoothGatt.GATT_SUCCESS, BlePairing.GATT_SUCCESS)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_READ_NOT_PERMITTED, BlePairing.GATT_READ_NOT_PERMITTED)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BlePairing.GATT_INSUFFICIENT_AUTHENTICATION)
        assertEquals(android.bluetooth.BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION, BlePairing.GATT_INSUFFICIENT_ENCRYPTION)
    }
}
