# Number Change Detector

Barebones Android test app for locating the exact video-sample timestamp where a corner number changes. It reports `none -> 1`, `1 -> 2`, and later changes in-app and writes a compatible JSON result.

The project is intentionally split into a thin `:app` module and a reusable `:scanner` Android library. The scanner uses persisted `content://` input, normalized ROI fractions, bundled ML Kit OCR, conservative 6/9 topology correction, sparse coarse reads, and actual `MediaExtractor` presentation timestamps for local boundary refinement.

## Build

```powershell
.\gradlew.bat clean test assembleDebug
```

Install `app\build\outputs\apk\debug\app-debug.apk`, choose a video, select its corner, and run the Fast profile first. Fast samples every 30 seconds and refines candidates to the source frame timeline; Balanced and Precise increase coarse coverage for short-lived numbers.

## Validated baseline

Version 0.1.1 was exercised in-app on an API 35 emulator against the 3,600-second fixture with SHA-256 `DC6508A164983E6A30C3F0E114E54B6FFBCD4EEFF65E5FABF360EC0E87848258`. It found all 10 transitions from `none -> 1` through `9 -> 10`; every reported timestamp matched the labeled frame PTS exactly, with a 132.745-second scan runtime (27.12x realtime).

Tester builds use the checked-in `app/test-signing.jks` solely to keep sideloaded GitHub APK upgrades compatible. This public test key must never be reused for a production app.
