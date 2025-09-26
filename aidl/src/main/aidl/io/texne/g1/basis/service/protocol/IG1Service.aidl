package io.texne.g1.basis.service.protocol;

interface IG1Service {
    void connectGlasses(String deviceAddress);
    void disconnectGlasses();
    boolean isConnected();
    void sendMessage(String msg);
    void lookForGlasses();
    void observeState(IG1StateCallback callback);
    void displayTextPage(String text);
    void stopDisplaying();
}
