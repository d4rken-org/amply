# CLAUDE.md

This file provides guidance to AI coding assistants when working with code in this repository.

## About Amply

Amply is an **experimental Android controller for OEM battery charge-protection modes**. Its primary action
temporarily allows a full charge, then restores the user's protective policy at 100%, on unplug, or at a safety
timeout.

Several control adapters exist — four OEM adapters plus two custom-ROM adapters (LineageOS, GrapheneOS). **Pixel charging optimization** is capability-gated to Pixel 6a and newer phones on
Android 15+ when Google's charging-optimization controller is present. **Samsung battery protection** (global
`protect_battery` keys) is gated to verified One UI generations — One UI 8 multi-mode, and the legacy One UI 4/5
toggle — on the system user. **Xiaomi charging protection** (secure `security_pc_secure_protect_mode_key`)
has two adapters: binary Adaptive/Unrestricted gated to the HyperOS 2.x ROM (`ro.mi.os.version.code == 2`), and a
HyperOS 3 three-mode variant adding a FixedLimit(80) hard cap — gated to HyperOS 3 **plus a qualified-codename
allowlist** (mode `2` is not HyperOS-3-wide and cannot be probed at runtime).
**OnePlus/ColorOS charging protection** (mutually-exclusive `system` keys `regular_/smart_charge_protection_switch_state`
= FixedLimit(80)/Adaptive) is gated to ColorOS 15 (`ro.build.version.oplusrom == 15`) across the Oplus family
(OnePlus/Oppo/Realme) — **writes require Shizuku** (system namespace). **LineageOS charging control** (the private
`lineagesettings` provider, keys `charging_control_enabled`/`_mode`/`_charging_limit`) is manufacturer-agnostic —
it matches every LineageOS build that ships the provider (plus the system user), but control is gated on
**enforcement observed on the device itself** (HAL enforcement is per-build, and a read-back proves only that the
ROM stored the value): a device stays a candidate with controls off until the user runs a verification and the cap
is seen holding, and loses them for good if the battery is seen charging past it. **Reads are unprivileged
(ContentResolver), writes require Shizuku** (the shell UID holds `lineageos.permission.WRITE_SETTINGS`, which
`WRITE_SECURE_SETTINGS` does not cover). **GrapheneOS charge limit**
(`global battery_charge_limit`, binary FixedLimit(80)/Unrestricted) is gated to GrapheneOS identity (its
`app.grapheneos.*` core packages; no property/feature/fingerprint marker exists) plus the system user —
**reads AND writes require Shizuku**: GrapheneOS marks the key `@Protected`, denying it to all third-party
packages including WSS holders, with only the shell UID exempt. The ROM **latches the key at plug-session start**
(`policyLatchesAtPlug`), so external writes take effect at the next unplug→replug — handled by a
pending-until-replug verification state and a 30s session grace window; the reconnect gesture is unsupported
there. Other Pixels, Samsung on unverified One UI versions (6/7, 9+), unqualified Xiaomi devices,
non-ColorOS-15 Oplus devices, and LineageOS builds without the settings provider remain diagnostics-only. See the
qualification
ledger (`.claude/skills/device-qualification/`) for the verified devices and mappings.

Package: `eu.darken.amply`. License: GPL-3.0-or-later. Status: pre-launch (current version in `VERSION`).

## Project Shape

Single Gradle module (`:app`), flavors and build types are declared in `app/build.gradle.kts`. Two non-obvious
constraints:

- **Java**: build/test toolchain needs **JDK 21** (Robolectric requires it to emulate Android SDK 36); compiled
  bytecode still targets **Java 17** (`compileOptions`/`jvmTarget` in `app/build.gradle.kts`).
- **Every variant shares the single applicationId** `eu.darken.amply` — no build-type suffixes. Because signing
  certificates differ, installed variants are mutually exclusive on a device; switching requires an uninstall.

## Package Layout (feature/core/ui)

Under `app/src/main/java/eu/darken/amply/`:

- `charging/core` — policies, device capability checks, OEM adapters, WSS, Shizuku access (`access/shizuku`, `adapter`)
- `charging/core/enforcement` — the observed-enforcement gate: verdict engine, durable evidence, monitor watcher
- `fullcharge/core` — temporary sessions, boot recovery, reconnect gesture
- `main/ui` — activity, onboarding, dashboard, settings, setup guide, `tile`, `widget`
- `diagnostics/core` + `diagnostics/ui` — "Help add support" contribution wizard: read-only multi-mode setting
  discovery + on-device privacy review
- `common` — shared DataStore owner (`AppDataStore`) and cross-feature primitives
- `common/datastore` — the `createValue()` settings DSL every preference facade is built on (`DataStoreValue`)
- `common/serialization` — the single `Json` plus `ChargePolicySerializer`, for JSON-backed setting records
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

Always-loaded topic guidance lives in `.claude/rules/`:

- `architecture.md` — data flow, `ChargeObservation`, session/recovery, reconnect gesture, pitfalls
- `privileged-access.md` — Shizuku/WSS access paths, capability gates, AIDL safety boundary (read before touching control code)
- `build-commands.md` — gradle build/test/lint commands, flavors, build types
- `code-style.md` — Kotlin/Compose conventions, logging, DataStore
- `testing.md` — JUnit 5 + Kotest conventions (JUnit 4 only for Robolectric)
- `commit-guidelines.md` — commit/PR format and prefixes
- `localization.md` — string extraction conventions and the current gap
- `agent-instructions.md` — sub-agent usage and working principles

Loaded on demand, as skills (`.claude/skills/`) — there are **no nested `CLAUDE.md` files** in this repo, all
guidance lives under `.claude/`:

- `oem-adapters` — per-OEM adapter detail (keys, value domains, write ordering, session overrides)
- `device-qualification` — physical qualification protocol, verified-device ledger, per-OEM known gaps
- `release` — versioning, `bump.sh`, signing, release workflows, store metadata + screenshots

## Safety Boundary (read first)

Amply has **no arbitrary shell API**. The Shizuku user service executes argument-separated commands, validates
namespaces and values, and **allowlists every writable setting**. The temporary override relies on a `specialUse`
foreground service because dormant apps cannot reliably receive power-disconnect broadcasts. Never widen the writable
allowlist, bypass the capability gate, or introduce a shell-string execution path. See `rules/privileged-access.md`.
