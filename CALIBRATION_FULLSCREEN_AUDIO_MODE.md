# SweetSpot Calibration Fullscreen + Exclusive Audio Mode

## Purpose

This document defines the required Android TV behavior while SweetSpot is performing room/speaker calibration from the phone.

This is an addendum to the room auto-correction implementation plan in:

- `darel919/sweetspot-web/ROOM_AUTO_CORRECTION_IMPLEMENTATION.md`

The requirement is simple:

> While a measurement sweep is active, SweetSpot must take over the TV screen and audio path so another app cannot accidentally play audio into the measurement. When calibration is finished, cancelled, fails, or times out, SweetSpot must automatically leave the fullscreen UI and return the user to whatever app they were using before calibration.

Do not require the user to manually exit SweetSpot after calibration.

---

# Important Technical Detail

Fullscreen UI by itself does **not** make audio exclusive.

SweetSpot must combine:

```text
fullscreen foreground Activity
+
Android transient-exclusive audio focus
+
measurement-state DSP bypass
+
SweetSpot-owned AudioTrack sweep
```

The goal is to make the calibration sweep the only intentional media output during measurement.

Android audio focus is cooperative at the framework/app level, so no implementation can literally guarantee that a broken/vendor app will never ignore focus. SweetSpot should nevertheless request the strongest appropriate focus mode and **abort calibration if that focus request is not granted**.

Do not kill or force-stop other apps.

---

# Required User Flow

Normal situation:

```text
User watching Netflix / YouTube / TV app / other source
              │
              ▼
Phone presses "Start calibration"
              │
              ▼
TV enters SweetSpot CalibrationActivity
              │
              ├─ fullscreen / immersive
              ├─ screen kept awake
              ├─ transient-exclusive audio focus acquired
              ├─ current SweetSpot DSP state saved
              └─ measurement bypass prepared
              │
              ▼
Phone records
TV plays calibration sweep
              │
              ▼
measurement completes
              │
              ├─ restore previous DSP state
              ├─ release audio focus
              ├─ stop/release AudioTrack
              └─ close CalibrationActivity automatically
              │
              ▼
Previous app becomes visible again
```

The same cleanup/exit behavior is required on:

- successful calibration
- user cancel
- phone disconnect
- mailbox/relay failure
- sweep playback exception
- audio focus loss
- measurement timeout
- malformed measurement command
- service shutdown

---

# Do Not Use the Existing Overlay as Calibration UI

The current TV overlay is useful for pairing/status, but calibration should use a real foreground Activity.

Create something similar to:

```text
app/src/main/java/com/darelisme/sweetspot/CalibrationActivity.kt
```

This Activity should be intentionally tiny.

Responsibilities:

- take foreground visually
- enter immersive fullscreen
- keep display awake
- show calibration status
- optionally show progress / instructions
- expose a Cancel action if TV remote interaction is desired
- finish automatically when the service says calibration is over

It must **not** own:

- AudioEngine
- calibration persistence
- mailbox transport
- sweep DSP math
- correction calculation

`SweetSpotService` remains the long-lived owner of audio and measurement state.

---

# Activity Manifest / Task Behavior

Configure calibration UI so it does not become a normal permanent TV task.

Desired behavior:

- fullscreen TV Activity
- excluded from Recents if practical on the target TV
- no normal launcher-style navigation requirement
- opened only for an active measurement session
- finishes automatically

Do not accidentally clear or finish the app the user was previously watching.

The Activity should be launched on top of the currently visible app. When it finishes, Android should reveal the previously foreground task naturally.

Test this explicitly with at least:

- YouTube
- built-in TV/live-TV app if available
- one third-party streaming app

Do not rely only on launcher-to-SweetSpot testing.

---

# Fullscreen / Immersive Mode

When calibration begins, hide system UI as far as supported on the target Android TV.

Use modern WindowInsets APIs where supported, with a compatibility fallback if necessary for the TV firmware.

Conceptually:

```kotlin
WindowCompat.setDecorFitsSystemWindows(window, false)
WindowInsetsControllerCompat(window, window.decorView).apply {
    hide(WindowInsetsCompat.Type.systemBars())
    systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
```

Also keep the screen awake for the active calibration session:

```text
FLAG_KEEP_SCREEN_ON
```

Do not keep this flag active after the calibration Activity finishes.

The calibration page should contain no distracting controls.

Suggested TV UI:

```text
SweetSpot Calibration

Measuring your speakers…

Position 2 of 5

Do not change volume or switch apps.

[ Cancel ]
```

During the actual sweep:

```text
Playing measurement sweep…
```

---

# Exclusive Audio Focus

Add audio focus ownership to `MeasurementController`.

Do not put it in `CalibrationActivity` because the Activity is only UI. Measurement state must remain service-owned.

Use `AudioManager` and `AudioFocusRequest` on API 28+.

Request:

```text
AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
```

Suggested attributes:

```kotlin
AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()
```

Conceptually:

```kotlin
val request = AudioFocusRequest.Builder(
    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
)
    .setAudioAttributes(attributes)
    .setOnAudioFocusChangeListener(focusListener)
    .build()
```

Do **not** begin sweep playback until:

```text
requestAudioFocus(...) == AUDIOFOCUS_REQUEST_GRANTED
```

If focus is denied:

```text
measurement.error
code = "audio_focus_denied"
```

Then:

- restore DSP state
- close CalibrationActivity
- return user to previous app
- tell browser calibration cannot begin

Do not silently continue with a contaminated measurement.

---

# Audio Focus Loss During Measurement

Treat focus loss as a measurement failure.

For:

```text
AUDIOFOCUS_LOSS
AUDIOFOCUS_LOSS_TRANSIENT
```

immediately:

```text
stop sweep
invalidate current measurement
restore previous DSP state
release resources
measurement.error("audio_focus_lost")
close fullscreen calibration UI
```

Do not attempt to resume from the middle of a sweep.

A fresh sweep is cheaper and acoustically safer.

For duck events, the expectation with transient-exclusive focus is that competing media should already stop. If the platform still reports a duck-related focus change during a measurement, treat it conservatively as invalid measurement state rather than altering sweep volume.

---

# AudioTrack Must Be Owned by the Measurement Session

The sweep must use SweetSpot's own `AudioTrack` instance.

Recommended attributes should match the audio-focus intent:

```text
USAGE_MEDIA
CONTENT_TYPE_MUSIC
```

The sweep AudioTrack must be:

- created only during measurement
- stopped on abort/error
- flushed if needed
- released on completion

Do not leave a long-lived calibration AudioTrack allocated while SweetSpot is idle.

---

# Calibration Entry Sequence

On `measurement.prepare` from the phone, execute in this order:

```text
1. Reject if another measurement session is active.
2. Create MeasurementSession with unique sessionId.
3. Snapshot current DSP/audio state.
4. Launch CalibrationActivity fullscreen.
5. Request transient-exclusive audio focus.
6. If focus denied:
      restore + close UI + measurement.error.
7. Prepare transient DSP bypass.
8. Prepare sweep parameters / AudioTrack resources.
9. Send measurement.ready.
```

Do not send `measurement.ready` until the TV is actually ready to guarantee the intended measurement environment.

That means at minimum:

```text
fullscreen Activity active
exclusive audio focus granted
DSP measurement state ready
```

---

# Sweep Entry Sequence

When browser sends `measurement.playSweep`:

```text
1. Verify matching sessionId.
2. Verify CalibrationActivity/session is still active.
3. Verify audio focus still owned.
4. Verify DSP bypass still active.
5. Verify AudioTrack ready.
6. Send measurement.started.
7. Play full deterministic sweep.
8. Stop/flush AudioTrack as appropriate.
9. Send measurement.finished.
```

A single position measurement ending does **not necessarily mean calibration mode should exit**.

For multi-position calibration, keep fullscreen + audio focus across the entire calibration wizard if practical:

```text
position 1 sweep
move phone
position 2 sweep
move phone
position 3 sweep
...
```

This prevents the previously used app from repeatedly resuming between measurements.

Therefore distinguish:

```text
measurement sweep finished
```

from:

```text
calibration session finished
```

---

# Add Calibration Session Commands

The existing protocol currently has sweep-level messages such as:

```text
measurement.prepare
measurement.playSweep
measurement.finished
measurement.abort
```

For a clean multi-position UX, add explicit calibration-session completion semantics.

Recommended additions:

```text
calibrationSession.begin
calibrationSession.end
calibrationSession.abort
```

or equivalent names consistent with the existing protocol.

If minimizing protocol churn, `measurement.prepare` may begin the persistent calibration session and a new message such as:

```text
measurement.release
```

may end it.

The important invariant is:

> Do not restore the previous app/audio focus after every individual sweep when the phone still needs four more positions.

Suggested lifecycle:

```text
calibration session begin
    ↓
fullscreen + exclusive focus
    ↓
position 1
position 2
position 3
position 4
position 5
validation sweep if needed
    ↓
calibration curve applied
    ↓
calibration session end
    ↓
restore normal state + exit fullscreen
```

---

# Automatic Exit Behavior

On successful calibration completion, the TV should automatically:

```text
1. stop/release measurement AudioTrack
2. end transient measurement bypass
3. apply final calibration curve if not already committed
4. restore normal user EQ layer
5. apply/recalculate headroom
6. abandon transient-exclusive audio focus
7. tell CalibrationActivity to finish
8. clear calibration-session timeout/state
```

The Activity should use normal Android task behavior so finishing it reveals the previously foreground app.

Do not launch the launcher/Home screen manually unless testing proves the target TV requires a workaround.

Do not try to relaunch the previous app by package name in V1.

Why:

- Android already maintains the prior task
- relaunching may reset playback/navigation state
- package-specific launching is fragile
- finishing SweetSpot's temporary foreground Activity should be enough

If target-TV testing shows `finish()` does not reliably reveal the previous application, investigate task flags before implementing package-specific restoration.

---

# Activity ↔ Service Communication

The service needs a simple way to tell the Activity when to appear/finish and update status.

Use a lightweight native mechanism, for example:

- explicit Intents/actions
- local service binding if already justified
- a process-local listener/state holder

Do not add a large event bus or UI framework.

Possible actions:

```text
ACTION_CALIBRATION_UI_OPEN
ACTION_CALIBRATION_UI_UPDATE
ACTION_CALIBRATION_UI_CLOSE
```

or direct Activity launch + finish signal.

The state authority remains `MeasurementController` / `SweetSpotService`, not the Activity.

If the Activity is unexpectedly destroyed and the measurement is still active, the service should treat that as an unsafe state and abort/restore rather than continuing invisibly.

---

# Timeout / Watchdog

While calibration mode is active, maintain a TV-side watchdog.

If no valid measurement/calibration command is received for a configured period, automatically abort.

Recommended starting value:

```text
60 seconds of inactivity
```

This is longer than one sweep and allows the user to move the phone between positions.

Reset the watchdog on valid session activity.

On timeout:

```text
stop AudioTrack
restore DSP state
abandon audio focus
close CalibrationActivity
clear session
```

This prevents SweetSpot from trapping the TV fullscreen if the iPhone tab dies or Wi-Fi disappears.

---

# Phone UX Requirements

The Nuxt calibration wizard must understand that starting calibration temporarily takes over the TV.

Before beginning:

```text
SweetSpot will temporarily take over your TV while it measures your speakers.
Your previous app will return automatically when calibration is finished.

[ Start calibration ]
```

After the TV reports it is ready:

```text
TV ready
Audio isolated
Microphone ready
```

Do not tell the user to manually close SweetSpot on the TV.

On completion:

```text
Calibration complete.
Returning your TV to normal playback…
```

Then the TV Activity exits automatically.

---

# Measurement Integrity Rules

During fullscreen calibration mode, instruct user not to:

- change TV volume
- press Home
- switch TV input
- start playback manually
- cover the phone mic
- rotate phone between measurement points

Where practical, record volume-related state or detect obvious level changes between sweeps.

Do not attempt to disable every TV remote key globally. A user must still be able to escape a broken calibration session.

Back/Cancel should trigger a clean measurement abort and state restoration.

---

# Error Codes to Add

At minimum support structured measurement errors for:

```text
audio_focus_denied
audio_focus_lost
calibration_ui_failed
calibration_ui_closed
measurement_timeout
sweep_playback_failed
invalid_session
already_measuring
```

The browser should show a useful message and must not calculate/apply a correction from an invalid sweep.

---

# Acceptance Tests

## Previous-app restoration

For each test app:

```text
YouTube
built-in live TV / TV app
one third-party streaming app
```

Test:

```text
1. Start playback.
2. Begin SweetSpot calibration from iPhone.
3. Confirm SweetSpot CalibrationActivity becomes fullscreen.
4. Confirm previous app audio stops during calibration.
5. Run one or more sweeps.
6. End calibration.
7. Confirm SweetSpot Activity disappears automatically.
8. Confirm previous app/task is visible again.
```

The previous application may remain paused depending on how that app responds to audio focus. SweetSpot must not force it to resume playback; returning the user to the same app/task is the requirement.

## Abort restoration

Repeat while:

- pressing Cancel
- closing the phone tab
- disconnecting Wi-Fi
- simulating mailbox failure
- forcing sweep exception

Every path must exit fullscreen and restore normal SweetSpot DSP state.

## Audio contamination

While SweetSpot owns transient-exclusive audio focus:

- start calibration from a currently playing media app
- verify previous media audio is not mixed into the sweep
- attempt common TV/system sound interactions
- verify no competing audio appears in captured sweep where the platform honors focus

If exclusive focus cannot be established, SweetSpot must refuse to measure.

---

# Implementation Order

- [ ] Add `CalibrationActivity`.
- [ ] Implement immersive fullscreen + keep-screen-on.
- [ ] Add measurement session state to `MeasurementController`.
- [ ] Add `AudioFocusRequest` using `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`.
- [ ] Abort if exclusive focus is denied or lost.
- [ ] Connect `measurement.prepare` to fullscreen/activity + focus acquisition.
- [ ] Keep fullscreen/focus across multiple measurement positions.
- [ ] Add explicit session-end semantics to the protocol.
- [ ] Implement automatic UI finish on successful calibration.
- [ ] Implement identical cleanup on abort/error/timeout.
- [ ] Verify Android returns naturally to the previous task.
- [ ] Test against real media playback on target TCL TV.

---

# Definition of Done

This feature is complete only when the following user experience is reliable:

```text
User is watching something
        ↓
starts calibration from iPhone
        ↓
SweetSpot temporarily takes over TV fullscreen
        ↓
other compliant media audio is stopped by exclusive audio focus
        ↓
all calibration sweeps run without previous-app audio contamination
        ↓
calibration finishes or aborts
        ↓
SweetSpot automatically exits
        ↓
user is returned to the app/task they were using before calibration
```

No manual TV cleanup step should be required.
