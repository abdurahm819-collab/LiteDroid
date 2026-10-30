# LiteDroid 1.2

A lightweight, browse-only F-Droid catalogue client.

## Features
- Searches the official F-Droid full-text search service as you type.
- Shows app names, package IDs, summaries and available descriptions.
- Shows icons and developer links when provided by search results.
- No accounts, ads, app downloads or installer.

## Official F-Droid service
Search requests go to `https://search.f-droid.org/api/search_apps?q=QUERY`, the F-Droid repository's documented full-text search endpoint. LiteDroid requests results only after at least two characters are entered. It does not download the complete repository index.

The app is for displaying catalogue information only. It does not download or verify APKs. F-Droid documents its signed repository indexes separately for security-sensitive repository operations.

## Android
- Minimum: Android 6.0 (API 23)
- Target: API 35
- Version: 1.2 (version code 3)

## Build
Run `gradle assembleDebug`. The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds and publishes LiteDroid 1.2 on pushes to `main` or manual workflow runs.
