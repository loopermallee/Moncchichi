package io.texne.g1.basis.service.protocol;

interface IG1StateCallback {
    void onStateChanged(boolean connected, int batteryPercent);
}
