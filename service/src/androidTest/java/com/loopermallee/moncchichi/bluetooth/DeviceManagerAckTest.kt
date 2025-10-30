package com.loopermallee.moncchichi.bluetooth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.loopermallee.moncchichi.bluetooth.BluetoothConstants.ACK_FAILURE
import com.loopermallee.moncchichi.bluetooth.BluetoothConstants.ACK_SUCCESS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceManagerAckTest {

    @Test
    fun sendAndAwaitAckReturnsTrueForAckSuccess() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DeviceManager(context, this)
        val opcode: Byte = BluetoothConstants.OPCODE_SEND_BMP

        val result = async {
            manager.sendAndAwaitAck(
                payload = byteArrayOf(0x01),
                opcode = opcode,
                timeoutMs = 1_000L,
                commandSender = { true },
            )
        }

        runCurrent()
        manager.emitAck(byteArrayOf(opcode, ACK_SUCCESS))

        assertTrue(result.await())
    }

    @Test
    fun sendAndAwaitAckReturnsFalseForAckFailure() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = DeviceManager(context, this)
        val opcode: Byte = BluetoothConstants.OPCODE_SEND_BMP

        val result = async {
            manager.sendAndAwaitAck(
                payload = byteArrayOf(0x01),
                opcode = opcode,
                timeoutMs = 1_000L,
                commandSender = { true },
            )
        }

        runCurrent()
        manager.emitAck(byteArrayOf(opcode, ACK_FAILURE))

        assertFalse(result.await())
    }

    private fun DeviceManager.emitAck(bytes: ByteArray) {
        val field = DeviceManager::class.java.getDeclaredField("writableIncoming")
        field.isAccessible = true
        val sharedFlow = field.get(this) as MutableSharedFlow<ByteArray>
        sharedFlow.tryEmit(bytes)
    }
}
