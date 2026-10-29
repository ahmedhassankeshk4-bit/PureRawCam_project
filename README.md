# PureRawCam Android project

This is a starter Android Studio project around the supplied `MainActivity.kt`.

## Build
- Open this folder in Android Studio.
- Install Android SDK Platform 35 and JDK 17 when prompted.
- Sync Gradle, then use Build > Build APK(s).
- Minimum Android version: Android 12 (API 31).

## Important limitations
- This project is not yet device-tested and no APK has been built in this environment.
- The supplied activity captures DNG/RAW only. A working portrait mode requires additional implementation and device testing; this starter does not claim to provide portrait/bokeh yet.
- RAW resolution, lens access, and focus support depend on the phone's Camera2 implementation.
- The current UI is the original minimal UI, not yet the requested iOS-inspired redesign.
