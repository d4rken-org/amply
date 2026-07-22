# Privileged Access & Safety Boundary

Amply writes hidden/secure Android settings to control OEM charge protection. This is the highest-risk surface in the
app. Read this before modifying anything under `charging/core/access/`, the AIDL, or the capability gate.

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
  the Xiaomi key (`secure security_pc_secure_protect_mode_key`), and the Oplus keys (`system
  regular_charge_protection_switch_state`, `system smart_charge_protection_switch_state`) are all **live** on gated
  devices (see Capability Gates).

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
unqualified device — record the device in the qualification ledger below instead.

### Samsung

Samsung control requires **all** of: Samsung manufacturer, a **verified One UI range** (One UI 8.x for the
multi-mode adapter; One UI 4.x/5.x for the legacy toggle adapter — read from `ro.build.version.oneui`), a present
`global protect_battery` key, and the **system user** (the keys are device-wide; sessions are per-user). One UI
6.x/7.x and 9.x+ are unverified and fall through to the diagnostics-only lab adapter — do not widen the ranges
without a qualified device; record results in the qualification ledger below.

Unlike Pixel's hidden secure settings, Samsung's `global` keys are world-readable: configured-state verification
works without Shizuku, and writes apply synchronously (no Settings-Intelligence-style async middleman).

### OnePlus / ColorOS (Oplus)

Oplus control requires **all** of: `ro.build.version.oplusrom == 15` (ColorOS/OxygenOS 15 — the property is
Oplus-exclusive and covers OnePlus/Oppo/Realme) and the system user. **Writes require Shizuku**: the two keys are in
the `system` namespace, which `WRITE_SECURE_SETTINGS` cannot write (reads are unprivileged). The adapter sets
`preferShizukuForWrites` and read-back-verifies, so a WSS-only write fails honestly. Do not widen to ColorOS 16+
without a qualified device; record results in the qualification ledger below.

### Xiaomi

Xiaomi control requires **all** of: Xiaomi manufacturer (covers Redmi/POCO, which report Xiaomi as
manufacturer), `ro.mi.os.version.code == 2` (HyperOS 2.x — the ROM feature is version-scoped, not
model-scoped), and the system user. Use `ro.mi.os.version.code`, NOT the frozen legacy
`ro.miui.ui.version.code`. Do not widen to HyperOS 3+ without qualifying a device; record results in the qualification ledger below. The single key is per-user `secure`, applied synchronously. Two deliberate
assumptions: the feature is treated as present on any HyperOS 2 device (a device lacking it reads the key
absent → a harmless false claim of control), and daemon-level enforcement of external writes is pending
long-term observation (see Known gaps below).

### LineageOS

LineageOS control requires **all** of: LineageOS (`ro.lineage.build.version` present), a **physically-qualified
device codename** (`Build.DEVICE` ∈ `LineageChargingAdapter.QUALIFIED_CODENAMES`), the `lineagesettings` provider
present, and the system user. It is **manufacturer-agnostic** (LineageOS runs on many OEMs), so the Lineage
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

Adding or widening a live adapter requires physical qualification, not just a settings mapping:

1. **Characterize** — record model/codename, build fingerprint, OS + OEM-component versions, and the native
   charge-protection options. Diff `settings list {secure,system,global}` before/after each native UI state to isolate
   the exact key(s) and value domain. Note whether a key is **absent** in factory state (absent ≠ off).
2. **Validate hardware** — drive the raw key transitions unplugged, and charging below/near/above the limit, wired
   **and** wireless. The gate passes only if writes move the **real charging hardware** (battery status / sysfs
   `charging_policy` / current), not just the Settings UI.
3. **Validate access tiers** — WSS-only (writes apply, hidden reads stay truthfully "requested", grant survives
   reboot), Shizuku-only (authoritative readback, denial + binder-death + restart), and both (WSS write → Shizuku
   readback). An external native change must never be claimed as verified.
4. **Validate sessions** — full charge, early disconnect, manual restore, arm + safety timeouts, process death,
   force-stop, notification denial, real reboot + deferred `BOOT_COMPLETED` redelivery. Confirm exact restore of the
   prior value **including a previously-absent key**, and that an unknown/malformed value **refuses** the session
   without mutating the setting.
5. **Minified build** — repeat the smoke path on an R8 `foss` beta (past runs caught R8-only startup/reflection
   breakage that debug builds hid).

Record every scenario as **PASS / FAIL / NOT RUN / BLOCKED** — an untestable hardware condition is not a pass. Never
commit device serials or user data. Requalify after a relevant OS / OEM-component update.

**Go / no-go**: *Go* only if writes reliably control the hardware and restore safely (enable just the tested model/OS
row). *Shizuku-only* if control needs an extra safe op invokable through the typed service. *No-go* → keep the device
diagnostics-only.

## Verified devices (physically tested)

The gate's supported *scope* (see Capability Gates) is broader than what has been physically tested below. Widen a gate
only after adding a row here. Detailed run narratives live in each adapter's landing commit.

| OEM | Tested device / build | Hardware evidence | Coverage | Landed |
|---|---|---|---|---|
| Pixel | Pixel 8 `shiba` A17/API37; Pixel 9 Pro `caiman` A16/API36; Pixel 7a `lynx` A16/API36 | Full — sysfs `charging_policy` follows writes (~11–12s) | Access tiers, sessions, boot recovery, wireless hold, at-threshold, reconnect gesture, natural 100% | 2026-07-15/-19/-20 |
| Samsung | Galaxy Tab A9+ SM-X210 One UI 8.0; Galaxy S20 FE SM-G781B One UI 4.1 | Full — sync readback + HAL enforcement | Modern multi-mode + legacy toggle, session E2E, native-change cancel, reboot recovery, R8 beta | 2026-07-21 |
| Xiaomi | Xiaomi 13T `2306EPN60G` HyperOS 2.0 (`ro.mi.os.version.code=2`) | **Partial** — mapping/readback/session verified; the adaptive 80% hold could not be triggered, so daemon-level hardware enforcement is **not yet demonstrated** | Read matrix, both-direction writes, session at 100%, unknown-value refusal, R8 beta | 2026-07-21 |
| OnePlus (Oplus) | OnePlus Nord CE4 Lite `CPH2621` ColorOS 15 (`ro.build.version.oplusrom=V15.0.0`) | Full — enforcement directly observable (device holds at 80%); external writes stick | Two mutually-exclusive `system` keys (Charging limit / Smart charging), WSS-only write rejected + Shizuku write succeeds for all three policies, WSS-only UX (controls disabled + Shizuku-required banner) | 2026-07-21 |

## Known gaps

- **LineageOS** — landed **diagnostics-only**: `QUALIFIED_CODENAMES` ships **empty**, so the live adapter never
  matches and every LineageOS build falls to `LineageLabAdapter`. A codename is added only after that device passes
  qualification (real charging cessation at the limit, wired + wireless, below/at/above threshold) and gets a
  "Verified devices" row.
  - **Pixel 6 (oriole) on LineageOS 20.0 / Android 13 — tested 2026-07-22, result NO-GO (no hardware enforcement).**
    The *software chain is fully validated* — both raw (shell-UID `content query`/`content insert`) **and through the
    app's real Shizuku backend end-to-end** (R8 debug build + Shizuku granted): tapping 80 % wrote the trio via
    `writeLineageSetting` and read back `Verified(FixedLimit(80), SHIZUKU)`. LineageOS's `ChargingControlController`
    **observes the external write** and updates its live config (`dumpsys lineagehealth` → `mConfigEnabled:true,
    mConfigMode:3, mConfigLimit:80`); the `vendor.lineage.health.IChargingControl/default` HAL is registered and its
    service runs. The refuse-don't-clobber decode was also confirmed live: a native `mode=2` (CUSTOM) state read as
    `Unknown(unrecognized)` ("Policy not verified"), never overwritten. And the diagnostics-only path was smoke-tested
    (empty allowlist → `lineageos-lab` → "Detected for diagnostics only", no crash). **But the hard percent
    limit did not cut charging** — with `limit=80` set, the battery charged 92→95 %+ at ~1.2 A (`charge_stage=Inactive`,
    `charge_limit` sysfs empty, `mChargingStopReason:0`). On Pixel the charge hardware is driven by Google's
    adaptive/`charge_deadline` mechanism, and this Lineage build's HAL does not map the %-cap to a real cutoff — the
    `mIsLimitSet:false` device-dependent class of gap. So oriole stays **out** of the allowlist. This is a clean
    validation of the conservative gate: an "any LineageOS device" gate would have claimed 80 % protection while the
    phone charged to 100 %. (Qualifying a device with a *working* charge-control HAL remains open; the adapter's
    provider/observer mechanism is proven, only per-device HAL enforcement varies.)
  - Also confirmed on oriole: `charging_control_*` keys are **absent in factory state** (validates the conservative
    "absent → unrecognized" decode). Still open: the provider's change-notification URI form (per-key vs table)
    against the registered `observedSettingUris`, to be checked on a HAL-enforcing device.
- **Xiaomi** — adaptive hardware enforcement of external writes unconfirmed; treat the adapter as provisional until
  the 80% hold is physically observed.
- **Pixel** — wireless at-threshold hold/charge-past and the widget under Shizuku-only remain unexercised (both share
  the verified wired mechanism).
- **Recovery notification** — the "Charge limit needs attention" notification is not cancelled when a later restore
  succeeds; it lingers until swiped (cosmetic; `SessionNotifications.showRecovery`).
