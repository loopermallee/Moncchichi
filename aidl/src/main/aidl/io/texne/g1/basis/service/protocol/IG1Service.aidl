// IG1Service.aidl
package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.ObserveStateCallback;
import io.texne.g1.basis.service.protocol.OperationCallback;

interface IG1Service {
    void observeState(ObserveStateCallback callback);
    void lookForGlasses();
    void connectGlasses(String id, @nullable OperationCallback callback);
    void disconnectGlasses(String id, @nullable OperationCallback callback);
    void displayTextPage(String id, in String[] page, @nullable OperationCallback callback);
    void stopDisplaying(String id, @nullable OperationCallback callback);
    void connectGlasses();
    void disconnectGlasses();
    boolean isConnected();
    void sendMessage(String msg);
}
