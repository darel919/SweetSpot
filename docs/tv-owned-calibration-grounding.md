# TV-owned calibration grounding

## Current ownership

The TV owns sweep playback, PCM ingest, acoustic acceptance, the position ledger,
convergence, correction generation, validation, candidate transactions, and
recovery. The browser is a remote microphone and renderer: it opens the mic when
the TV requests a capture, streams bounded binary chunks over the direct capture
DataChannel, and renders the next TV job state.

The old split created two sources of truth. The browser could retain accepted
evidence that the TV could not resume, while the TV could retain a pending DSP
candidate that the browser could not reconstruct after reload. The persisted
`CalibrationJob` and TV DSP transaction now close both gaps.

## Proven failure path

The former browser planner returned `bounded` when it reached the optional
position cap. Browser staging required `sufficient`, so a complete three- or
four-position dataset could end without a candidate after a late optional
failure. The TV planner now persists a usable best solution after the mandatory
center/left/right set and stages that solution when optional work is exhausted.

## Existing parts to keep

- `MeasurementSweepGenerator` is the playback waveform source. Analysis must derive its reference from the same generator.
- `DynamicsProcessingEq` and `ProfileStore` already provide persisted candidate intent, live application, readback verification, cut-only headroom behavior, acceptance, rollback, and startup recovery.
- The browser position ledger already proves useful append-only and complete-position-only semantics. Port the behavior, not the browser authority.
- The browser analyzer has deterministic marker, drift, deconvolution, impulse, response, microphone-profile, confidence, optimizer, and validation tests.

## Transport boundary

Cloudflare provides HTTPS hosting and a short-lived SDP/ICE rendezvous only.
After the ordered, reliable `control` and `capture` DataChannels open, the
browser sends envelopes and bounded `SSCP` Float32 mono PCM chunks directly to
the TV. The TV stores the chunks in a partial file, verifies ordering, counts,
metadata, and SHA-256, then passes only the finalized capture to the analyzer.

## Design constraints

- The TV issues every job ID, capture ID, action, candidate ID, and state revision.
- A job persists an action before publishing it and persists a result before publishing the next action.
- A capture upload is idempotent by `captureId` and SHA-256. A different hash for the same ID is a conflict.
- Only complete accepted left and right physical positions enter an aggregate or solution.
- Minimum viable state and the best solution are monotonic after center, left, and right pass.
- Optional failure changes diagnostics and next action. It does not clear usable state.
- Browser presence does not own job lifetime.
- Startup reconciles the persisted job with the persisted DSP transaction before publishing state.
- Control JSON and binary PCM have separate validation and size limits. The
  control channel remains independent while the capture channel is backpressured.
- Calibration uses one bounded worker. Terminal paths close playback, streams, temporary files, and the worker.

## Migration order

1. Define and test the pure job model, ledger, confidence, optimizer, and monotonic planner.
2. Add job persistence and restart tests.
3. Port the analyzer in numerical slices against generated PCM vectors. Keep
   the browser analyzer only as a temporary parity/debug oracle.
4. Add bounded binary PCM storage, stream receipts, and direct-transport backpressure.
5. Connect the engine to playback and the existing DSP transaction API.
6. Add TV-owned matched-center validation and deterministic fallback modes.
7. Add the job protocol and state snapshot.
8. Replace browser policy with remote microphone capture and job rendering over
   WebRTC DataChannels. Use Cloudflare only for pairing rendezvous and SDP/ICE.
9. Remove old production authority after parity and end-to-end tests pass. The
   Auto Room Calibration route already renders only the TV-owned remote-mic
   component; legacy browser analysis remains diagnostic until parity fixtures
   are complete.

## Throughput checkpoint

The first usable checkpoint is a persisted TV job that accepts center, left, and right evidence, generates `bestSolution`, reloads without loss, and retains that solution after an optional rejection.
