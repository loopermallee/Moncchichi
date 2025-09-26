package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.G1ServiceState;

interface ObserveStateCallback {
    void onStateChange(in G1ServiceState state);
}
