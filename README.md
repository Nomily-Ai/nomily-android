# Nomily Android

Native Android implementation — connects to a D·NOTE recording device over BLE, browses and transfers recordings, drives device settings, and runs ASR (Azure or local Whisper). Kotlin + Jetpack Compose. Project overview and licence: [nomily-app](https://github.com/Nomily-Ai/nomily-app).

## Requirements

- JDK 21
- Android Studio (or Gradle) with the Android SDK
- Android 7.0+ (API 24) device — BLE and Wi-Fi transfer need real hardware
- The `argon2kt` native key-derivation dependency pulls its NDK bits through Gradle

## Build

```bash
cd android
./gradlew :app:assembleDebug     # or open the android/ project in Android Studio
```

Install and run the debug APK on a connected device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Modules

| Module | What it is |
|---|---|
| `app/` | UI (Compose) plus Android platform integration (BLE, Wi-Fi, storage) |
| `core/` | Protocol, crypto, and audio logic, shared and unit-tested off-device |

Run the shared-logic unit tests:

```bash
./gradlew :core:test
```

## Encryption

When a device reports encryption on, recordings are ChaCha20-encrypted and the app derives the key from the passphrase you set, using Argon2id through the `argon2kt` NDK library (`Argon2KtProvider`). The derivation is identical across Nomily clients, so a clip encrypted by one can be decrypted by another.
