# Number Change Detector and CompilationMaker Prototype

This sister repository contains both the original barebones number-change detector and a separately installable CompilationMaker prototype using the same sparse, PTS-aware scanner.

The modules are:

- `:scanner`: reusable scanner library using normalized ROI fractions, bundled ML Kit OCR, conservative 6/9 topology correction, sparse coarse reads, and actual `MediaExtractor` presentation timestamps.
- `:app`: thin barebones timestamp viewer retained for focused scanner testing.
- `:prototype`: the CompilationMaker UI, background job, clip-planning, and Media3 export pipeline with the scanner integrated behind `Prototype Fast PTS (30s)`.

The prototype uses application ID `com.hughbechainez.compilationmaker.prototype`, so it installs alongside the production CompilationMaker app and does not participate in its update feed.

## Background status and picture-in-picture

Prototype 0.4.0 keeps an active scan or compilation in its foreground media-processing worker, holds a bounded CPU wake lock for that user-started job, and automatically enters a dedicated status PiP when the app is minimized. The PiP shows the current percentage, pipeline phase, worker state, and status text.

`Concise heartbeat` records stage changes and a status heartbeat at most every 30 seconds. `Core activity log` shows a 250 ms sampled, process-local stream of real frame fetch, ROI preparation, OCR, PTS enumeration, binary-search, and boundary-confirmation events. The rapid stream is not Android logcat and does not write its high-frequency events to disk or WorkManager.

## Updates and lazy boundary confirmation

Prototype 0.3.1 checks the verified sister-repository update feed immediately and every five minutes while visible or in PiP. Android background checks use WorkManager's 15-minute minimum. Available releases produce a notification; the default-off automatic option downloads the APK, verifies its feed SHA-256, package, version code, and signing certificate, then waits for Android's required installation approval.

Exact boundary confirmation now requests neighboring source frames lazily. It preserves the previous earliest persistent-frame rule but stops decoding and running OCR immediately after that rule is satisfied instead of eagerly processing the full 15-frame recovery window.

## Monotonic turbo scanning

Prototype 0.4.0 adds the opt-in `Monotonic Turbo PTS (3m adaptive, persistent 1→N)` profile. It takes three-minute macro checkpoints, recursively probes only intervals whose directly observed endpoints skip values, and accepts a plan only when the observed timeline is persistent, nondecreasing, begins at 1, and advances one number at a time. Every transition still goes through the existing exact presentation-timestamp and persistence confirmation path.

The mode never invents a missing number. A reset, reversal, return to no-number, invalid intermediate state, exhausted probe budget, or failed exact refinement discards the entire turbo attempt and reruns the proven 30-second scan with the same OCR cache. Equal endpoints intentionally mean no hidden change under this mode's persistent-counter contract, so videos with temporary or reversing numbers must use a general profile.

## Build

```powershell
.\gradlew.bat clean :scanner:test :prototype:testDebugUnitTest :prototype:lintDebug :prototype:assembleDebug
```

Install `prototype\build\outputs\apk\debug\prototype-debug.apk`, choose Video A, select `Prototype Fast PTS (30s)`, and start compilation. The profile samples every 30 seconds, refines each candidate against source presentation timestamps, displays the detected changes, and passes the resulting marks into CompilationMaker's clip/export pipeline.

The original focused app remains buildable with `:app:assembleDebug`; its APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Prototype fixture gate

The prototype instrumentation test uses the one-hour Video A fixture with SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`. It requires scanner version `v1-sparse-pts-ocr`, all ten ordered transitions from `none -> 1` through `9 -> 10` at their exact frame PTS, ten planned clips, and a nonempty exported video. A successful unit/build run alone does not claim that runtime gate passed.

## Validated baseline

Barebones version 0.1.1 was exercised in-app on an API 35 emulator against the 3,600-second fixture. It found all ten transitions, every timestamp matched the labeled frame PTS exactly, and scanning took 132.745 seconds (27.12x realtime). Prototype 0.2.0 passed the full API 35 fixture gate: ten exact frame-PTS transitions, ten clips totaling 400.000 seconds, and a verified 31,406,917-byte export. The isolated final run scanned in 265.390 seconds while another emulator was active; an uncontended integration run scanned in 106.367 seconds (33.85x realtime). Prototype 0.2.1 ports the v0.17.26 finite-report and confirmed-candidate deduplication delta. Prototype 0.3.0 adds background/PiP visibility and core telemetry. Prototype 0.3.1 adds verified periodic update handling and a policy-equivalent lazy boundary search. Prototype 0.4.0 adds the guarded monotonic profile and automated planner/fallback coverage; neither new profile nor updater has a new connected-device fixture-runtime claim yet.

Tester builds use the checked-in `app/test-signing.jks` solely to keep sideloaded GitHub APK upgrades compatible. This public test key must never be reused for a production app. Tags publish `CompilationMaker-Prototype-v<version>.apk` from the `:prototype` module.
