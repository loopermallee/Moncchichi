package io.texne.g1.basis.service.protocol;

parcelable G1ServiceState {
    int connectionStatus;   // 0 = disconnected, 1 = connecting, 2 = connected
    int batteryLevel;       // 0–100 percentage
    String deviceName;      // name of the paired glasses
    String deviceId;        // unique ID of the paired device
    boolean isDisplaying;   // true if text/page is currently shown
}
