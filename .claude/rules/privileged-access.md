# Privileged Access & Safety Boundary

Amply writes hidden/secure Android settings to control OEM charge protection. This is the highest-risk surface in the
app. Read this before modifying anything under `charging/core/access/`, the AIDL, or the capability gate.

Two on-demand companions: the **`oem-adapters` skill** holds the per-OEM keys, value domains, and write ordering the
gates below govern; the **`device-qualification` skill** holds the protocol and the ledger of physically-verified
devices that justify each gate's current width.

## Two Access Paths

`AccessResolver` probes both independently:

1. **Direct `WRITE_SECURE_SETTINGS`** — durable writes. Android blocks third-party *reads* of the hidden Pixel values,
   so WSS-only control can write but cannot verify hidden state.
2. **Shizuku** — provides exact configured-setting readback while running, and is preferred for reads and
   verification.

`ChargingRepository` combines them: Shizuku for reads, direct WSS for durable writes, Shizuku for verification.

Granting WSS in development:

```bash
adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS
```

(All variants share the applicationId `eu.darken.amply`.) Alternatively start Shizuku, grant Amply access, and
optionally use the setup card to grant durable WSS.

## No Arbitrary Shell API

The Shizuku user service (`ChargingControlUserService`, AIDL `IChargingControlService`) exposes a **typed** interface
for get / put / WSS grant / diagnostic snapshots. Hard rules:

- `ProcessBuilder` receives **separate arguments** — no shell string is ever evaluated. Never construct a command
  string and pass it to a shell.
- Writes require a valid **namespace**, valid key/value **syntax**, and an explicit **key allowlist**. Do not widen
  the allowlist without an explicit, reviewed reason.
- Every writable key carries an explicit **per-key value domain** (`SettingWritePolicy`) — the boundary itself
  rejects out-of-domain values. The Samsung keys (`global protect_battery`, `global battery_protection_threshold`),
  the Xiaomi key (`secure security_pc_secure_protect_mode_key`), the Oplus keys (`system
  regular_charge_protection_switch_state`, `system smart_charge_protection_switch_state`), and the GrapheneOS key
  (`global battery_charge_limit`) are all **live** on gated devices (see Capability Gates).

## Capability Gates

### Pixel

Direct Pixel control requires **all** of:

- Google manufacturer
- A Google-supported Pixel 6a-or-newer model (Pixel Tablet excluded)
- Android 15 / API 35 or newer
- Telephony capability
- A resolvable Settings Intelligence charging-optimization action

This runtime gate deliberately avoids both a brittle exact-model allowlist and an unsafe version-only match. Devices
that fail the gate remain **diagnostics-only**. Do not loosen or short-circuit the gate to "make it work" on an
unqualified device — record the device in the qualification ledger (`device-qualification` skill) instead.

### Samsung

Samsung control requires **all** of: Samsung manufacturer, a **verified One UI range** (One UI 8.x for the
multi-mode adapter; One UI 4.x/5.x for the legacy toggle adapter — read from `ro.build.version.oneui`), a present
`global protect_battery` key, and the **system user** (the keys are device-wide; sessions are per-user). One UI
6.x/7.x and 9.x+ are unverified and fall through to the diagnostics-only lab adapter — do not widen the ranges
without a qualified device; record results in the qualification ledger (`device-qualification` skill).

Unlike Pixel's hidden secure settings, Samsung's `global` keys are world-readable: configured-state verification
works without Shizuku, and writes apply synchronously (no Settings-Intelligence-style async middleman).

### OnePlus / ColorOS (Oplus)

Oplus control requires **all** of: `ro.build.version.oplusrom == 15` (ColorOS/OxygenOS 15 — the property is
Oplus-exclusive and covers OnePlus/Oppo/Realme) and the system user. **Writes require Shizuku**: the two keys are in
the `system` namespace, which `WRITE_SECURE_SETTINGS` cannot write (reads are unprivileged). The adapter sets
`preferShizukuForWrites` and read-back-verifies, so a WSS-only write fails honestly. Do not widen to ColorOS 16+
without a qualified device; record results in the qualification ledger (`device-qualification` skill).

### Xiaomi

Xiaomi control requires **all** of: Xiaomi manufacturer (covers Redmi/POCO, which report Xiaomi as
manufacturer), `ro.mi.os.version.code == 2` (HyperOS 2.x — the ROM feature is version-scoped, not
model-scoped), and the system user. Use `ro.mi.os.version.code`, NOT the frozen legacy
`ro.miui.ui.version.code`. Do not widen to HyperOS 3+ without qualifying a device; record results in the qualification ledger (`device-qualification` skill). The single key is per-user `secure`, applied synchronously. Two deliberate
assumptions: the feature is treated as present on any HyperOS 2 device (a device lacking it reads the key
absent → a harmless false claim of control), and daemon-level enforcement of external writes is pending
long-term observation (see Known gaps below).

### GrapheneOS

GrapheneOS control requires **all** of: GrapheneOS identity (`DeviceInfo.isGrapheneOs` — resolved from the OS's
core `app.grapheneos.*` packages via PackageManager `<queries>` entries; **no** graphene property, system feature,
or fingerprint marker exists, verified on a real device), a present world-readable `global battery_charge_limit`
key (the capability signal — GrapheneOS ships the toggle exactly where its implementation works), and the system
user. Identity is deliberately **not** OR-ed with key presence: the adapter is registered ahead of the Pixel
adapter, and a future stock Pixel shipping a same-named key must not be swallowed as GrapheneOS.

The key is binary (`1` = fixed 80% cap with bypass charging, `0` = off) and WSS-writable — **no Shizuku needed**.
The defining quirk is **`policyLatchesAtPlug`**: the ROM samples the key only at plug-session start, so an external
write reads back correctly but has no hardware effect until the next unplug→replug (the native Settings toggle
applies live because Settings pokes the charging service directly). Three mechanisms handle this — the
pending-until-replug verification state (condition-based, no settling clock), the session engine's 30s replug grace
window (a disconnect during a session opens a window instead of restoring, so the user's replug latches the
override rather than a premature restore), and `reapply == apply` (no observer to re-trigger). While enforcing, the
device reports the stock-Pixel hardware signal (`EXTRA_CHARGING_STATUS` = 4), which the adapter decodes for real
enforcement evidence. The reconnect gesture is unsupported — its override write lands strictly after the replug
broadcast, which the ROM has already sampled past.

Qualification is **remote** (issue #49, Pixel 9 Pro XL `komodo`, GrapheneOS 2026080501 / Android 17): the tester
physically observed enforcement (held at 80% with the shield and state 4) and the latch behavior. Package
visibility of `app.grapheneos.*` from app context and the factory-absent key state are still unverified on-device;
both fail closed (Pixel-adapter diagnostics, or diagnostics + contribution wizard). Record any new evidence in the
qualification ledger (`device-qualification` skill).

### LineageOS

LineageOS control requires **all** of: LineageOS (`DeviceInfo.isLineageOs`), a **physically-qualified
device codename** (`Build.DEVICE` ∈ `LineageChargingAdapter.QUALIFIED_CODENAMES`), the `lineagesettings` provider
present, and the system user.

**Detect LineageOS with the `org.lineageos.android` system feature, never `ro.lineage.build.version`.** All five
`ro.lineage.*` properties are labelled `u:object_r:custom_version_prop:s0`, which SELinux denies to
`untrusted_app`. `SystemProperties.get` returns an *empty string* on denial instead of throwing, so the read fails
silently and a LineageOS device is indistinguishable from stock — which routed every LineageOS build to an OEM
adapter (verified on oriole / LineageOS 23.2 / Android 16). `hasSystemFeature` needs no `<queries>` entry and no
permission. `lineageOsVersion` remains a **secondary identity signal** — `isLineageOs` ORs it in so derivatives that
relabel the property still match — and is normally null on real hardware; it is not "diagnostics only".

**Blocker before the first codename is added to `QUALIFIED_CODENAMES`:** the live gate is codename-scoped, but HAL
capability is *build*-scoped — oriole exposed the LIMIT mode bit on LineageOS 20 and dropped it on 23.2 on identical
hardware. A bare codename entry would therefore claim more than any single qualification run proves. Scope the entry
by codename **plus** the qualified Lineage generation / API level (and treat a property-only or derivative match as
insufficient for the live gate), or qualify every build you intend to cover. It is **manufacturer-agnostic** (LineageOS runs on many OEMs), so the Lineage
live/lab adapters are ordered **before all OEM adapters** in `AdapterRegistry` — a LineageOS build on Samsung/
Xiaomi/OnePlus hardware must never be swallowed by a manufacturer-based lab adapter. Unqualified LineageOS builds
fall to `LineageLabAdapter` (diagnostics/contribution).

The three keys live in the private `content://lineagesettings/system` provider — **NOT** any AOSP `settings`
namespace. Modeled as `SettingNamespace.LINEAGE_SYSTEM`: **reads are unprivileged** (`LineageSettingsClient`,
ContentResolver — the provider declares no readPermission), **writes require Shizuku** (`content insert`, the shell
UID holds `lineageos.permission.WRITE_SETTINGS`; `WRITE_SECURE_SETTINGS` cannot write it). The adapter sets
`preferShizukuForWrites`, and `AutoWssGrantCoordinator` skips the WSS auto-grant for it (WSS is useless here).
Writable keys/domains (`LineageSettingWritePolicy`, independent of the adapter): `charging_control_enabled` ∈
{0,1}, `charging_control_mode` = 3 (LIMIT only; mode 0 is invalid, disabling is via enabled=0), and
`charging_control_charging_limit` ∈ {70,75,80,85,90,95}. Verification is `SYNC_READBACK` (read-back equality).

**Crucial gate rationale:** the setting can be written while the `vendor.lineage.health.IChargingControl` HAL never
actually limits (the `mIsLimitSet:false` class of bug) — setting readback does **not** prove hardware enforcement.
That is why the gate is a qualified-codename allowlist, not "any LineageOS device": a device must be physically
proven (see the qualification protocol) before its codename is added. The adapter also **refuses** (reads
`Unknown(unrecognizedValue=true)`) any native state it cannot restore exactly — AUTO/CUSTOM schedule modes, off-tick
limits, or an absent/malformed `enabled` — so a temporary session never clobbers the user's own choice.

## Foreground Service Requirement

The temporary override uses a `specialUse` foreground service because dormant apps cannot reliably receive
power-disconnect broadcasts. Consequences to respect:

- Force-stopping Amply or revoking its privilege can prevent restoration of the protective policy.
- **Google Play publication must stay gated on approval of the declared `specialUse` foreground-service use case.**

## Diagnostics

The Settings → Diagnostics workflow (Shizuku-only) captures before/after setting differences. Reports **redact common
identifiers** and include only setting differences — never widen what a diagnostic report emits.

## Qualifying a new device / OEM

Adding or widening a live adapter requires **physical qualification**, not just a settings mapping — a setting can
read back correctly while the charging HAL never actually limits. Do not loosen or short-circuit a gate to "make it
work" on an unqualified device.

The full protocol, the ledger of physically-verified devices, and the per-OEM known gaps live in the
**`device-qualification` skill** (`.claude/skills/device-qualification/SKILL.md`). Read it before qualifying a
device, widening a gate, or adding a codename to an allowlist.
