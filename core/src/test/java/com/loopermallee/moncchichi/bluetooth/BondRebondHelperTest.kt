package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BOND_FAILURE_UNKNOWN = 10
private const val UNBOND_REASON_REPEATED_ATTEMPTS = 7

class BondRebondHelperTest {

    @Test
    fun rebondRequestedWhenBondedDeviceFallsBackToNone() {
        val shouldRebond = shouldAttemptRebondAfterLoss(
            previousBondState = BluetoothDevice.BOND_BONDED,
            newBondState = BluetoothDevice.BOND_NONE,
            reason = BOND_FAILURE_UNKNOWN,
        )

        assertTrue(shouldRebond)
    }

    @Test
    fun rebondSkippedWhenDeviceWasNotBonded() {
        val shouldRebond = shouldAttemptRebondAfterLoss(
            previousBondState = BluetoothDevice.BOND_NONE,
            newBondState = BluetoothDevice.BOND_NONE,
            reason = BOND_FAILURE_UNKNOWN,
        )

        assertFalse(shouldRebond)
    }

    @Test
    fun repeatedAttemptReasonDoesNotTriggerRebondLoop() {
        val shouldRebond = shouldAttemptRebondAfterLoss(
            previousBondState = BluetoothDevice.BOND_BONDED,
            newBondState = BluetoothDevice.BOND_NONE,
            reason = UNBOND_REASON_REPEATED_ATTEMPTS,
        )

        assertFalse(shouldRebond)
    }
}
