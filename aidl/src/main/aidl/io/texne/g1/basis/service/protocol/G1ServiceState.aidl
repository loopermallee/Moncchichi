package io.texne.g1.basis.service.protocol;

import io.texne.g1.basis.service.protocol.G1Glasses;

parcelable G1ServiceState {
    const int READY = 0;
    const int LOOKING = 1;
    const int LOOKED = 2;
    const int ERROR = -1;

    int status;
    G1Glasses[] glasses;
}
