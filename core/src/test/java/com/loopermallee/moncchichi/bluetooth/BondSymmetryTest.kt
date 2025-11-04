package com.loopermallee.moncchichi.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.loopermallee.moncchichi.MoncchichiLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun leftReadyLaunchesRightConnect() = runTest {
        val context = mockk<Context>(relaxed = true)
        val logger = mockk<MoncchichiLogger>(relaxed = true)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val service = spyk(MoncchichiBleService(context, scope, logger))

        val leftState = MutableStateFlow(G1BleClient.State(bonded = true, attMtu = 256))
        val leftClient = mockk<G1BleClient>(relaxed = true) {
            every { state } returns leftState
        }
        val rightDevice = mockk<BluetoothDevice>(relaxed = true)

        val clientRecordClass = Class.forName(
            "com.loopermallee.moncchichi.bluetooth.MoncchichiBleService\$ClientRecord",
        )
        val constructor = clientRecordClass.getDeclaredConstructor(
            MoncchichiBleService.Lens::class.java,
            G1BleClient::class.java,
            MutableList::class.java,
        ).apply { isAccessible = true }
        val clientRecord = constructor.newInstance(
            MoncchichiBleService.Lens.LEFT,
            leftClient,
            mutableListOf<Job>(),
        )

        val clientRecordsField = MoncchichiBleService::class.java.getDeclaredField("clientRecords").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val clientRecords = clientRecordsField.get(service) as MutableMap<MoncchichiBleService.Lens, Any>
        clientRecords[MoncchichiBleService.Lens.LEFT] = clientRecord

        val knownDevicesField = MoncchichiBleService::class.java.getDeclaredField("knownDevices").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val knownDevices = knownDevicesField.get(service) as MutableMap<MoncchichiBleService.Lens, BluetoothDevice>
        knownDevices[MoncchichiBleService.Lens.RIGHT] = rightDevice

        val pendingField = MoncchichiBleService::class.java.getDeclaredField("pendingRightBondSequence").apply {
            isAccessible = true
        }
        pendingField.setBoolean(service, true)

        coEvery { service.connect(rightDevice, MoncchichiBleService.Lens.RIGHT) } returns true

        val method = MoncchichiBleService::class.java.getDeclaredMethod("maybeStartCompanionSequence").apply {
            isAccessible = true
        }

        method.invoke(service)
        scope.runCurrent()

        coVerify { service.connect(rightDevice, MoncchichiBleService.Lens.RIGHT) }
        assertFalse(pendingField.getBoolean(service))
    }
}
