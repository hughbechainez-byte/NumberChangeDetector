# Number Change Detector and CompilationMaker Prototype

This sister repository contains both the original barebones number-change detector and a separately installable prototype of CompilationMaker 0.17.26 using the same sparse, PTS-aware scanner.

The modules are:

- `:scanner`: reusable scanner library using normalized ROI fractions, bundled ML Kit OCR, conservative 6/9 topology correction, sparse coarse reads, and actual `MediaExtractor` presentation timestamps.
- `:app`: thin barebones timestamp viewer retained for focused scanner testing.
- `:prototype`: the CompilationMaker UI, background job, clip-planning, and Media3 export pipeline with the scanner integrated behind `Prototype Fast PTS (30s)`.

The prototype uses application ID `com.hughbechainez.compilationmaker.prototype`, so it installs alongside the production CompilationMaker app and does not participate in its update feed.

## Build

```powershell
.\gradlew.bat clean :scanner:test :prototype:testDebugUnitTest :prototype:lintDebug :prototype:assembleDebug
```

Install `prototype\build\outputs\apk\debug\prototype-debug.apk`, choose Video A, select `Prototype Fast PTS (30s)`, and start compilation. The profile samples every 30 seconds, refines each candidate against source presentation timestamps, displays the detected changes, and passes the resulting marks into CompilationMaker's clip/export pipeline.

The original focused app remains buildable with `:app:assembleDebug`; its APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Prototype fixture gate

The prototype instrumentation test uses the one-hour Video A fixture with SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`. It requires scanner version `v1-sparse-pts-ocr`, all ten ordered transitions from `none -> 1` through `9 -> 10` at their exact frame PTS, ten planned clips, and a nonempty exported video. A successful unit/build run alone does not claim that runtime gate passed.

## Validated baseline

Barebones version 0.1.1 was exercised in-app on an API 35 emulator against the 3,600-second fixture. It found all ten transitions, every timestamp matched the labeled frame PTS exactly, and scanning took 132.745 seconds (27.12x realtime). Prototype 0.2.0 passed the full API 35 fixture gate: ten exact frame-PTS transitions, ten clips totaling 400.000 seconds, and a verified 31,406,917-byte export. The isolated final run scanned in 265.390 seconds while another emulator was active; an uncontended integration run scanned in 106.367 seconds (33.85x realtime). Prototype 0.2.1 ports the v0.17.26 finite-report and confirmed-candidate deduplication delta; those changes are outside the stable sparse-PTS scan path and are covered by the regression/unit gate.

Tester builds use the checked-in `app/test-signing.jks` solely to keep sideloaded GitHub APK upgrades compatible. This public test key must never be reused for a production app. Tags publish `CompilationMaker-Prototype-v<version>.apk` from the `:prototype` module.
