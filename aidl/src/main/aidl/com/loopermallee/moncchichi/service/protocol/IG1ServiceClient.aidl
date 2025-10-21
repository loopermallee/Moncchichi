// IG1ServiceClient.aidl
package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.G1Glasses;
import com.loopermallee.moncchichi.service.protocol.ObserveStateCallback;
import com.loopermallee.moncchichi.service.protocol.OperationCallback;

interface IG1ServiceClient {
    void observeState(ObserveStateCallback callback);
    void displayTextPage(String id, in String[] page, @nullable OperationCallback callback);
    void stopDisplaying(String id, @nullable OperationCallback callback);
}
