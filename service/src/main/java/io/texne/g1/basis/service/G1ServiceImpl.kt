package io.texne.g1.basis.service

import android.util.Log
import io.texne.g1.basis.service.protocol.ObserveStateCallback
import io.texne.g1.basis.service.protocol.OperationCallback
import io.texne.g1.basis.service.protocol.IG1Service

class G1ServiceImpl : IG1Service.Stub() {

    override fun observeState(callback: ObserveStateCallback?) {
        Log.d(TAG, "observeState(callback=$callback)")
    }

    override fun lookForGlasses() {
        Log.d(TAG, "lookForGlasses()")
    }

    override fun connectGlasses(id: String?, callback: OperationCallback?) {
        Log.d(TAG, "connectGlasses(id=$id, callback=$callback)")
    }

    override fun disconnectGlasses(id: String?, callback: OperationCallback?) {
        Log.d(TAG, "disconnectGlasses(id=$id, callback=$callback)")
    }

    override fun displayTextPage(
        id: String?,
        page: Array<out String?>?,
        callback: OperationCallback?
    ) {
        Log.d(TAG, "displayTextPage(id=$id, page=${page?.contentToString()}, callback=$callback)")
    }

    override fun stopDisplaying(id: String?, callback: OperationCallback?) {
        Log.d(TAG, "stopDisplaying(id=$id, callback=$callback)")
    }

    override fun connectGlasses(deviceAddress: String?) {
        Log.d(TAG, "connectGlasses(deviceAddress=$deviceAddress)")
    }

    override fun connectGlasses() {
        Log.d(TAG, "connectGlasses()")
    }

    override fun disconnectGlasses() {
        Log.d(TAG, "disconnectGlasses()")
    }

    override fun isConnected(): Boolean {
        Log.d(TAG, "isConnected()")
        return false
    }

    override fun sendMessage(msg: String?) {
        Log.d(TAG, "sendMessage(msg=$msg)")
    }

    private companion object {
        const val TAG = "G1ServiceImpl"
    }
}
