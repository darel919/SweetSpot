# SweetSpot

SweetSpot is a lightweight native Android TV application for tuning the TV's global audio output.

The project is currently being developed for a resource-constrained TCL Android TV and is designed to run continuously with minimal CPU and memory overhead.

## Status

SweetSpot is a working tool for tuning a TV's sound, not a proof-of-concept.

The core capability is already confirmed on the target hardware:

- Android global audio session `0` is accessible
- SweetSpot obtains control of the TV's built-in equalizer
- changing EQ values produces an audible system-wide effect during normal TV playback

## Architecture

SweetSpot is intentionally designed without Flutter or Compose.

Android is the DSP/device backend. The browser frontend lives in the separate [sweetspot-web](https://github.com/darel919/sweetspot-web) project (hosted at https://sweetspot.darelisme.my.id), which owns all dashboard UI, EQ controls, profiles UI, diagnostics, and the calibration wizard.

```text
Android TV SweetSpot
    |
    v
SweetSpotService
    |
    +-- AudioEngine
    |     |
    |     +-- global audio session 0 DSP (DynamicsProcessing)
    |     +-- 64-band calibration state
    |     +-- 24-band user EQ
    |     +-- profiles
    |
    +-- local HTTP API server (API/debugging interface only)
    +-- native TV status/pairing overlay
```

Normal user control comes through the hosted dashboard at https://sweetspot.darelisme.my.id. The TV's local HTTP server exposes a JSON API for device control and debugging; it does not host any browser UI. A relay-based device agent is planned so the hosted dashboard can reach the TV without direct LAN access.

## Design Constraints

SweetSpot should remain:

- native Kotlin
- lightweight
- low-RAM
- low-CPU
- usable on 32-bit ARM Android TV hardware
- independent of a large TV-side UI

Avoid adding large frameworks unless necessary. Do not bundle or serve a web dashboard from the APK.

## Build

Requirements:

- macOS development environment
- Android SDK
- JDK 17
- Gradle wrapper included with the project

Build the debug APK:

```bash
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install on TV

Connect the TV through ADB:

```bash
adb connect <TV-IP>:5555
```

Check connection:

```bash
adb devices
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch:

```bash
adb shell am start \
  -n com.darelisme.sweetspot/.MainActivity
```

## Current Milestone

Move ownership of the global equalizer from `MainActivity` into a foreground `SweetSpotService`.

The service should:

- create and own the global EQ
- keep it active while other apps are in the foreground
- expose temporary preset switching for testing
- release the effect cleanly when stopped
- remain lightweight

See:

```text
SWEETSPOT_IMPLEMENTATION.md
```

for the implementation brief.

See:

```text
IDEA.md
```

for the product and architecture direction.
