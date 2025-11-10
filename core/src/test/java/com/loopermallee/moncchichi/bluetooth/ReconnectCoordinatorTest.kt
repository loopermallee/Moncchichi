package com.loopermallee.moncchichi.bluetooth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectCoordinatorTest {

    @Test
    fun retriesWithBackoffUntilSuccess() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val attempts = mutableListOf<Int>()
        var successInvoked = false
        val coordinator = ReconnectCoordinator(
            scope = scope,
            shouldContinue = { true },
            onAttempt = { _, attempt, _, _ -> attempts += attempt },
            attempt = { _, attempt, _ -> attempt == 3 },
            onSuccess = { successInvoked = true },
            onStop = { },
            updateState = { _, _ -> },
            baseDelayMs = 10L,
            maxDelayMs = 40L,
        )

        coordinator.schedule(MoncchichiBleService.Lens.LEFT, "test")

        scope.advanceTimeBy(10)
        scope.runCurrent()
        scope.advanceTimeBy(20)
        scope.runCurrent()
        scope.advanceTimeBy(40)
        scope.runCurrent()

        assertEquals(listOf(1, 2, 3), attempts)
        assertTrue(successInvoked)
    }

    @Test
    fun cancelStopsPendingAttempts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        val attempts = mutableListOf<Int>()
        val coordinator = ReconnectCoordinator(
            scope = scope,
            shouldContinue = { true },
            onAttempt = { _, attempt, _, _ -> attempts += attempt },
            attempt = { _, _, _ -> false },
            onSuccess = { },
            onStop = { },
            updateState = { _, _ -> },
            baseDelayMs = 10L,
            maxDelayMs = 40L,
        )

        coordinator.schedule(MoncchichiBleService.Lens.LEFT, "drop")

        scope.advanceTimeBy(10)
        scope.runCurrent()
        coordinator.cancel(MoncchichiBleService.Lens.LEFT)

        scope.advanceTimeBy(100)
        scope.runCurrent()

        assertEquals(listOf(1), attempts)
    }
}
