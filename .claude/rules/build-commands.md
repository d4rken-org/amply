# Build Commands

Single module (`:app`), two flavors (`foss`, `gplay`), three build types (`debug`, `beta`, `release`). Use the Gradle
wrapper (`./gradlew`). Build/test on **JDK 21** (Robolectric needs it for Android SDK 36); compiled bytecode targets
**Java 17**. CI runs on JDK 21 (`.github/workflows/code-checks.yml`).

## Build

```bash
# Debug APKs
./gradlew assembleFossDebug
./gradlew assembleGplayDebug

# Release APKs (minified; signed when signing material is present, unsigned otherwise — see release.md)
./gradlew assembleFossRelease
./gradlew assembleGplayRelease

# Clean
./gradlew clean
```

## Test

```bash
# Unit tests per flavor (JVM + Robolectric)
./gradlew testFossDebugUnitTest
./gradlew testGplayDebugUnitTest

# Everything CI runs, in one go
./gradlew testFossDebugUnitTest testGplayDebugUnitTest \
  lintVitalFossBeta lintVitalFossRelease lintVitalGplayBeta lintVitalGplayRelease \
  assembleFossDebug assembleGplayDebug
bash fastlane/check_metadata_length.sh
```

## Lint

```bash
./gradlew lintFossDebug
./gradlew lintGplayDebug
```

## Play Store Screenshots

Screenshots are rendered from `@Preview` composables on the JVM (no device) via the Compose Preview Screenshot
Testing plugin (`com.android.compose.screenshot`, enabled by `android.experimental.enableScreenshotTest=true` in
`gradle.properties`). The store composables live in `app/src/debug/.../screenshots/ScreenshotContent.kt`; the capture
entry points (`@PreviewTest`) and locale annotations live in `app/src/screenshotTest/.../screenshots/`.

```bash
# 1. Render (writes to app/src/screenshotTestGplayDebug/reference/, which is gitignored)
./fastlane/generate_screenshots.sh
# 2. Normalize (flatten alpha → opaque 1080x1920) + sort into the committed metadata tree
./fastlane/copy_screenshots.sh
```

Committed output lands in `fastlane/metadata/android/en-US/images/phoneScreenshots/` as `1_dashboard_light.png …
6_reconnect_gesture.png` (names come from `copy_screenshots.sh`'s `screen_file` map). Both scripts fail
loudly on any count/dimension/format mismatch and `copy_screenshots.sh` requires ImageMagick. Needs the JDK 21 build
toolchain like everything else. CI compiles these sources (`compileGplayDebugScreenshotTestKotlin`) but does **not**
render — layoutlib output differs across machines — so **regenerating screenshots is a manual pre-release step**.

## Install & Inspect

```bash
adb install app/build/outputs/apk/foss/debug/app-foss-debug.apk

# Amply logs use the AMP: tag prefix
adb logcat | grep AMP:

# All variants share the single applicationId (variants are mutually exclusive on a device)
adb shell pm clear eu.darken.amply
```

## Context Management

Per the project's global rules, run gradle builds/tests through the **`devtools:build-runner`** agent (Task tool)
rather than directly, so verbose output stays out of the main conversation. It should report only pass/fail,
compilation errors with file:line, and warning counts. Run gradle directly in the main context only when the user
explicitly asks for full output.

## Pitfalls

- **Plugin versions live in the root `build.gradle.kts`** (AGP, KSP, Kotlin Compose plugin, Hilt) — all applied with
  `apply false` and pulled into `:app`. Keep KSP and the Kotlin Compose plugin compatible with the Kotlin toolchain.
- `buildFeatures { aidl = true; buildConfig = true; compose = true }` are all enabled — the Shizuku `IChargingControlService.aidl`
  and `BuildConfig.ENABLE_PIXEL_LAB_ADAPTER` depend on this.
- `testOptions.unitTests.isIncludeAndroidResources = true` — Robolectric tests can read resources.
- CI (`.github/workflows/code-checks.yml`) is matrix-based (CAPod-style): `lintVital{Foss,Gplay}{Beta,Release}`,
  `assemble{Foss,Gplay}Debug`, `test{Foss,Gplay}DebugUnitTest`, plus a fastlane metadata length check. A change that
  only compiles under one flavor still fails CI, and lintVital compiles the beta/release sources — but CI does **not**
  run R8 packaging. Run `./gradlew assembleFossRelease assembleGplayRelease` locally before a release.
