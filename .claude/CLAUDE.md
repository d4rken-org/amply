# CLAUDE.md

This file provides guidance to AI coding assistants when working with code in this repository.

## About Amply

Amply is an **experimental Android controller for OEM battery charge-protection modes**. Its primary action
temporarily allows a full charge, then restores the user's protective policy at 100%, on unplug, or at a safety
timeout.

Several control adapters exist — four OEM adapters plus a custom-ROM (LineageOS) adapter. **Pixel charging optimization** is capability-gated to Pixel 6a and newer phones on
Android 15+ when Google's charging-optimization controller is present. **Samsung battery protection** (global
`protect_battery` keys) is gated to verified One UI generations — One UI 8 multi-mode, and the legacy One UI 4/5
toggle — on the system user. **Xiaomi charging protection** (secure `security_pc_secure_protect_mode_key`,
binary Adaptive/Unrestricted) is gated to the HyperOS 2.x ROM (`ro.mi.os.version.code == 2`) on Xiaomi devices.
**OnePlus/ColorOS charging protection** (mutually-exclusive `system` keys `regular_/smart_charge_protection_switch_state`
= FixedLimit(80)/Adaptive) is gated to ColorOS 15 (`ro.build.version.oplusrom == 15`) across the Oplus family
(OnePlus/Oppo/Realme) — **writes require Shizuku** (system namespace). **LineageOS charging control** (the private
`lineagesettings` provider, keys `charging_control_enabled`/`_mode`/`_charging_limit`) is manufacturer-agnostic —
gated to a **physically-qualified device-codename allowlist** (HAL enforcement is per-device) plus the provider and
system user; **reads are unprivileged (ContentResolver), writes require Shizuku** (the shell UID holds
`lineageos.permission.WRITE_SETTINGS`, which `WRITE_SECURE_SETTINGS` does not cover). Other Pixels, Samsung on
unverified One UI versions (6/7, 9+), non-HyperOS-2 Xiaomi devices, non-ColorOS-15 Oplus devices, and unqualified
LineageOS builds remain diagnostics-only. See the qualification ledger in `.claude/rules/privileged-access.md` for
the verified devices and mappings.

Package: `eu.darken.amply`. License: GPL-3.0-or-later. Status: pre-launch (`0.1.0-beta1`).

## Project Shape

- **Single Gradle module**: `:app` (declared in `settings.gradle.kts`). This is *not* a multi-module project.
- **Product flavors** (`distribution` dimension): `foss` and `gplay`.
- **Build types**: `debug`, `beta` (minified), `release` (minified). Every variant shares the single applicationId
  `eu.darken.amply` — no build-type suffixes; installed variants are mutually exclusive (different signing keys).
- **SDKs**: `compileSdk`/`targetSdk` 36, `minSdk` 26. Core-library desugaring enabled.
- **Java**: build/test toolchain needs **JDK 21** (Robolectric requires it to emulate Android SDK 36); compiled
  bytecode still targets **Java 17** (`compileOptions`/`jvmTarget` in `app/build.gradle.kts`).
- **Stack**: Kotlin, Jetpack Compose + Material 3, Navigation3, Glance (widget), Hilt/KSP, Coroutines/Flow,
  Preferences DataStore, Shizuku (AIDL user service).

## Package Layout (feature/core/ui)

Under `app/src/main/java/eu/darken/amply/`:

- `charging/core` — policies, device capability checks, OEM adapters, WSS, Shizuku access (`access/shizuku`, `adapter`)
- `fullcharge/core` — temporary sessions, boot recovery, reconnect gesture
- `main/ui` — activity, onboarding, dashboard, settings, setup guide, `tile`, `widget`
- `diagnostics/core` + `diagnostics/ui` — privileged settings comparison and its guided UI
- `common` — shared DataStore owner (`AppDataStore`) and cross-feature primitives
- `common/theming` — brand, Material You, mode, contrast preferences
- `common/settings` — reusable hierarchical settings rows/sections
- `common/debug/logging` — opt-in debug sessions and logging backends

AIDL boundary: `app/src/main/aidl/eu/darken/amply/charging/core/access/shizuku/IChargingControlService.aidl`.

## Important File Locations

- `version.properties` + `VERSION` — versioning source of truth (parsed by the buildSrc `ProjectConfigPlugin`) and
  its drift mirror; bump via `tools/release/bump.sh`, never by hand
- `buildSrc/` — `ProjectConfig` (packageName/SDKs/version) plus shared build helpers
- `app/build.gradle.kts` — flavors, build types, signing wiring, dependencies
- `build.gradle.kts` (root) — plugin versions (AGP, KSP, Kotlin Compose, Hilt)
- `.github/workflows/code-checks.yml` — CI (builds + tests + lint for both flavors)
- `app/src/main/res/values/strings.xml` — extracted user-facing strings (system-surfaced text)

## Rules

Topic-specific guidance lives in `.claude/rules/`:

- `architecture.md` — package layout, data flow, `ChargeObservation`, adapters, session/recovery, privileged boundary
- `privileged-access.md` — Shizuku/WSS access paths, capability gate, AIDL safety boundary (read before touching control code)
- `build-commands.md` — gradle build/test/lint commands, flavors, build types
- `code-style.md` — Kotlin/Compose conventions, logging, DataStore
- `testing.md` — JUnit 5 + Kotest conventions (JUnit 4 only for Robolectric)
- `commit-guidelines.md` — commit/PR format and prefixes
- `localization.md` — string extraction conventions and the current gap
- `release.md` — versioning, signing, CI (pre-launch state)
- `agent-instructions.md` — sub-agent usage and working principles

## Safety Boundary (read first)

Amply has **no arbitrary shell API**. The Shizuku user service executes argument-separated commands, validates
namespaces and values, and **allowlists every writable setting**. The temporary override relies on a `specialUse`
foreground service because dormant apps cannot reliably receive power-disconnect broadcasts. Never widen the writable
allowlist, bypass the capability gate, or introduce a shell-string execution path. See `rules/privileged-access.md`.
