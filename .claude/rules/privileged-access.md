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

Two live adapters over the same per-user `secure` key (applied synchronously). Use `ro.mi.os.version.code`, NOT
the frozen legacy `ro.miui.ui.version.code`.

**HyperOS 2** (`xiaomi-hyperos2-v1`) requires **all** of: Xiaomi manufacturer (covers Redmi/POCO, which report
Xiaomi as manufacturer), `ro.mi.os.version.code == 2` (the two-mode feature is version-scoped, not model-scoped),
and the system user. Two deliberate assumptions: the feature is treated as present on any HyperOS 2 device (a
device lacking it reads the key absent → a harmless false claim of control), and daemon-level enforcement of
external writes is pending long-term observation (see Known gaps below).

**HyperOS 3** (`xiaomi-hyperos3-v1`, adds mode `2` = Battery protection, hard cap 80%) requires **all** of:
Xiaomi manufacturer, `ro.mi.os.version.code == 3`, a **physically-qualified device codename**
(`XiaomiHyperOs3ChargingAdapter.QUALIFIED_CODENAMES`, ships with `tanzanite`), and the system user. The gate
CANNOT be version-only: mode `2` is model-/build-dependent within HyperOS 3 (a Poco F5 `marblein` on HyperOS
3.0.2 carries only the two old modes), the version property exposes no minor version, and no runtime probe for
mode-2 presence exists — the key is absent in factory state and reading it returns only the current value. Both-
direction hardware enforcement of external shell-UID writes was demonstrated on `tanzanite` (issue #48); no
hardware hold signal exists in `dumpsys battery`, so verification is read-back only. The boundary write domain
for the key is `{0,1,2}` **globally** — accepted on HyperOS 2 because no Amply code path emits `2` there and the
HyperOS 2 decode refuses it. Widen the codename allowlist only with a qualified device plus a ledger row
(`device-qualification` skill); unqualified HyperOS 3 devices fall to the lab adapter.

### GrapheneOS

GrapheneOS control requires **all** of: GrapheneOS identity (`DeviceInfo.isGrapheneOs` — resolved from the OS's
core `app.grapheneos.*` system packages (FLAG_SYSTEM required) via PackageManager `<queries>` entries; **no**
graphene property, system feature, or fingerprint marker exists, verified on a real device) and the system user.
**Key presence is deliberately NOT a gate condition and cannot be**: GrapheneOS declares the key
`@Protected(read = SYSTEM_UI, readWrite = SETTINGS)` (frameworks_base `c30c6393`) and its SettingsProvider throws
`SecurityException` on reads and writes from every other package — **including `WRITE_SECURE_SETTINGS` holders**;
the check is package-based and runs after the permission check (`e87c93a2`). The unprivileged probe therefore
reads absent whether the key exists or not (verified via the issue-#49 beta report: probe false while the same
report showed the limit enforcing). The feature is treated as present on any GrapheneOS build (Xiaomi-precedent
assumption; their platform ships it for every Google device, which is every device GrapheneOS supports). Accepted
failure mode, same class as Xiaomi's: on a build without the feature a shell-UID write could still create the row
and read back, yielding a harmless false claim of configured control — no battery hazard, and the hardware decode
(state 4) stays honest.

**Access is Shizuku-only.** The one usable exemption in their enforcement is the shell UID ("ADB is used for
testing"), which is how the Shizuku user service executes `settings get/put`. The adapter sets
`preferShizukuForWrites`; direct reads come back unreadable (SecurityException → `access_read_blocked`) and
`readSyncDirectFirst` falls through to the Shizuku backend. The key is binary (`1` = fixed 80% cap with bypass
charging, `0` = off); an **absent key decodes as the factory off state** — upstream reads it through
`BoolSetting(GLOBAL, BATTERY_CHARGE_LIMIT, /* default */ false)`, so a never-toggled device has no row and
charges unrestricted.

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
physically observed enforcement (held at 80% with the shield and state 4), the latch behavior, and — via the
0.3.2-beta0 report — that package detection works from app context while unprivileged key access is denied.
Shizuku-path writes are the same shell-UID mechanism the tester's adb commands proved. Record any new evidence in
the qualification ledger (`device-qualification` skill).

### LineageOS

LineageOS control requires **all** of: LineageOS (`DeviceInfo.isLineageOs`), the `lineagesettings` provider
present, the system user, and clearing the **enforcement evidence gate** for this exact build (a maintainer
codename, or the user's explicit opt-in on an unconfirmed build, and no refutation — see "Enforcement evidence gate"
below). The adapter *matches* every provider-carrying LineageOS build; what it may *do* there is decided by the
gate, not by a codename.

**Detect LineageOS with the `org.lineageos.android` system feature, never `ro.lineage.build.version`.** All five
`ro.lineage.*` properties are labelled `u:object_r:custom_version_prop:s0`, which SELinux denies to
`untrusted_app`. `SystemProperties.get` returns an *empty string* on denial instead of throwing, so the read fails
silently and a LineageOS device is indistinguishable from stock — which routed every LineageOS build to an OEM
adapter (verified on oriole / LineageOS 23.2 / Android 16). `hasSystemFeature` needs no `<queries>` entry and no
permission. `lineageOsVersion` remains a **secondary identity signal** — `isLineageOs` ORs it in so derivatives that
relabel the property still match — and is normally null on real hardware; it is not "diagnostics only".

`QUALIFIED_CODENAMES` survives as the **maintainer fast path** only (still empty): a codename there is the ONLY
route to the confirmed tier, since nothing Amply can observe confirms a cap (see below). It is deliberately no
longer what makes the adapter reachable, because HAL capability is
*build*-scoped — oriole exposed the LIMIT mode bit on LineageOS 20 and dropped it on 23.2 on identical hardware —
so a bare codename entry claims more than any single qualification run proves. Add one only with a qualified device
plus a ledger row, and only when you mean every build on that device.

LineageOS is **manufacturer-agnostic** (it runs on many OEMs), so the Lineage live/lab adapters are ordered
**before all OEM adapters** in `AdapterRegistry` — a LineageOS build on Samsung/Xiaomi/OnePlus hardware must never
be swallowed by a manufacturer-based lab adapter. A build **without** the settings provider does not match the live
adapter at all and falls to `LineageLabAdapter` (diagnostics/contribution), which keeps that ordering for it.

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
That is why control is never granted on "any LineageOS device": either the maintainer proved the hardware (a ledger
row), or the user accepted an explicitly unconfirmed build that Amply keeps watching for a refutation. The adapter also
**refuses** (reads
`Unknown(unrecognizedValue=true)`) any native state it cannot restore exactly — AUTO/CUSTOM schedule modes, off-tick
limits, or an absent/malformed `enabled` — so a temporary session never clobbers the user's own choice.

## Enforcement Evidence Gate

For adapters that set `ChargingAdapter.enforcementEvidenceRequired` (LineageOS today), a settings read-back is
**not** a licence to offer control: it proves the ROM stored the value, not that the charging hardware acts on it.
`AdapterRegistry.select()` therefore takes an explicit `EnforcementEvidenceState` — **no default**, so "the caller
forgot", "not read yet" and "genuinely nothing stored" cannot collapse into control-enabled — and resolves a tier in
this order: **REFUTED** (or a corrupt record, which may be one) → control off, contribution wanted; **CONFIRMED**
(a maintainer codename, and nothing else) → control as probed; **UNVERIFIED** (the user accepted the unconfirmed
build) → control as probed, but no surface may claim the cap is proven; otherwise **CANDIDATE** → control off.
Callers that only need adapter *capabilities* pass `EnforcementEvidenceState.Loading`, which can never enable
control. A probe that **already** refused control (secondary user, missing provider) short-circuits the whole
resolution: enforcement stays null and no surface offers an opt-in that could not change anything.

The gate governs **new control only**. A restore the user is already owed — the session restore, its rollback, boot
recovery of one — goes through `ChargingRepository.restorePersistent()`, which applies every adapter precondition
but not the evidence tier: an OTA mid-session changes the build identity, and refusing the owed protective write
would strand the device in the session's Unrestricted state.

Pending recovery work is **not** automatically an owed restore, so every recovery target carries a persisted
`RecoveryOrigin` and `writeRecoveryTarget()` dispatches on it: `SESSION_RESTORE` takes the ungated path,
`USER_REQUEST` (a widget/tile persistent choice, which `setPersistentPolicy` persists *before* its write) stays on
the gated `reapplyPersistent()`. A fresh user write — `Unrestricted` included — must not reach a build the gate
refuses just because a process death turned it into recovery work. The field's default is the gated
`USER_REQUEST`, so a record from a build without it cannot bypass the gate either.

Evidence is produced by `charging/core/enforcement/`: a pure `EnforcementVerdictEngine` over the monitor's battery
ticks, persisted by `EnforcementEvidenceStore`. Three properties are load-bearing and must not be relaxed:

- **Observation can only refute, never confirm — `EnforcementVerdict` has exactly one value.** No passively
  observable signal distinguishes a cap hold from a thermal or weak-supply pause. `EXTRA_CHARGING_STATUS` == 4
  looked like one, but it is *session-scoped*: measured on a Pixel 6 / LineageOS 23.2, the extra read 4 while the
  device was actively charging at level 70 under an 80% cap — it means "limit mode is enabled for this plug
  session", exactly as `StatsLimitHitDetector`'s KDoc documents for Pixel. The only field that differs between a
  cap hold and a thermal pause is `EXTRA_STATUS`, which both produce. So Amply never claims a cap is verified from
  observation; the confirmed tier comes solely from physical qualification. Earning a real confirmation would take
  a **guided two-cap challenge** (write a cap below the current level and watch charging cut, raise it and watch it
  resume, cut again) — known, and deliberately not implemented. **REFUTE keys on an upward level trend** through
  the cap from any starting level, needs no hardware signal, and deliberately ignores the reported battery status,
  which a ROM can misreport while charging past the limit.
- Evidence is scoped to a **composite build identity** (fingerprint + incremental + build time + provider version
  code, hashed). `Build.FINGERPRINT` alone is useless here: LineageOS spoofs it to stock.
- A refutation is **terminal** for its scope, and a corrupt record is treated as a refutation. Both are fail-closed
  on purpose — the one error this gate exists to prevent is claiming protection that isn't there.

## Guided Qualification Run

`charging/core/qualification/` is the **active** counterpart to the passive gate above: instead of watching, it
drives the cap and watches the hardware answer. Behind `BuildConfig.ENABLE_QUALIFICATION_RUN` (debug/beta only
until a real device pass). `QualificationRunEngine`'s cut → resume → cut sequence is the "guided two-cap
challenge" `EnforcementVerdictEngine`'s KDoc names as the only way to earn a real confirmation.

Rules that must not be relaxed:

- **A phase timeout is `INCONCLUSIVE`, never `REFUTED`.** Refutation stays reachable only from an observed climb
  past the cap, with the same `OVERSHOOT_ALLOWANCE` the passive engine uses. A cold room or a weak charger must
  never permanently disable control on a working device.
- **A run may not refute a mapping it guessed.** On a candidate adapter (PR 2; the engine already implements it)
  the commanded value is an assumption — a One UI 6/7 device whose `protect_battery` means "cap at 85" charges
  past a commanded 80 while enforcing perfectly. That is `CAP_MISMATCH` plus the observed hold level, never a
  refutation.
- **A pass is stored in its own record**, `qualification.result.v1`, never as a constant in `EnforcementVerdict`.
  The two record different things — passive observation versus a driven experiment with a protocol version, a run
  shape and a measurement signal — and only one of them can ever be positive. (An earlier version of this note
  claimed a positive constant there would be outright fail-open via a record that lost `algorithmVersion`; that
  was overstated, since such a record decodes as version 0 and is then scoped out by the algorithm-version check.
  The separation stands on the domain difference, not on that argument.) Bumping `ALGORITHM_VERSION` instead
  would be actively unsafe: `scope()` would read every existing version-2 record as `Absent`, silently
  un-refuting every already-refuted device.
  The new record's fail-closed guard is an explicit `protocolVersion` **plus a credibility check** — a one-constant
  *positive* enum has no safe default, so a record carrying only a matching build and protocol version would
  otherwise decode as a pass with no adapter, cap, signal or exercised policies. Its `Corrupt` state means **not
  qualified** — the opposite direction from the enforcement store's, and the same principle: the unreadable state
  is the restrictive one.
- **A pass licenses only the policies the run exercised** (`AdapterSupport.licensedPolicies`), enforced at both
  the display and the write path. A run writes two policies and proves nothing about the adapter's others, which
  matters most on a candidate device where the value mapping is itself a guess.
- **Every phase is judged against the run's own baseline rate**, never an absolute threshold. A fixed
  "charging has stopped" bar is simultaneously too high for a weak supply — a phone charging steadily at 100 mA
  sits under it forever and reads as held, which is a false pass — and meaningless on a battery whose capacity or
  reporting units are unknown. Do not reintroduce one.
- **`EnforcementStatus.SELF_QUALIFIED`, not `CONFIRMED`.** `CONFIRMED` means a maintainer physically qualified the
  device and earned a ledger row; a local pass is one user's device, one build, reset by an OTA. A refutation
  still outranks it.
- **`ChargingRepository.applyForQualification` requires a run token** matching a live `QualificationRunStore`
  record. It is ungated for the same reason `restorePersistent` is, and the token is what stops that from
  becoming a general bypass. Its writes are non-persistent, so the user's protective baseline and the reconnect
  gesture's arming basis survive the run.
- **The restore is registered before the first write**, as a `FullChargeStore` recovery target with
  `RecoveryOrigin.SESSION_RESTORE`, so process death and reboot are covered by the shipped boot recovery rather
  than anything new.
- **No write-allowlist change, ever, for this feature.** Every key and value a run writes is already in
  `SettingWritePolicy` / `LineageSettingWritePolicy`. If a future adapter needs a new one, that is a boundary
  change reviewed on its own merits, not a qualification-run change.

Excluded adapters and why: `policyLatchesAtPlug` (GrapheneOS, a future HONOR adapter) — mid-run writes have no
hardware effect until a replug, so the sequence is structurally impossible; Adaptive-only adapters (Xiaomi
HyperOS 2) — `enforcementIsConditional`, nothing to challenge on demand; Pixel — already maintainer-qualified with
a real hardware signal.

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
