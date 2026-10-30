# LiteDroid 1.0

A minimal, browse-only F-Droid catalogue client.

## What it does
- Searches the official F-Droid repository.
- Shows app icons, names, summaries and full descriptions.
- Shows developer-provided website, source code, issue tracker and donation links when present.
- Keeps the interface focused: no recommendations, accounts, ads or install manager.

## Build
Open this project in Android Studio, or run:

```bash
gradle assembleDebug
```

The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.

The GitHub Actions workflow can build and publish LiteDroid 1.0 to Releases when run manually or when the `v1.0` tag is pushed.

## Data
Catalogue metadata and icons are read from the official F-Droid repository. LiteDroid does not host or repackage the apps listed in the catalogue.

## Release status
The `main` branch build publishes the debug APK as the GitHub Release named **LiteDroid 1.0**.
