package com.pckeyboard.ime.remote

/** Content-free status codes shared by the app and the privileged input service. */
internal object SystemInputStatus {
    // READY confirms HID registration, not focus safety or remote delivery.
    const val READY = 0
    const val UNKNOWN_ERROR = -1
    const val UNSUPPORTED_ANDROID = -2
    const val CALLER_REJECTED = -3
    const val INVALID_TARGET = -4
    const val INVALID_KEY = -5
    const val UU_UNAVAILABLE = -6
    const val KEYBOARD_UNAVAILABLE = -7
    const val TARGETED_API_UNAVAILABLE = -8
    const val DISPLAY_API_UNAVAILABLE = -9
    const val PERMISSION_DENIED = -10
    const val INJECTION_ERROR = -11
    const val HID_UNAVAILABLE = -12
    const val FOCUS_UNSAFE = -13
    const val UNSUPPORTED_DISPLAY = -14
}
