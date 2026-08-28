# SweetSpot

SweetSpot is a lightweight native Android TV application for tuning the TV's global audio output.

The project is currently being developed for a resource-constrained TCL Android TV and is designed to run continuously with minimal CPU and memory overhead. Calibration is a short-lived, TV-owned job: it may temporarily allocate PCM, FFT, and optimizer buffers, then releases them and returns to the lean always-on runtime.

## Status

The current build targets Android global audio session `0` and the TV's
system-wide audio effect chain. Hardware validation is separate from the source
tests and build checks listed below.

## Architecture

SweetSpot is intentionally designed without Flutter or Compose.

Android is the DSP/device backend. The browser frontend lives in the separate [sweetspot-web](https://github.com/darel919/sweetspot-web) project (hosted at https://sweetspot.darelisme.my.id). It owns dashboard presentation, EQ controls, profile presentation, diagnostics, and the remote microphone surface. The Android TV owns the calibration job, acoustic analysis, position ledger, confidence, correction, candidate transaction, validation, persistence, and recovery.

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
    +-- native TV controls / status / pairing overlay
    +-- TV-owned CalibrationEngine
          +-- binary PCM capture store
          +-- marker, clock, ESS, impulse, response analysis
          +-- physical-position ledger and spatial confidence
          +-- best-so-far optimizer and candidate validation
```

Normal user control comes through the hosted dashboard at https://sweetspot.darelisme.my.id or the small native TV overlay. The TV's local HTTP server exposes a JSON API for device control and debugging; it does not host any browser UI. Pairing and control use the current WebSocket relay contract.

## Calibration ownership and behavior

The TV issues every calibration job ID, capture action, position, and state
revision. The browser opens the phone microphone only when requested, sends a
binary Float32 mono PCM frame with actual capture metadata and a SHA-256 hash,
and renders the returned TV state. It never accepts a marker, position,
correction, convergence result, or validation result.

Microphone profiles are supplied by the capture device through the web client.
Kotlin does not contain a growing profile registry. The complete versioned
profile is sent with each capture, validated on the TV, and is correction
eligible only when its capture path is marked `validated`.

Center setup is qualified before the walkaround. Center, left, and right are
the minimum viable dataset; the TV persists a best solution immediately after
they pass. Forward and backward are optional refinements. A failed optional
measurement is excluded locally and cannot erase the usable solution already
earned. Spatial disagreement reduces correction strength per band, and a
worse validation rolls back and tries gentler/restricted solutions from the
same saved room data instead of forcing another walkaround.

Raw PCM is temporary by default and is deleted after terminal success. Compact
accepted evidence, analyzer/sweep revisions, confidence, validation history,
and final correction state are retained so a browser reload or TV process
restart can resume safely.

See [`docs/tv-owned-calibration-architecture.md`](docs/tv-owned-calibration-architecture.md)
for the state model and hard invariants.

## Design Constraints

SweetSpot should remain:

- native Kotlin
- lightweight
- low-RAM
- low-CPU
- usable on 32-bit ARM Android TV hardware
- independent of a large TV-side UI; the native overlay remains deliberately small and framework-free

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

## Project guidance

[`AGENTS.md`](AGENTS.md) contains the current ownership, resource, layout, and
verification rules. [`IDEA.md`](IDEA.md) points to that file instead of
duplicating architecture rules.
