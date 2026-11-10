# BassForge

Bass booster / equalizer for Android with a physics-based fidget-spinner knob.

- **Audio:** DynamicsProcessing chain (10-band pre-EQ -> 4-band compressor -> post-EQ -> limiter) on Android 9+,
  BassBoost + Equalizer fallback on older versions. Gain staging: -3 dB pre-amp for every +6 dB of boost.
- **How it finds music:** players that support external equalizers broadcast
  `OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`. BassForge attaches to those sessions. Players that never send them
  (some browsers, some games) can't be processed without root. Session 0 (global mix) is blocked for normal apps.
- **UI:** cinematic intro, living background, spinner with inertia + motion blur + beat shockwaves, live spectrum, 10-band EQ.

## Build (no laptop)
Push to GitHub -> Actions -> "Build APK" -> download the `BassForge-debug-apk` artifact.
Local: `gradle :app:assembleDebug` (JDK 17, Android SDK 34).

## Install
Debug APK is signed with the debug key, so it installs directly (enable "install unknown apps").
