# LiteDroid 1.1

A lightweight, browse-only F-Droid catalogue client.

## What it does
- Loads the live app catalogue from the official F-Droid v2 repository index.
- Searches app names, package IDs and summaries.
- Shows app icons, summaries and full descriptions.
- Shows developer website, source code, issue tracker and donation links when present.
- No accounts, ads, recommendations, app downloads or installer.

## Official catalogue source
LiteDroid reads `https://f-droid.org/repo/index-v2.json` directly over HTTPS. It does not host or repackage the listed apps. The app currently displays catalogue information only; it does not use this index to download or verify APKs.

## Android compatibility
- Minimum Android: 6.0 (API 23)
- Target Android: API 35
- Version: 1.1 (version code 2)

Targeting API 35 addresses the old-target Play Protect compatibility warning on Android versions where API 35 is within the supported target window. Play Protect may still show its separate standard scan prompt for apps installed outside Google Play.

## Build
Open the project in Android Studio, or run:

```bash
gradle assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds and publishes **LiteDroid 1.1** to Releases on pushes to `main` or when run manually.
