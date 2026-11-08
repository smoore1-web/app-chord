# ChordEar (app-chord) — Shazam for chord progressions

Tap the big button (or the floating pop-up bubble over any app), play a song,
and ChordEar shows the key, the main chord progression (with Roman numerals)
and every chord it heard.

## Get the app
Open the **Releases** section of this repo and download `ChordEar.apk` on your
Android phone. Tap it to install (allow "Install unknown apps" for your browser
when asked).

## Build
Every push builds the APK automatically with GitHub Actions
(`.github/workflows/build.yml`). To build locally: open in Android Studio, or
run `gradle assembleDebug`.

Requires Android 8.0+.
