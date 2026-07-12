# Camera

A native Android camera app written in Kotlin with Gradle, built on the
[kotlin-android-template](https://github.com/cortinico/kotlin-android-template).

## Features

- Full-screen CameraX preview with a Samsung-style shutter bar
- Front/back camera flip
- A small circular thumbnail of the last photo you took -- tap it to jump into
  your gallery app (tries Samsung Gallery first, falls back to the device's
  default gallery/photos app)
- Photos are saved to `Pictures/CameraApp` via `MediaStore`

## Requirements

- Android 12 (API 31) through Android 15 (API 35)

## Building

This project is built with Gradle. Locally:

```bash
./gradlew assembleDebug
```

Or let GitHub Actions do it: every push/PR (and manual `workflow_dispatch`
runs) triggers `.github/workflows/android-build.yaml`, which lints, runs unit
tests, assembles a debug APK, and uploads it as a workflow artifact.
