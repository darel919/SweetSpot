# SweetSpot Android — Embedded Web UI Removal Plan

## Goal

Convert the Android/Kotlin side of SweetSpot into a **device/DSP + API server only** application.

The browser dashboard has moved to the separate Nuxt project:

- Web dashboard: `https://github.com/darel919/sweetspot-web`
- Production target: `https://sweetspot.darelisme.my.id`

The Android TV app must no longer contain or serve the old Vue dashboard.

This task is intentionally a cleanup/refactor only. **Do not redesign the DSP, profiles, calibration, foreground service, or API behavior while doing this work.**

---

# 1. Desired Final Responsibility Split

## Android TV repository — `darel919/SweetSpot`

Keep:

- `SweetSpotService`
- `AudioEngine`
- `DynamicsProcessingEq`
- global audio session 0 DSP
- 64-band calibration state
- 24-band user EQ
- profiles
- diagnostics
- local API server
- future outbound relay/WebSocket device client
- future sweep/test-tone playback
- minimal native TV status/pairing UI

Do **not** keep a browser dashboard implementation here.

## Web repository — `darel919/sweetspot-web`

Owns all browser UI:

- dashboard
- EQ controls
- profiles UI
- diagnostics UI
- calibration wizard
- microphone capture
- graphs
- measurement analysis
- future auto-correction UI

There should be one browser UI implementation, not one in Nuxt and another embedded inside the APK.

---

# 2. Files to Remove

Remove the old embedded web application directory:

```text
app/src/main/assets/www/
```

Current files known to be obsolete:

```text
app/src/main/assets/www/index.html
app/src/main/assets/www/app.js
app/src/main/assets/www/style.css
```

`index.html` currently imports Vue from the CDN and mounts the legacy dashboard. `app.js` contains the old Vue dashboard logic. `style.css` styles that dashboard.

All three belong to the old browser implementation and should be deleted.

If `app/src/main/assets/` becomes empty after removal, remove the empty directory as well unless another Android asset actually uses it.

Before deleting any additional asset, verify it is not used elsewhere.

---

# 3. `WebServer.kt` Must Become API-Only

Current `WebServer.kt` serves both static files and JSON APIs.

Remove all static web UI serving behavior.

## Remove constants/state used only for web assets

Remove:

```kotlin
private const val WWW_ROOT = "www"
```

Remove any other imports, fields, helpers, or comments that only exist to load Android assets.

---

# 4. Remove Static Routes

Delete routes equivalent to:

```text
GET /
GET /index.html
GET /style.css
GET /app.js
```

Specifically remove logic similar to:

```kotlin
method == "GET" && (path == "/" || path == "/index.html") ->
    serveAsset(...)

method == "GET" && path == "/style.css" ->
    serveAsset(...)

method == "GET" && path == "/app.js" ->
    serveAsset(...)
```

The Android HTTP server must no longer serve HTML, CSS, JavaScript, Vue, or another browser application.

---

# 5. Remove `serveAsset()`

Delete the static-asset helper if it is no longer used:

```kotlin
private fun serveAsset(...)
```

This includes removal of code equivalent to:

```kotlin
context.assets.open(...)
```

After removing asset serving, check whether the `Context` constructor dependency is still required by `WebServer`.

If `Context` is no longer used anywhere in `WebServer.kt`, simplify:

```kotlin
class WebServer(
    private val context: Context,
    private val engine: AudioEngine,
    ...
)
```

to:

```kotlin
class WebServer(
    private val engine: AudioEngine,
    ...
)
```

and update the constructor call in `SweetSpotService`.

Only remove `Context` if it truly has no remaining use.

---

# 6. Root Route Behavior

Do not replace `/` with another HTML page.

Prefer one of these API-server behaviors:

## Preferred

Return a tiny JSON service descriptor:

```http
GET /
```

```json
{
  "service": "SweetSpot",
  "type": "api",
  "status": "ok"
}
```

This is useful when manually visiting the TV IP during development and makes it obvious that the service is alive but no dashboard is hosted there.

Alternatively, `/` may return `404`, but the small JSON response is preferable for diagnostics.

Do not redirect `/` to the hosted Nuxt site from the server. The TV's native status/pairing UI can show the hosted URL/QR separately.

---

# 7. Add/Keep a Small Health Endpoint

If one does not already exist, add:

```http
GET /api/health
```

Suggested response:

```json
{
  "ok": true,
  "service": "SweetSpot",
  "apiVersion": 1
}
```

Keep this response cheap. It must not perform CPU sampling, DSP probing, file operations, or other expensive diagnostics.

The hosted dashboard or future direct-LAN transport can use this to verify that the entered IP is actually a SweetSpot TV.

---

# 8. Preserve Existing API Endpoints

Do **not** remove API endpoints just because their previous caller was the Vue UI.

The Nuxt dashboard and future relay transport still need the underlying capabilities.

Preserve the existing API behavior for endpoints including the current equivalents of:

```text
GET  /api/state
GET  /api/profiles

POST /api/preset
POST /api/saveprofile
POST /api/loadprofile
POST /api/deleteprofile

POST /api/bypass
POST /api/enable
POST /api/bands

GET  /api/eq/calibration
POST /api/eq/calibration
POST /api/eq/calibration/reset

GET  /api/deviceinfo
```

Development/diagnostic APIs may also remain for now:

```text
POST /api/probe
GET  /api/probe/status
POST /api/probe/persist
POST /api/probe/release
POST /api/probe/apply-curve
GET  /api/probe/persistent
```

Do not remove these as part of the UI cleanup unless they are separately proven obsolete.

This task is about removing **presentation code**, not device capabilities.

---

# 9. TV Overlay Endpoints

Current API includes commands similar to:

```text
POST /api/showui
POST /api/hideui
```

These control the native Android TV overlay, not the removed Vue page.

Therefore do **not** automatically delete these endpoints during this cleanup.

Keep them if the native overlay is still intentionally supported.

The browser UI being moved to Nuxt does not mean the native TV status/pairing overlay should disappear.

---

# 10. Update `WebServer.kt` Documentation

Replace comments describing it as an embedded web server that serves browser assets.

Desired wording should communicate approximately:

```text
Minimal dependency-free local HTTP API server for SweetSpot device control.

Responsibilities:
- expose device/DSP state
- accept control commands
- expose calibration/profile operations
- expose development diagnostics

It does not serve the SweetSpot browser dashboard.
The dashboard is hosted separately by sweetspot-web.
```

Remove stale comments mentioning:

- static assets
- `Context.getAssets`
- embedded browser UI
- Vue

Also fix stale references to 128 calibration bands when touching those comments. Production calibration is currently 64 bands.

---

# 11. Update `README.md`

The Android README must no longer say or imply that the TV hosts the main control dashboard.

Document the new architecture:

```text
Android TV SweetSpot
    ↓
local API + future relay device agent

Hosted SweetSpot Web
https://sweetspot.darelisme.my.id
    ↓
all browser UI and calibration UX
```

State clearly:

- Android is the DSP/device backend.
- `sweetspot-web` is the browser frontend.
- the local HTTP server is an API/debugging interface only.
- normal user control will come through the hosted dashboard/relay architecture.

Do not rewrite unrelated README sections unless they are stale because of this architecture change.

---

# 12. Do Not Add Nuxt/Vue Dependencies to Android

After cleanup, verify there is no dependency or runtime reference to:

```text
Vue
Nuxt
unpkg.com/vue
browser CDN scripts
WebView-based dashboard code
```

Do not add a WebView as a replacement.

The Android application should remain native Kotlin and lightweight.

---

# 13. CORS — Prepare API for Future Direct-LAN Mode

The current primary architecture will use the relay, so direct browser-to-TV LAN access is not required for this cleanup.

However, do not make future direct-LAN support harder.

If CORS code already exists, preserve it unless incorrect.

If CORS does not yet exist, it is acceptable to leave it for a dedicated follow-up task rather than mixing security changes into this cleanup.

Future allowed hosted origin will be:

```text
https://sweetspot.darelisme.my.id
```

Do not solve CORS by blindly returning:

```text
Access-Control-Allow-Origin: *
```

on all future authenticated/mutation APIs.

---

# 14. Keep HTTP Server Lightweight

After removal, `WebServer.kt` should conceptually look like:

```text
WebServer
  ├── socket accept loop
  ├── HTTP request parsing
  ├── API routing
  ├── JSON responses
  └── API helpers
```

It should no longer contain:

```text
HTML serving
CSS serving
JavaScript serving
Vue-related assumptions
browser dashboard state
frontend templates
```

Do not add a server framework just for this cleanup.

Keep the existing small dependency-free Kotlin HTTP implementation unless there is a separate reason to replace it.

---

# 15. Verify APK Cleanup

After deleting the old browser files, build:

```bash
./gradlew assembleDebug
```

Confirm:

- build succeeds
- APK installs
- foreground service starts
- audio engine still gets control
- local API still listens on the configured port
- `/api/state` still works
- EQ changes through API are still audible
- profiles still work
- calibration endpoint still works
- native TV overlay still works if retained

Also inspect the built APK and confirm the old files are absent:

```text
assets/www/index.html
assets/www/app.js
assets/www/style.css
```

There should be no bundled Vue dashboard left.

---

# 16. API-Only Acceptance Tests

From another machine on the LAN:

```bash
curl http://TV-IP:8080/
```

Expected: small JSON API/service response, not HTML.

Then:

```bash
curl http://TV-IP:8080/api/health
```

Expected:

```json
{"ok":true,"service":"SweetSpot","apiVersion":1}
```

Then:

```bash
curl http://TV-IP:8080/api/state
```

Expected: current SweetSpot state JSON.

Verify these old UI paths no longer return dashboard assets:

```bash
curl -i http://TV-IP:8080/index.html
curl -i http://TV-IP:8080/app.js
curl -i http://TV-IP:8080/style.css
```

Expected:

```text
404 Not Found
```

Do not return placeholder files.

---

# 17. Recommended Change Order

Perform the cleanup in this order:

```text
1. inspect all references to assets/www
2. inspect all WebServer uses of Context/assets
3. remove static routes from WebServer
4. remove serveAsset()
5. simplify WebServer constructor if Context becomes unused
6. add lightweight root JSON response
7. add /api/health if missing
8. delete index.html
9. delete app.js
10. delete style.css
11. remove empty asset directories if safe
12. update WebServer comments
13. update README architecture wording
14. build APK
15. run API smoke tests
16. verify old UI URLs return 404
```

Keep each change narrowly related to removal of the embedded frontend.

---

# 18. Files Expected to Change

Likely changes:

```text
app/src/main/java/com/darelisme/sweetspot/WebServer.kt
app/src/main/java/com/darelisme/sweetspot/SweetSpotService.kt
README.md
```

Likely deletions:

```text
app/src/main/assets/www/index.html
app/src/main/assets/www/app.js
app/src/main/assets/www/style.css
```

`SweetSpotService.kt` should only need modification if the `WebServer` constructor no longer needs `Context`.

Do not touch DSP implementation files unless compilation requires a small related cleanup.

---

# 19. Explicit Non-Goals

Do not implement the following as part of this cleanup:

- Nuxt dashboard features
- WebSocket relay
- pairing protocol
- QR pairing
- microphone capture
- sweep generation
- auto-correction
- new DSP filters
- redesign of profiles
- redesign of calibration storage
- replacing the Kotlin HTTP server
- removing the native Android TV overlay
- direct-LAN browser transport

Those belong to separate milestones.

---

# 20. Definition of Done

This cleanup is complete when:

1. `app/src/main/assets/www/` is gone.
2. No Vue/HTML/CSS/JS dashboard is bundled in the Android APK.
3. `WebServer.kt` serves JSON/API responses only.
4. Existing DSP/profile/calibration APIs still work.
5. `/api/health` provides a cheap identity/liveness check.
6. `/` returns a small JSON service response rather than UI.
7. old `/index.html`, `/app.js`, and `/style.css` routes return 404.
8. Android builds and runs successfully on the target TV.
9. README states that browser UI lives in `sweetspot-web`.
10. No unrelated DSP or calibration behavior changed.

The architectural rule after this change is simple:

> **SweetSpot Android is the device backend. `sweetspot-web` is the browser frontend.**
