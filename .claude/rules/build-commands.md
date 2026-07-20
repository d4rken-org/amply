# Build Commands

Single module (`:app`), two flavors (`foss`, `gplay`), three build types (`debug`, `beta`, `release`). Use the Gradle
wrapper (`./gradlew`). Build/test on **JDK 21** (Robolectric needs it for Android SDK 36); compiled bytecode targets
**Java 17**. CI runs on JDK 21 (`.github/workflows/code-checks.yml`).

## Build

```bash
# Debug APKs
./gradlew assembleFossDebug
./gradlew assembleGplayDebug

# Release APKs (minified; needs signing config — see release.md)
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
  lintFossDebug lintGplayDebug \
  assembleFossDebug assembleGplayDebug \
  assembleFossRelease assembleGplayRelease
```

## Lint

```bash
./gradlew lintFossDebug
./gradlew lintGplayDebug
```

## Install & Inspect

```bash
adb install app/build/outputs/apk/foss/debug/app-foss-debug.apk

# Amply logs use the AMP: tag prefix
adb logcat | grep AMP:

# Debug build's applicationId is suffixed with .debug
adb shell pm clear eu.darken.amply.debug
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
- CI (`.github/workflows/code-checks.yml`) builds **both** flavors and both debug+release; a change that only compiles
  under one flavor will fail CI.
