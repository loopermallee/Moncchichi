package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.IG1StateCallback;

interface IG1Service {
    void observeState(IG1StateCallback callback);
    void connectGlasses(String deviceId);
    void disconnectGlasses();
    void displayTextPage(String text);
    void stopDisplaying();
}
