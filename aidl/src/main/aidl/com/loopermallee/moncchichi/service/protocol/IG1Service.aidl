package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.IG1StateCallback;
import com.loopermallee.moncchichi.service.protocol.OperationCallback;

interface IG1Service {
    void observeState(IG1StateCallback callback);
    void lookForGlasses();
    void connectGlasses(String id, @nullable OperationCallback callback);
    void disconnectGlasses(String id, @nullable OperationCallback callback);
    void displayTextPage(String id, in String[] page, @nullable OperationCallback callback);
    void stopDisplaying(String id, @nullable OperationCallback callback);
    void connectGlassesById(String deviceId);
    void disconnectGlassesById(String deviceId);
    void connectPreferredGlasses();
    void disconnectPreferredGlasses();
    boolean isConnected();
    void sendMessage(String message);
    void displayLegacyTextPage(String text, int page, int flags);
    void stopDisplayingWithFlags(int flags);
}
