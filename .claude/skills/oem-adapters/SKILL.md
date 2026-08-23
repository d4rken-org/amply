---
name: oem-adapters
description: Per-OEM charge-control adapter detail for Amply — the exact settings keys, value domains, write ordering, verification strategy, and session override of the Samsung, Xiaomi, OnePlus/ColorOS, LineageOS, and Pixel adapters. Use when reading or changing anything under charging/core/adapter, adding an adapter, or reasoning about how a policy maps to real settings values.
---

# Charging adapters

Per-OEM adapter detail, for work under `charging/core/adapter`. The always-loaded companions are
`.claude/rules/architecture.md` (data flow, `ChargeObservation`, session/recovery) and
`.claude/rules/privileged-access.md` (safety boundary, capability gates) — **read the latter before changing any
control code**. The ledger of physically-verified devices lives in the `device-qualification` skill.

## Samsung Adapters

Two live adapters, gated by `ro.build.version.oneui` ranges plus `protect_battery` presence plus system user
(all in world-readable `global` namespace; only writes need WSS):

- **Modern (One UI 8.x)**: `protect_battery` 0=off / 1=Maximum / 3=Standard(pause at 100%, resume 95%), plus
  `battery_protection_threshold` 80|85|90|95 (absent = 80, only valid ticks decode; malformed → Unknown).
  Policies: FixedLimit(80/85/90/95), PauseAtFull, Unrestricted. Session override = **PauseAtFull** (reaches 100%
  while keeping Samsung's own safety net). Threshold is written before mode.
- **Legacy (One UI 4.x/5.x)**: `protect_battery` 0/1 toggle, fixed 85% cap. Policies: FixedLimit(85),
  Unrestricted. Session override = Unrestricted.

Writes apply **synchronously** (`VerificationStrategy.SYNC_READBACK`): `apply()` requires read-back equality, no
pending-settle window, boot recovery converges on settings readback, and no reapply-inversion trick is needed.
The reconnect gesture runs in `ReconnectSupport.ANY_LEVEL_ONLY` mode: One UI publishes no charging-policy hold
signal, so the limit-hold basis can never arm and the any-level basis is implied on (the sub-option is hidden).
One UI 6/7 and 9+ fall through to the
diagnostics-only lab adapter. An external `protect_battery=0` makes One UI forget the user's prior mode (it falls back
to the OEM default on re-enable), so Amply restores the exact prior policy itself rather than trusting Samsung's
bookkeeping. Verified devices + coverage: see the qualification ledger (`device-qualification` skill).

## Xiaomi Adapters

Two live adapters over the single key `secure/security_pc_secure_protect_mode_key` (both in
`XiaomiChargingAdapter.kt`). Use `ro.mi.os.version.code`, NOT the frozen legacy `ro.miui.ui.version.code`.
Manufacturer Xiaomi covers Redmi/POCO — they report Xiaomi as manufacturer.

- **HyperOS 2 (`xiaomi-hyperos2-v1`)** — gated to the HyperOS ROM version (the two-mode setting is a ROM
  feature, not a per-model one): manufacturer Xiaomi + `ro.mi.os.version.code == 2` + system user. Values:
  `0`=charge fully, `1`=Intelligent (heuristic 80% hold → `ChargePolicy.Adaptive`), absent=Intelligent
  (factory state); `2` does not exist on HyperOS 2 and decodes `Unknown(unrecognizedValue)`. Session
  override = Unrestricted; protective default = Adaptive. Two documented assumptions: the feature is treated
  as present on any HyperOS 2 device (a device lacking it reads the key absent → a harmless false claim of
  control), and daemon-level enforcement of external writes is pending long-term observation.
- **HyperOS 3 (`xiaomi-hyperos3-v1`)** — same key plus `2`="Battery protection" (hard cap →
  `FixedLimit(80)`; both-direction enforcement of external shell-UID writes demonstrated on `tanzanite`,
  issue #48). Gate: manufacturer Xiaomi + `ro.mi.os.version.code == 3` + **qualified-codename allowlist**
  (`QUALIFIED_CODENAMES`, ships `tanzanite`) + system user. Version-only gating is impossible: mode `2` is
  model-/build-dependent within HyperOS 3 (`marblein` on 3.0.2 has only 0/1), the property has no minor
  version, and mode-2 presence cannot be probed (key absent in factory state). Session override =
  Unrestricted; protective default = FixedLimit(80) — the only Xiaomi mode with demonstrated enforcement.
  Absent=Intelligent mirrors HyperOS 2 but is **unverified on HyperOS 3** (pending the issue-#48
  qualification run). No hardware decode: `dumpsys battery` exposes no hold signal on HyperOS 3.

Both are SYNC_READBACK with read-back equality; writes are WSS-capable (`secure` namespace). HyperOS 1,
pre-HyperOS MIUI, and unqualified HyperOS 3 devices fall to `XiaomiLabAdapter` (diagnostics + contribution).
The boundary write domain for the key is `{0,1,2}` globally (see `privileged-access.md`). Qualification
evidence: `device-qualification` skill.

## OnePlus / ColorOS Adapter

One live adapter (`oplus-coloros15-v1`) for the ColorOS/OxygenOS (Oplus) family — OnePlus, Oppo, Realme —
gated to `ro.build.version.oplusrom == 15` (Oplus-exclusive property, so it doubles as the family signal) +
system user. Two mutually-exclusive **`system`** keys under Battery health: `regular_charge_protection_switch_state`
= "Charging limit" (fixed 80% cap → `FixedLimit(80)`) and `smart_charge_protection_switch_state` = "Smart charging"
(adaptive → `Adaptive`); neither on = Unrestricted; both on = Unknown/unrecognized. The OEM enforces exclusion and
keeps a `_status` mirror (Amply writes only `_switch_state`). SYNC_READBACK with read-back equality; session
override = Unrestricted; protective default = FixedLimit(80). **Writes require Shizuku** — the keys are `system`
namespace, which WRITE_SECURE_SETTINGS cannot write (reads are unprivileged); the adapter sets
`preferShizukuForWrites`. Unqualified Oplus versions fall to `OnePlusLabAdapter`. Enforcement is directly
observable (device holds at 80%). See the qualification ledger (`device-qualification` skill).

## LineageOS Adapter

One live adapter (`lineageos-chargingcontrol-v1`) plus a `LineageLabAdapter`, for LineageOS's native Charging
Control. **Manufacturer-agnostic** — the ROM changes charging control regardless of the OEM hardware — so both are
registered **first** in `AdapterRegistry`, ahead of every OEM adapter; a LineageOS build on Samsung/Xiaomi/OnePlus/
Pixel hardware is handled by these, never the OEM lab adapters (stock devices are not `isLineageOs` and skip both).
Gate: `DeviceInfo.isLineageOs` + `Build.DEVICE` in a **physically-qualified codename allowlist**
(`QUALIFIED_CODENAMES`) + `lineagesettings` provider present + system user. Unqualified LineageOS builds fall to
`LineageLabAdapter`.

`isLineageOs` comes from the **`org.lineageos.android` system feature**, not `ro.lineage.build.version`: the
`ro.lineage.*` properties are SELinux-denied to `untrusted_app` (`custom_version_prop`) and read back empty, which
previously made every LineageOS device look like stock and fall through to an OEM adapter. See
`rules/privileged-access.md`.

The three keys live in the private `content://lineagesettings/system` provider (`SettingNamespace.LINEAGE_SYSTEM`),
NOT any AOSP `settings` namespace: `charging_control_enabled` (0/1), `charging_control_mode` (3=LIMIT), and
`charging_control_charging_limit` (the discrete ticks 70/75/80/85/90/95). A hard cap is `enabled=1`+`mode=3`+`limit=N`;
`enabled=0` is Unrestricted. Writes are ordered limit→mode→enabled (the observable "on" flip last). **Reads are
unprivileged** (`LineageSettingsClient` via ContentResolver, shared by both backends); **writes require Shizuku**
(`content insert`; the shell UID holds `lineageos.permission.WRITE_SETTINGS`, which `WRITE_SECURE_SETTINGS` cannot
cover — `preferShizukuForWrites`, and the WSS auto-grant is skipped). `SYNC_READBACK` with read-back equality;
session override = Unrestricted; protective default = FixedLimit(80); reconnect gesture
`ANY_LEVEL_ONLY` (reachable only once the enforcement gate enables control, since it rides `canApply`).

LineageOS's own `ChargingControlController` observes these keys and re-drives the `vendor.lineage.health.
IChargingControl` HAL, so an external write is honored. But the HAL is **device-dependent** (the setting can flip
while charging never actually stops — the `mIsLimitSet:false` bug), which is why the gate is a qualified-codename
allowlist and control ships disabled until a codename is physically proven. `read()` returns `Verified` **only** for
states v1 can restore exactly (a supported fixed limit, or Unrestricted); AUTO/CUSTOM schedules, off-tick limits, and
an absent/malformed `enabled` decode to `Unknown(unrecognizedValue=true)` so a temporary session refuses rather than
clobbering the user's native choice. Verified devices + coverage: see the qualification ledger (`device-qualification` skill).

## GrapheneOS Adapter

One live adapter (`grapheneos-chargelimit-v1`) for GrapheneOS's own "Limit to 80%" (Settings → Battery → Charging
optimization). **ROM-identity adapter, ordered after the Lineage pair and BEFORE `pixel`** in `AdapterRegistry` —
GrapheneOS ships only on Pixels, and the Pixel probe (any Google/Pixel*) would otherwise swallow the device as a
matched-but-diagnostics-only stock Pixel. Gate: `DeviceInfo.isGrapheneOs` (core `app.grapheneos.*` FLAG_SYSTEM
packages via PackageManager `<queries>` — NO property/feature/fingerprint marker exists; verified on a real
device) + system user. **Key presence is not — and cannot be — a gate condition**: the key is `@Protected`
(below), so the unprivileged probe reads absent regardless; the feature is assumed present on any GrapheneOS
build (their platform ships it for every Google device). No lab adapter; `contributionWanted = false`.

Single key `global battery_charge_limit`: `1` = fixed 80% cap (bypass charging; hard-wired, no threshold key) →
`FixedLimit(80)`, `0` **or absent** → `Unrestricted` (upstream reads it via `BoolSetting(..., default false)` —
absent IS the factory off state); other → `Unknown(unrecognizedValue=true)`. **Reads and writes are Shizuku-only**:
GrapheneOS declares the key `@Protected(read = SYSTEM_UI, readWrite = SETTINGS)` (frameworks_base `c30c6393`) and
throws SecurityException for every other package *including WSS holders* (`e87c93a2`); the shell UID is the one
usable exemption, which is exactly the Shizuku user service's `settings get/put` path. `preferShizukuForWrites`;
direct reads come back unreadable and `readSyncDirectFirst` falls through to Shizuku. SYNC_READBACK with read-back
equality; session override = Unrestricted; protective default = FixedLimit(80); `reapply == apply` (no
observer-poke — see below); reconnect gesture **unsupported** (structurally: the gesture's override write lands
strictly after the replug broadcast, which the ROM has already sampled past).

**The defining quirk: `policyLatchesAtPlug = true`.** GrapheneOS samples the key only at plug-session start; an
external write updates the Settings UI live but has no hardware effect until the next unplug→replug (the native
toggle applies live because Settings pokes the charging service directly — so the session watcher's
cancel-without-restore on an observed external change stays correct). This drives the pending-until-replug
verification state and the session engine's 30s replug grace window (see `rules/architecture.md`). While enforcing,
the device reports stock-Pixel hardware state 4 (`EXTRA_CHARGING_STATUS`), which the adapter's own `decodeHardware`
maps to `Verified(FixedLimit(80), BATTERY_HARDWARE)` — deliberately not shared with the Pixel decode, which also
maps state 5 to an Adaptive profile this adapter cannot restore. Remote qualification (issue #49): see the
`device-qualification` skill.

## Pixel Adapter

Writes **only** two secure settings:

- `secure/adaptive_charging_enabled`
- `secure/charge_optimization_mode`

Ordering matters:

- Fixed 80%: adaptive `0`, then mode `1`
- Unrestricted: mode `0`, then adaptive `0`
- Adaptive: mode `0`, then adaptive `1`

Google's Settings Intelligence worker applies external secure-setting changes **asynchronously** (measured
charging-HAL delay ≈ 11–12 s on tested Pixels). A same-package same-value write does **not** fire the settings
observer — re-writes briefly invert `charge_optimization_mode` before applying the target.

