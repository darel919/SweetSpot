# SweetSpot

SweetSpot is a lightweight native Android TV application for system-wide audio tuning. It targets resource-constrained TCL Android TV hardware and is designed to stay active with low CPU, memory, and thread use.

## Architecture

The Android TV owns the audio engine and all calibration decisions. The separate [sweetspot-web](https://github.com/darel919/sweetspot-web) dashboard is a secure HTTPS remote microphone and control surface.

```text
phone Safari  -- WebRTC DataChannels, direct when possible --  Android TV
     |                                                       |
 HTTPS signaling bootstrap only                         DSP + calibration
     |                                                       |
 Cloudflare Worker: static dashboard and short-lived SDP/ICE signaling
```

The direct peer has two ordered, reliable DataChannels:

- `control` carries commands, replies, TV state, calibration actions, diagnostics, cancellation, and capability negotiation.
- `capture` carries bounded binary Float32 mono PCM chunks with explicit backpressure, counts, and SHA-256 verification.

Cloudflare is not in the active calibration or EQ data path after the channels open. It hosts the secure dashboard and brokers short-lived signaling only. The local Android HTTP server remains available for authenticated diagnostics, local API testing, and ADB workflows; it is not the browser control path.

The native WebRTC peer factory is initialized lazily when a remote dashboard session needs it. The app creates no WebRTC media tracks, keeps capture data in bounded temporary files, and releases peer, factory, and native session resources when the session ends.

The dashboard's collapsed developer details can request a redacted TV transport snapshot covering peer state, ICE state, traffic, capture buffering, reconnects, and the latest transport error.

## TV-owned calibration

The TV issues job IDs, actions, revisions, and capture timing. It owns microphone metadata validation, PCM integrity checks, acoustic analysis, accepted evidence, the position ledger, confidence, correction optimization, candidate staging, validation, rollback, persistence, and recovery.

The browser owns microphone permission, capture-path metadata, microphone profile selection, streamed PCM upload, remote UI, and cancellation requests. It never declares a marker, position, correction, convergence result, or validation result accepted.

Center, left, and right form the minimum viable dataset. Optional position failures cannot erase that solution. Accepted evidence is persisted before the next action is published, so browser reloads and transport loss do not discard completed work. Raw PCM is temporary by default.

See [`docs/tv-owned-calibration-architecture.md`](docs/tv-owned-calibration-architecture.md) and [`AGENTS.md`](AGENTS.md) for the authoritative boundaries and invariants.

## Resource constraints

- Native Kotlin only. No Flutter, Compose, WebView, or large TV UI framework.
- Global DSP stays on Android audio session `0`.
- WebRTC has no audio or video tracks and no idle session allocation.
- Capture chunks and partial files are bounded; successful temporary PCM is removed according to the existing retention policy.
- Release builds keep R8/minification enabled. ABI and APK-size changes must be measured before release claims.

The current measured unsigned release APK is 5,119,505 bytes with only `armeabi-v7a` WebRTC native code, versus the 665,359-byte pre-WebRTC baseline. PSS, heap, CPU, and post-disconnect recovery still require measurements on the target TCL hardware.

## Build and install

Requirements: Android SDK, JDK 17, and the included Gradle wrapper.

```bash
./gradlew test
./gradlew assembleDebug
./gradlew assembleRelease
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Release output is written to `app/build/outputs/apk/release/`.

For device testing:

```bash
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.darelisme.sweetspot/.ui.MainActivity
```

Source tests and a successful build do not prove TV audio behavior, iPhone Safari microphone behavior, direct connectivity, or the cloud-independence acceptance test. Report those separately and only after running them on the target devices.

## Repository layout

```text
app/src/main/java/com/darelisme/sweetspot/
├── audio/                 engine and diagnostics
├── calibration/           TV-owned calibration authority
│   ├── analysis/          acoustic analysis and validation
│   ├── capture/           verified capture storage and readers
│   ├── dsp/               calibration DSP adapter
│   ├── model/             jobs, protocol models, ledger, and recovery
│   ├── persistence/       atomic job snapshots
│   ├── playback/          sweeps, session state, audio running, and AudioTrack ownership
│   └── transport/         capture and calibration event adapters
├── diagnostics/            bounded device and audio diagnostics orchestration
├── pairing/               rendezvous credentials and lifetime
├── transport/             protocol, signaling, and WebRTC implementation
├── server/                local authenticated HTTP API
├── service/               foreground-service lifecycle and command orchestration
└── ui/                    native overlay and TV controls
```

Calibration policy and analysis stay under `calibration/`. Generic peer and signaling code stays under `transport/`. The service coordinates ownership and lifecycle without implementing WebRTC or acoustic analysis.

The Android app and `sweetspot-web` are released as one protocol. When the wire contract changes, update the shared fixtures and the paired web repository's `shared/types/README.md` and `shared/types/TRANSPORT.md` before reporting the change complete.
