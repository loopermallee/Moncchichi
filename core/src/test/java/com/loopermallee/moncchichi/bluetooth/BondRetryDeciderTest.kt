package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BondRetryDeciderTest {

    @Test
    fun nextRetryAttemptIncrementsUntilLimitThenStops() {
        var now = 0L
        val decider = BondRetryDecider(
            maxAttempts = 3,
            retryWindowMs = 30_000L,
        ) { now }

        assertEquals(1, decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))
        assertEquals(2, decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))
        assertEquals(3, decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))
        assertNull(decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))

        now = 31_000L
        assertEquals(1, decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))
    }

    @Test
    fun nonTransientReasonsNeverTriggerRetries() {
        var now = 0L
        val decider = BondRetryDecider(
            maxAttempts = 3,
            retryWindowMs = 30_000L,
        ) { now }

        assertNull(decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_REMOVED))
        assertNull(decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_OPERATION_CANCELED))

        assertEquals(1, decider.nextRetryAttempt(BluetoothDevice.UNBOND_REASON_AUTH_FAILED))
    }

    @Test
    fun transientReasonsAreRecognized() {
        val expectedTransientReasons = listOf(
            BluetoothDevice.UNBOND_REASON_AUTH_FAILED,
            BluetoothDevice.UNBOND_REASON_AUTH_REJECTED,
            BluetoothDevice.UNBOND_REASON_AUTH_CANCELED,
            BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN,
        )

        expectedTransientReasons.forEach { reason ->
            assertTrue(
                BondRetryDecider.isTransientReason(reason),
                "Expected $reason to be treated as transient",
            )
        }
    }
}
