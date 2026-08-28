# TV-owned calibration implementation plan

## Definition of done

The generated scenario accepts center, left, right, and forward, rejects backward twice, preserves the four-position best solution, stages it, validates it at center, and survives reload after each milestone. A worse validation rolls back and asks only for another center capture with a gentler solution. Android tests and `assembleDebug` pass. Web tests, typecheck, and static generation pass.

## Global constraints

- Android owns every calibration decision and persistent job field.
- Browser code records and uploads PCM but cannot accept evidence, optimize, validate, or finalize a candidate.
- Minimum viability and the best solution remain monotonic after three complete required positions.
- Only complete accepted physical positions enter solutions.
- PCM is binary Float32 mono little-endian with SHA-256 integrity.
- Runtime browser/TV communication uses direct ordered WebRTC DataChannels;
  Cloudflare is limited to HTTPS hosting and short-lived SDP/ICE signaling.
- PCM uses bounded stream frames with capture-channel backpressure; it is never
  sent as one giant message or through a production relay.
- The engine uses one bounded worker and releases it on every terminal path.
- `DynamicsProcessingEq` retains candidate transaction safety and verified readback.
- Analyzer and sweep revisions are persisted and incompatible jobs do not silently resume.
- The iPhone 17 Pro profile remains correction eligible.
- Preserve unrelated changes in both repositories.

## Task 1: Pure job model and policy

Create the calibration package types, complete-position ledger, reducer, planner, spatial confidence, correction limiter, solution comparator, validation fallback policy, and deterministic tests for hard invariants 1 through 10. The first checkpoint must generate a three-position `BOUNDED_USABLE` solution and retain it after optional rejection.

## Task 2: Android analyzer parity

Port the shared signal reference, FFT, marker detector, pair scoring, clock drift, resampler, ESS deconvolution, direct arrival, response extraction, microphone profile correction, and quality classification. Port generated browser fixtures and declare numerical tolerances. Keep `android-response-v1` authoritative.

## Task 3: Job and capture persistence

Add atomic versioned job snapshots, restart validation, bounded capture storage, streaming hash verification, upload receipts, duplicate replay, conflict rejection, raw retention, and cleanup tests. Persist accepted derived evidence and rejected attempt summaries.

## Task 4: Engine, playback, DSP, and protocol integration

Add the serialized `CalibrationEngine`, adapt `MeasurementController` to engine-issued actions, add the narrow DSP transaction port, reconcile job and DSP state at startup, add TV job protocol messages and snapshots, and add the bounded direct capture stream transport. Remove disconnect-to-cancel ownership.

## Task 5: TV-owned validation and recovery

Stage the best solution, validate against the original center baseline, retry inconclusive validation locally, roll back worse candidates, generate gentle and restricted candidates from stored evidence, restore the prior calibration when all candidates fail, and persist validation history.

## Task 6: Browser remote microphone

Update the shared protocol. Replace production browser planning, analysis, optimization, staging, validation, recovery, and checkpoint authority with `useCalibrationRemoteMic()`. Render only the TV job and next action. Upload PCM with actual metadata and SHA-256 over direct WebRTC DataChannels; keep Cloudflare signaling-only. Keep old analyzer code only in parity tooling until Android fixtures pass.

## Task 7: Whole-product verification and subtraction

Run protocol parity, transport-fixture parity, Android tests, Android build, web tests, typecheck, and generation. Exercise deterministic reload, optional-failure, worse-validation, rollback, cleanup, and incompatible-revision scenarios. Remove old production authority imports, relay runtime, and stale all-or-nothing UI copy. Audit final diffs in both repositories.
