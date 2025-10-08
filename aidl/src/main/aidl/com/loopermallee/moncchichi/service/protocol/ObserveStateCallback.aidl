package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.G1ServiceState;

interface ObserveStateCallback {
    void onStateChange(in G1ServiceState state);
}
