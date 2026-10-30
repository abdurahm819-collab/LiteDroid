# LiteDroid 1.7.0 Stable

A lightweight, browse-only F-Droid catalogue client.

## Features
- Searches F-Droid and loads more results as you scroll.
- Releases off-screen app icons to keep memory use low.
- Shows app names, package IDs, summaries, descriptions and icons.
- Displays the official APK file size and version from F-Droid's signed index.
- Downloads APKs from the official F-Droid repository and verifies their SHA-256 checksum before installation.

## Android
- Minimum: Android 6.0 (API 23)
- Target: API 35
- Version: 1.7.0 Stable (version code 11)

## Build
Run `gradle assembleDebug`. The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds and publishes LiteDroid 1.7.0 Stable on pushes to `main` or manual workflow runs.
