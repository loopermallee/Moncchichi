package com.loopermallee.moncchichi.hub.tools.impl

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.widget.Toast
import com.loopermallee.moncchichi.bluetooth.BluetoothScanner
import com.loopermallee.moncchichi.bluetooth.DiscoveredDevice
import com.loopermallee.moncchichi.bluetooth.MoncchichiBleService
import com.loopermallee.moncchichi.hub.data.telemetry.BleTelemetryRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BleToolLiveImplConnectTest {

    @BeforeTest
    fun setUp() {
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
    }

    @AfterTest
    fun tearDown() {
        unmockkStatic(Toast::class)
    }

    @Test
    fun `connect right-first taps left lens before right`() = runTest {
        val events = MutableSharedFlow<MoncchichiBleService.MoncchichiEvent>()
        val state = MutableStateFlow(MoncchichiBleService.ServiceState())
        val service = mockk<MoncchichiBleService>(relaxed = true) {
            every { this@mockk.events } returns events
            every { this@mockk.state } returns state
        }
        val telemetry = mockk<BleTelemetryRepository>(relaxed = true) {
            every { snapshot } returns MutableStateFlow(BleTelemetryRepository.Snapshot())
        }
        val scannerDevices = MutableStateFlow(
            listOf(
                DiscoveredDevice(
                    name = "Even G1_100 LEFT",
                    address = "AA:BB:CC:DD:EE:01",
                    rssi = -40,
                    timestampNanos = null,
                ),
            ),
        )
        val scanner = mockk<BluetoothScanner>(relaxed = true) {
            every { devices } returns scannerDevices
            every { start() } just Runs
            every { stop() } just Runs
        }
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val leftDevice = mockk<BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:01"
            every { name } returns "Even G1_100 LEFT"
        }
        val rightDevice = mockk<BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:02"
            every { name } returns "Even G1_100 RIGHT"
        }
        val deviceMap = mapOf(
            leftDevice.address to leftDevice,
            rightDevice.address to rightDevice,
        )
        coEvery { service.connect(leftDevice, MoncchichiBleService.Lens.LEFT) } returns true
        coEvery { service.connect(rightDevice, MoncchichiBleService.Lens.RIGHT) } returns true

        val tool = BleToolLiveImpl(
            context = context,
            service = service,
            telemetry = telemetry,
            scanner = scanner,
            appScope = this,
            resolveDeviceFn = { mac -> deviceMap[mac] },
        )

        val connected = tool.connect(rightDevice.address)

        assertTrue(connected)
        coVerifySequence {
            service.connect(leftDevice, MoncchichiBleService.Lens.LEFT)
            service.connect(rightDevice, MoncchichiBleService.Lens.RIGHT)
        }
    }

    @Test
    fun `right lens skipped when left handshake fails`() = runTest {
        val events = MutableSharedFlow<MoncchichiBleService.MoncchichiEvent>()
        val state = MutableStateFlow(MoncchichiBleService.ServiceState())
        val service = mockk<MoncchichiBleService>(relaxed = true) {
            every { this@mockk.events } returns events
            every { this@mockk.state } returns state
        }
        val telemetry = mockk<BleTelemetryRepository>(relaxed = true) {
            every { snapshot } returns MutableStateFlow(BleTelemetryRepository.Snapshot())
        }
        val scannerDevices = MutableStateFlow(
            listOf(
                DiscoveredDevice(
                    name = "Even G1_200 LEFT",
                    address = "AA:BB:CC:DD:EE:11",
                    rssi = -38,
                    timestampNanos = null,
                ),
            ),
        )
        val scanner = mockk<BluetoothScanner>(relaxed = true) {
            every { devices } returns scannerDevices
            every { start() } just Runs
            every { stop() } just Runs
        }
        val context = mockk<Context>(relaxed = true) {
            every { applicationContext } returns this@mockk
        }
        val leftDevice = mockk<BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:11"
            every { name } returns "Even G1_200 LEFT"
        }
        val rightDevice = mockk<BluetoothDevice> {
            every { address } returns "AA:BB:CC:DD:EE:12"
            every { name } returns "Even G1_200 RIGHT"
        }
        val deviceMap = mapOf(
            leftDevice.address to leftDevice,
            rightDevice.address to rightDevice,
        )
        coEvery { service.connect(leftDevice, MoncchichiBleService.Lens.LEFT) } returns false

        val tool = BleToolLiveImpl(
            context = context,
            service = service,
            telemetry = telemetry,
            scanner = scanner,
            appScope = this,
            resolveDeviceFn = { mac -> deviceMap[mac] },
        )

        val connected = tool.connect(rightDevice.address)

        assertFalse(connected)
        coVerifySequence {
            service.connect(leftDevice, MoncchichiBleService.Lens.LEFT)
        }
    }
}
