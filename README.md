# 🧠 Moncchichi

Moncchichi is a collection of Android components that build a reliable Bluetooth Low Energy (BLE) pipeline for the Even Realities G1 smart glasses. The repository contains the background service that manages the connection, a Jetpack Compose hub for pairing and diagnostics, a teleprompter/subtitles companion, and helper libraries that make it possible for any client app to speak to the glasses.

---

## Current capabilities
- **Binder backed BLE service** – `service/` exposes a foreground `G1DisplayService` that keeps a persistent BLE session alive, persists reconnect hints, surfaces heartbeat telemetry, and accepts direct connect requests from trusted clients.【F:service/src/main/java/com/loopermallee/moncchichi/bluetooth/G1DisplayService.kt†L1-L143】【F:service/src/main/java/com/loopermallee/moncchichi/bluetooth/G1DisplayService.kt†L180-L268】
- **Soul Tether hub app** – `hub/` ships a Compose driven dashboard for scanning, selecting, and controlling glasses, a connection log, and a dedicated data console for manual protocol experiments.【F:hub/src/main/AndroidManifest.xml†L1-L41】【F:hub/src/main/java/com/loopermallee/moncchichi/ui/screens/G1DataConsoleScreen.kt†L1-L115】
- **Client APIs** – `client/` provides the `G1ServiceManager` and `G1ServiceClient` wrappers so external processes can bind to the service, observe state, and push formatted pages or free-form messages without touching AIDL directly.【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceManager.kt†L1-L116】【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceCommon.kt†L1-L123】【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceClient.kt†L1-L110】
- **Speech driven subtitles demo** – `subtitles/` is a standalone Hilt + Compose app that streams Android SpeechRecognizer partial results to the glasses, manages a queue of paged captions, and renders a HUD preview overlay for live debugging.【F:subtitles/src/main/java/io/texne/g1/subtitles/ui/SubtitlesScreen.kt†L1-L142】【F:subtitles/src/main/java/io/texne/g1/subtitles/ui/SubtitlesViewModel.kt†L1-L157】【F:subtitles/src/main/java/io/texne/g1/subtitles/model/Recognizer.kt†L1-L104】
- **AIDL contract packages** – `aidl/` defines the shared IPC surface (`IG1Service`, `IG1ServiceClient`, parcelable `G1Glasses`, and state callbacks) that both the hub and subtitles modules depend on.【F:aidl/src/main/aidl/io/texne/g1/basis/service/protocol/IG1Service.aidl†L1-L38】【F:aidl/src/main/aidl/io/texne/g1/basis/service/protocol/G1Glasses.aidl†L1-L44】
- **Sample display service** – `android/app/` embeds a minimal implementation of `IG1DisplayService` and a native activity that demonstrates driving the on-device renderer from a plain Android Activity.【F:android/app/src/main/java/io/texne/g1/basis/service/G1DisplayService.kt†L1-L135】【F:android/app/src/main/java/com/teleprompter/MainActivity.kt†L1-L55】

---

## Module map
| Module | What it contains |
| --- | --- |
| `core/` | Shared BLE models (`G1Device`, connection state enums) and logging utilities used by the service and hub.【F:core/src/main/java/io/texne/g1/basis/core/G1BLEManager.kt†L1-L53】【F:core/src/main/java/com/loopermallee/moncchichi/bluetooth/G1ConnectionState.kt†L1-L54】 |
| `service/` | The foreground BLE manager, reconnection heuristics, telemetry flow, and binder exposed commands (`connect`, `sendMessage`, display helpers).【F:service/src/main/java/com/loopermallee/moncchichi/bluetooth/G1DisplayService.kt†L1-L224】 |
| `hub/` | Soul Tether Compose UI, data console, pairing dashboard, service binding repository, and instrumentation entry points.【F:hub/src/main/java/io/texne/g1/hub/model/Repository.kt†L1-L70】【F:hub/src/main/java/io/texne/g1/hub/ui/ApplicationViewModel.kt†L1-L135】 |
| `client/` | Thin Kotlin APIs that hide binder boilerplate and expose high level helpers to display formatted pages or start discovery without managing `ServiceConnection` manually.【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceCommon.kt†L1-L123】【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceManager.kt†L1-L116】 |
| `subtitles/` | Speech recognizer pipeline, HUD preview overlay, and caption pagination logic for the teleprompter experience.【F:subtitles/src/main/java/io/texne/g1/subtitles/model/Repository.kt†L1-L146】【F:subtitles/src/main/java/io/texne/g1/subtitles/ui/G1HudOverlay.kt†L1-L120】 |
| `android/app/` | The sample display service that mirrors teleprompter commands and a barebones UI to exercise it.【F:android/app/src/main/java/io/texne/g1/basis/service/G1DisplayService.kt†L1-L135】 |
| `aidl/` | Parcelable and binder interface definitions shared by every module that binds to the BLE service.【F:aidl/src/main/aidl/io/texne/g1/basis/service/protocol/IG1Service.aidl†L1-L38】 |
| `docs/` | Engineering notes (for example the nullability audit that tracks Kotlin vs AIDL guarantees).【F:docs/nullability-audit.md†L1-L15】 |

---

## Architecture snapshot
1. **AIDL contract** – `aidl/` defines the IPC shape for service discovery, glass status, display commands, and telemetry callbacks.【F:aidl/src/main/aidl/io/texne/g1/basis/service/protocol/IG1StateCallback.aidl†L1-L26】
2. **Foreground BLE service** – `service/` binds to the Android BLE stack, tracks connection state, and publishes binder methods consumed by higher layers.【F:service/src/main/java/com/loopermallee/moncchichi/bluetooth/G1DisplayService.kt†L1-L224】
3. **Client wrappers** – `client/` turns binder APIs into coroutines, adds formatting helpers, and normalizes optional fields like battery percentage.【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceCommon.kt†L1-L123】
4. **Hub UI** – `hub/` binds to `G1ServiceManager`, renders Compose screens for discovery and telemetry, and lets developers send test payloads from the data console.【F:hub/src/main/java/io/texne/g1/hub/model/Repository.kt†L1-L70】【F:hub/src/main/java/com/loopermallee/moncchichi/ui/screens/G1DataConsoleScreen.kt†L1-L115】
5. **Companion apps** – `subtitles/` and the sample `android/app/` consume the same client API to provide specialized experiences (speech-driven captions or native teleprompter controls).【F:subtitles/src/main/java/io/texne/g1/subtitles/model/Repository.kt†L1-L146】【F:android/app/src/main/java/com/teleprompter/MainActivity.kt†L1-L55】

---

## Prerequisites
- Android Studio Koala Feature Drop (2024.1.2) or newer with AGP 8.8 support.
- Android SDK 34 and latest Google Play services repos.
- JDK 17 (Gradle and Kotlin toolchains are configured for Java 17).【F:build.gradle.kts†L1-L41】
- A device or emulator with Bluetooth LE (hardware testing recommended for G1 glasses).

---

## Build & run
1. **Sync the project**
   ```bash
   ./gradlew tasks
   ```
   This also downloads the shared `basis` dependencies declared in `gradle/libs.versions.toml` (Kotlin 1.9.24, Compose BOM 2025.01).【F:gradle/libs.versions.toml†L1-L44】
2. **Assemble the hub**
   ```bash
   ./gradlew :hub:assembleDebug
   ```
   Install the resulting APK to interact with the Soul Tether dashboard and data console.
3. **Run the subtitles demo**
   ```bash
   ./gradlew :subtitles:installDebug
   ```
   Once the hub service is installed on the same device, launch the subtitles app to stream speech recognition results to the glasses via the shared binder.
4. **Exercise the standalone display service**
   ```bash
   ./gradlew :android:app:installDebug
   ```
   This deploys the sample `IG1DisplayService` implementation used by the teleprompter activity.

Unit tests are not yet wired up for the modules; rely on the Gradle assemble tasks and device-level smoke tests when validating changes.

---

## Development notes
- All modules use Kotlin coroutines for asynchronous work; service-level flows surface connection status, battery telemetry, and speech recognition events.【F:service/src/main/java/com/loopermallee/moncchichi/bluetooth/G1DisplayService.kt†L24-L93】【F:subtitles/src/main/java/io/texne/g1/subtitles/model/Repository.kt†L33-L67】
- Jetpack Compose + Material 3 drive both hub and subtitles UIs, with Hilt providing dependency injection across modules.【F:hub/src/main/java/io/texne/g1/hub/G1HubApplication.kt†L1-L8】【F:subtitles/src/main/java/io/texne/g1/subtitles/ui/SubtitlesScreen.kt†L1-L142】
- Speech recognition, formatted display helpers, and HUD overlays live in their own modules to keep the BLE service lean and testable.【F:subtitles/src/main/java/io/texne/g1/subtitles/model/Recognizer.kt†L1-L104】【F:client/src/main/java/io/texne/g1/basis/client/G1ServiceCommon.kt†L64-L123】
- Additional engineering notes (such as nullability audits) are tracked under `docs/` to document Kotlin/AIDL guarantees.【F:docs/nullability-audit.md†L1-L15】

---

## License
This project is licensed under the Apache License 2.0. See [`LICENSE`](LICENSE) for details.
