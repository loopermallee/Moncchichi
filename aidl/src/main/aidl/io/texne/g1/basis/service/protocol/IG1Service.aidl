package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.IG1StateCallback;
import io.texne.g1.basis.service.protocol.OperationCallback;

interface IG1Service {
    void observeState(IG1StateCallback callback);
    void lookForGlasses();
    void connectGlasses(String id, @nullable OperationCallback callback);
    void disconnectGlasses(String id, @nullable OperationCallback callback);
    void displayTextPage(String id, in String[] page, @nullable OperationCallback callback);
    void stopDisplaying(String id, @nullable OperationCallback callback);
    void connectGlasses(String deviceId);
    void disconnectGlasses(String deviceId);
    void connectGlasses();
    void disconnectGlasses();
    boolean isConnected();
    void sendMessage(String message);
    void displayTextPage(String text, int page, int flags);
    void stopDisplaying(int flags);
}
