package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.G1Glasses;

parcelable G1ServiceState {
    const int READY = 0;
    const int LOOKING = 1;
    const int LOOKED = 2;
    const int ERROR = -1;

    int status;
    G1Glasses[] glasses;
}
