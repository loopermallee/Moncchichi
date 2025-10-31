package com.loopermallee.moncchichi.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val UNBOND_REASON_AUTH_FAILED = 1
private const val UNBOND_REASON_AUTH_REJECTED = 2
private const val UNBOND_REASON_AUTH_CANCELED = 3
private const val UNBOND_REASON_REMOTE_DEVICE_DOWN = 4
private const val UNBOND_REASON_REMOVED = 5
private const val UNBOND_REASON_OPERATION_CANCELED = 6
private const val UNBOND_REASON_REMOTE_AUTH_CANCELED = 8

class BondRetryDeciderTest {

    @Test
    fun nextRetryAttemptIncrementsUntilLimitThenStops() {
        var now = 0L
        val decider = BondRetryDecider(
            maxAttempts = 3,
            retryWindowMs = 30_000L,
        ) { now }

        assertEquals(1, decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))
        assertEquals(2, decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))
        assertEquals(3, decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))
        assertNull(decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))

        now = 31_000L
        assertEquals(1, decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))
    }

    @Test
    fun nonTransientReasonsNeverTriggerRetries() {
        var now = 0L
        val decider = BondRetryDecider(
            maxAttempts = 3,
            retryWindowMs = 30_000L,
        ) { now }

        assertNull(decider.nextRetryAttempt(UNBOND_REASON_OPERATION_CANCELED))

        assertEquals(1, decider.nextRetryAttempt(UNBOND_REASON_AUTH_FAILED))
        assertEquals(2, decider.nextRetryAttempt(UNBOND_REASON_REMOVED))
    }

    @Test
    fun transientReasonsAreRecognized() {
        val expectedTransientReasons = listOf(
            UNBOND_REASON_AUTH_FAILED,
            UNBOND_REASON_AUTH_REJECTED,
            UNBOND_REASON_AUTH_CANCELED,
            UNBOND_REASON_REMOTE_DEVICE_DOWN,
            UNBOND_REASON_REMOVED,
            UNBOND_REASON_REMOTE_AUTH_CANCELED,
        )

        expectedTransientReasons.forEach { reason ->
            assertTrue(
                BondRetryDecider.isTransientReason(reason),
                "Expected $reason to be treated as transient",
            )
        }
    }
}
