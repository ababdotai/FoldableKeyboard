package com.pckeyboard.ime.remote;

/** Guarded HID keyboard endpoint running as the Shizuku shell user. */
interface ISystemInputService {
    int sendKey(int keyCode, int metaState, int targetUid, int displayId) = 0;
    int checkCapabilities(int targetUid, int displayId) = 1;
    void destroy() = 16777114;
}
