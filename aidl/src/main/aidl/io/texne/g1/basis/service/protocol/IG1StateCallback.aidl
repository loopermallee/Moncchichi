package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.G1Glasses;

interface IG1StateCallback {
    void onStateChanged(int status, in G1Glasses[] glasses);
}
