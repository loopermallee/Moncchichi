package com.loopermallee.moncchichi.service.protocol;

import com.loopermallee.moncchichi.service.protocol.G1Glasses;

interface IG1DisplayService {
    /** Send a new text to display / scroll */
    void displayText(in String text);

    /** Stop / clear the current display */
    void stopDisplay();

    /** Pause the current display without clearing */
    void pauseDisplay();

    /** Resume a paused display */
    void resumeDisplay();

    /** Adjust the scroll speed for the display */
    void setScrollSpeed(float speed);

    /** Get status about the glasses / display */
    G1Glasses getGlassesInfo();
}
