package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.G1Glasses;

interface IG1StateCallback {
    void onStateChanged(int status, in G1Glasses[] glasses);
}
