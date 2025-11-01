package com.loopermallee.moncchichi.bluetooth

import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BondSymmetryTest {

    @Test
    fun leftBondSuccessSchedulesRightSequence() = runTest {
        val context = mockk<Context>(relaxed = true)
        val logger = mockk<MoncchichiLogger>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val service = MoncchichiBleService(context, scope, logger)

        val method = MoncchichiBleService::class.java.getDeclaredMethod(
            "handleBondTransitions",
            MoncchichiBleService.Lens::class.java,
            MoncchichiBleService.LensStatus::class.java,
            G1BleClient.State::class.java,
        ).apply { isAccessible = true }

        method.invoke(
            service,
            MoncchichiBleService.Lens.LEFT,
            MoncchichiBleService.LensStatus(bonded = false),
            G1BleClient.State(bonded = true),
        )

        val field = MoncchichiBleService::class.java.getDeclaredField("pendingRightBondSequence").apply {
            isAccessible = true
        }
        assertTrue(field.get(service) as Boolean)

        method.invoke(
            service,
            MoncchichiBleService.Lens.RIGHT,
            MoncchichiBleService.LensStatus(bonded = false),
            G1BleClient.State(bonded = true),
        )

        assertFalse(field.get(service) as Boolean)
    }
}
