# TV-owned calibration architecture

## Problem

The TV owns sweep playback, DSP safety, calibration evidence, and policy. The
old browser-owned split let a browser reload lose measurement truth and let a
late optional failure suppress a valid correction. The TV owns one durable
calibration job without moving acoustic policy into `SweetSpotService` or
`DynamicsProcessingEq`.

## Usage

`SweetSpotService` parses protocol input and calls one engine.

```kotlin
val calibrationEngine = CalibrationEngine(
    jobStore = calibrationJobStore,
    captureStore = calibrationCaptureStore,
    analyzer = calibrationAnalyzer,
    sweep = calibrationSweep,
    playback = calibrationPlayback,
    dsp = calibrationDsp,
)

calibrationEngine.startNewJob()
calibrationEngine.captureReady(jobId, captureId)
calibrationEngine.submitCaptureStream(metadataJson, pcmInput, pcmBytes)
calibrationEngine.currentJob()
calibrationEngine.close()
```

The browser renders the TV action, records PCM, uploads it, and waits for the next TV view.

```ts
const calibration = useCalibrationRemoteMic(connection)
calibration.startNewJob()
// The composable records only the action emitted by the TV.
calibration.refreshJob()
```

## Product behavior references

The workflow follows useful behavior documented by mature room-correction
products: qualify the primary listening position first, treat extra positions
as spatial context, and keep the correction range conservative when
measurements disagree. Dirac documents the sweet spot as the first and most
important position and notes that wider measurement spacing should produce less
aggressive correction ([measurement positions](https://helpdesk.dirac.com/en/dirac-room-correction/In-what-order-should-I-measure-the-positions-cbe)).
Anthem recommends five positions while explicitly noting that more positions
are not always better and that additional measurement sets are optional
([ARC measurement configuration](https://arc.anthemav.com/arc-genesis/advanced-settings/configure-measurements.php)).
Sonos exposes tuning as a short guided operation, asks users to minimize noise
and move smoothly, and lets the resulting tuning be toggled on or off
([Trueplay tuning](https://support.sonos.com/en-gb/article/tune-your-sonos-speakers-with-trueplay)).
SweetSpot applies those principles to a TV-owned job: center/left/right earn a
usable minimum, optional positions refine confidence, and failed optional or
validation captures are repaired or rolled back locally.

## Chosen shape

One serialized `CalibrationEngine` owns a reducer-backed `CalibrationJob`. Protocol DTOs, upload headers, JSON, Android playback, and DSP classes stay behind adapters. Analyzer and correction code are pure Kotlin modules.

```kotlin
sealed interface CalibrationUsability {
    data object NotYetUsable : CalibrationUsability
    data class Usable(
        val best: CalibrationSolution,
        val grade: UsabilityGrade,
    ) : CalibrationUsability
}

data class CalibrationJob(
    val id: CalibrationJobId,
    val revision: Long,
    val analyzerRevision: AnalyzerRevision,
    val sweepRevision: SweepRevision,
    val phase: CalibrationPhase,
    val ledger: PositionLedger,
    val usability: CalibrationUsability,
    val confidence: CalibrationConfidence?,
    val nextAction: CalibrationAction?,
    val candidate: CalibrationCandidateState?,
    val validationHistory: List<ValidationRecord>,
    val pendingEffect: PendingCalibrationEffect?,
    val lastError: CalibrationJobError?,
)
```

`CalibrationUsability.Usable` replaces a mutable minimum-viable boolean. Normal transitions have no path back to `NotYetUsable`. An incompatible revision or corrupt accepted record enters a separate recovery failure state.

`PositionLedger` stores historical attempts and complete accepted positions separately. Only a `CompletePosition` with accepted left and right evidence can enter aggregation. A repair can add the missing channel without replacing its accepted sibling.

The engine persists the next action before publishing it. It persists accepted evidence and a new best solution before publishing the following action. It persists a DSP effect intent before staging, accepting, or rolling back a candidate.

## Persistence

The implementation uses one atomic versioned job snapshot plus bounded immutable capture files. The snapshot contains stable effect IDs, completed command receipts, and the microphone profile identity/revision attached to every accepted channel. The engine writes a temporary snapshot, syncs it, and atomically renames it.

This keeps restart behavior explicit without a file-per-event log on low-end flash. The reducer remains independent of storage, so a bounded milestone journal can replace or supplement snapshots if crash tests find an unrepairable gap.

Startup performs these steps before it publishes state:

1. Load and validate the job revision.
2. Remove orphan partial uploads.
3. Inspect the persisted DSP transaction and live readback.
4. Reconcile any pending job effect with the DSP state.
5. Persist the reconciled job.
6. Publish the current action.

## Capture boundary

The TV issues the job ID, capture ID, and action. The browser sends a `capture.begin`
header, bounded `capture.chunk` frames, and a `capture.end` header over the direct
capture DataChannel. `CalibrationCaptureStreamReceiver` validates session and
capture identity, writes Float32 mono little-endian PCM to a partial file,
calculates SHA-256, verifies byte and sample counts, and atomically renames the
file. `submitCaptureStream()` then reopens that verified file for analysis.

The same capture ID and hash returns the prior receipt and analysis result. A different hash for the same capture ID returns a conflict. The analyzer only receives a verified stored capture.

Control JSON retains its existing size limit. PCM uses a separate binary transport
adapter. Cloudflare signaling never carries either application envelopes or PCM;
the engine receives only a verified finalized capture from the TV-side stream
receiver.

## Analyzer and correction boundaries

`CalibrationAnalyzer.analyze()` receives verified PCM, the TV sweep definition,
and the TV-owned microphone profile. `SpatialCorrection.optimize()` receives
only complete accepted positions and returns either a confidence-scored
solution or explicit insufficiency.

The analyzer derives its reference from `MeasurementSweepGenerator`. It ports the browser thresholds and numerical behavior before any threshold changes.

`SpatialCorrection` computes robust aggregation, per-band confidence, limiter behavior, and the solution score. The deterministic comparison tuple is confidence grade, usable-band count, robust score, source-position count, and solution ID. A proposed solution replaces the current best only when this tuple improves.

## Audio boundary

`TvCalibrationPlayback` is the playback and temporary-audio-state adapter.
`DynamicsProcessingEq` remains the candidate transaction and readback adapter.

```kotlin
interface CalibrationAudioPort {
    fun inspect(): CalibrationAudioSnapshot
    fun execute(command: CalibrationAudioCommand): CalibrationAudioResult
}
```

The command sequence is private to `CalibrationEngine`. Browser and service callers cannot coordinate DSP stages. Cut-only candidates retain the current headroom exception. Positive-gain candidates still require verified headroom.

## Validation

Validation compares a candidate center capture with the original accepted center baseline. An inconclusive capture retries validation only. A worse candidate persists rollback intent, rolls back, verifies the previous DSP state, and optimizes the same room data in `GENTLE` mode. A second worse result tries `RESTRICTED_BAND`. Failure of all modes restores the pre-job calibration.

Validation transitions never remove accepted room evidence.

## Protocol and browser

The TV publishes a compact `CalibrationJobView` in the state snapshot. Curves and
full debug details use a separate detail response if the compact view approaches
the control-message limit.

The browser can start, get, resume, cancel a capture, finish with the best solution, cancel optional refinement, or explicitly discard the job. It cannot submit marker decisions, convergence, a correction, validation classification, or candidate finalization.

`useCalibrationRemoteMic()` owns microphone permission, recording, metadata, binary upload, cancellation requests, and rendering state. Browser reload reconstructs all calibration UI from the TV job.

## Module map

```text
com.darelisme.sweetspot.calibration/
  CalibrationEngine.kt
  CalibrationModel.kt
  CalibrationStateMachine.kt
  CalibrationJobStore.kt
  CalibrationJobJson.kt
  CalibrationCaptureStore.kt
  transport/CalibrationCaptureStreamReceiver.kt
  transport/CalibrationCaptureStreamWire.kt
  CalibrationAnalyzer.kt
  CalibrationMicrophoneProfilePayload.kt
  PositionLedger.kt
  SpatialCorrection.kt
  CalibrationAudioPort.kt
  TvCalibrationPlayback.kt
  TvCalibrationDsp.kt
```

Marker, FFT, drift, impulse, and response helpers remain internal behind
`CalibrationAnalyzer`. Confidence, optimizer, and limiter helpers remain
internal behind `SpatialCorrection`. `CalibrationCaptureStore` keeps bounded
temporary PCM and compact metadata; `CalibrationMicrophoneProfilePayload` is
the versioned profile supplied with each remote capture, not a TV-side profile
registry.

## Hard invariants

1. `CalibrationUsability.Usable` cannot become `NotYetUsable` after an optional failure.
2. An optional rejection cannot make the best solution null.
3. Every solution source is a complete accepted position.
4. Partial positions cannot enter a stereo aggregate.
5. Protocol input cannot create accepted evidence or validation outcomes.
6. Browser presence does not own job lifetime.
7. Accepted evidence persists before the next action publishes.
8. A worse validation persists rollback intent before any other candidate action.
9. Optional failure changes attempt history and planning only.
10. Validation failure changes validation attempts and correction mode only.
11. Signaling loss after direct setup does not terminate an active peer.
12. Pairing expiry does not terminate an authenticated direct peer.
13. Control traffic remains independent from capture backpressure.
14. Incomplete or unverified PCM never becomes calibration evidence.
15. A stale peer generation or capture ID cannot mutate the current job.

## Synthesis decision

The final design keeps a serialized reducer and engine because they centralize
monotonic evidence and restart behavior. It uses explicit playback and DSP
adapters, a deterministic solution comparator, and a compact state projection.
Atomic snapshots keep persistence small on low-end flash without adding a
file-per-event journal.

## Tradeoffs accepted

- We accept one calibration executor in exchange for deterministic ordering and bounded memory.
- We accept compact derived evidence on TV in exchange for resume and re-optimization without another walkaround.
- We accept a dedicated binary route in exchange for keeping control messages small.
- We accept a short-lived signaling WebSocket in exchange for direct runtime
  traffic and no central PCM relay.
- We accept snapshot schema migration code in exchange for avoiding Room or another database dependency.

## Implementation status

The pure reducer, analyzer, capture store, binary upload route, Android playback/DSP adapters, native overlay, remote-microphone dashboard, and deterministic regression coverage are implemented. Keep the browser analyzer only for explicit diagnostics/parity work; production Auto Room Calibration uses the TV engine and the `android-response-v1` analyzer revision.
