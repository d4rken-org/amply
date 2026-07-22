# Plan: LineageOS charging-control adapter (revised after Codex review)

## Goal
Add a control adapter for **LineageOS's native Charging Control** so Amply can read and set the charge
limit on **qualified** LineageOS devices, integrated with Amply's session/widget/tile. Ships **disabled
(lab/diagnostics only) until physical qualification** on the prepared Pixel 6 + LineageOS.

## Confirmed facts (source-verified)
- Separate provider: authority `lineagesettings`, System table `content://lineagesettings/system`.
  Keys: `charging_control_enabled` (0/1), `charging_control_mode` (1=AUTO,2=CUSTOM,3=LIMIT),
  `charging_control_charging_limit` (70..100), `charging_control_start_time`, `charging_control_target_time`.
- Hard percent cap = `enabled=1` + `mode=3` + `charging_control_charging_limit=N`.
- Reads unguarded (world-readable provider). Writes need `lineageos.permission.WRITE_SETTINGS`, held by the
  ADB shell UID → **writable via Shizuku only**, no root. Provider validates writes (mode 1..3, limit 70..100).
- Lineage's `ChargingControlController` observes the keys and re-drives `vendor.lineage.health.IChargingControl`.
- **Top risk:** HAL device-dependence — the setting can flip while the HAL never limits (`mIsLimitSet:false`).
  Setting readback does NOT prove hardware enforcement; only on-device qualification does.

## Scope decisions (v1)
- **Ships lab-only until qualified.** Live control gated to a **qualified-codename allowlist** (empty until the
  Pixel 6 passes) + `lineageOsVersion != null` + provider present + system user. All other Lineage builds →
  new `LineageLabAdapter` (diagnostics + contribution). No "verified device" ledger row until qualification.
- **Recognize only exactly-restorable states.** `read()` returns `Verified` only for states v1 can restore
  precisely (the supported limit set + Unrestricted). Native AUTO/CUSTOM, any unsupported limit, or an ambiguous
  absent key → `Unknown(unrecognizedValue=true)`, so a session **refuses rather than clobbers**. Absent-key
  meaning is characterized on-device, not guessed.
- **Writes require Shizuku** (`preferShizukuForWrites = true`); direct ContentResolver writes → `false`.
- **Reads are unprivileged** — via a shared `LineageSettingsClient` (ContentResolver), same values on either backend.
- **Verification = SYNC_READBACK** (readback equality of the configured trio). Boot-recovery convergence is a
  **qualification gate** (see §5): confirm Lineage's controller consumes persisted settings at boot and reacts to
  external changes; if a race appears, add delayed-rewrite/async handling before shipping. (No repository
  verification-flow change; this does not touch `computeRefreshPending`.)
- **Reconnect gesture:** unsupported.
- **Limit set:** decided by the open question below (80-only vs discrete 70..95 range). Plan is written parametric
  on `SUPPORTED_LIMITS`.

## Changes

### 1. Access primitive
- `SettingNamespace`: add `LINEAGE_SYSTEM`. **Also** replace `SettingNamespace.entries` in
  `DefaultContributionRepository` with an explicit `STANDARD_SETTINGS_NAMESPACES = [SECURE, GLOBAL, SYSTEM]`
  so the contribution wizard never queries the Lineage provider (would fail capture on stock devices).
  + regression test asserting stock capture requests exactly those three.
- AIDL `IChargingControlService`: add **only** `boolean writeLineageSetting(String key, String value) = 5;`
  (constant `/system/bin/content insert`, constant URI, argv-separated, no shell string). No read/snapshot AIDL.
- `ChargingControlUserService`: implement `writeLineageSetting` via `runBoundedProcess` with
  `content insert --uri content://lineagesettings/system --bind name:s:<key> --bind value:s:<val>`.
  New `LineageSettingWritePolicy`: read+write **key allowlist** = the 3 control keys; write domains
  enabled∈{"0","1"}, mode∈{"3"}, limit∈`SUPPORTED_LIMITS` as canonical strings; reject leading-zero forms.
- `LineageSettingsClient` (new, app-process): `ContentResolver.query(content://lineagesettings/system,
  projection=[name,value], selection="name=?", selectionArgs=[key])`, `use{}` cursor, validate cardinality/
  columns, distinguish **absent** (no row) from **failure** (SecurityException/null cursor). One query returns
  all three keys to avoid a torn read between separate queries.
- `ShizukuSettingsBackend`: `LINEAGE_SYSTEM` read → `LineageSettingsClient`; write → `writeLineageSetting`.
  Wrap **all** blocking user-service calls (existing `read`/`write` too, not only `snapshot`) in
  `withContext(Dispatchers.IO)`; rethrow `CancellationException` instead of swallowing via broad `runCatching`.
- `DirectSettingsBackend`: `LINEAGE_SYSTEM` read → `LineageSettingsClient`; write → `false`.

### 2. Detection
- `LineageOsDetector`: `ro.lineage.build.version` via `SystemPropertyReader` → `String?`.
- `DeviceInfo`: `lineageOsVersion: String?`, `hasLineageSettingsProvider: Boolean`
  (`packageManager.resolveContentProvider("lineagesettings", 0) != null`, fail-closed).
- Manifest `<queries><provider android:authorities="lineagesettings"/></queries>` (+ the Lineage
  charging-control deep-link intent) so package-visibility doesn't false-negative resolution.

### 3. Adapters
- `LineageChargingAdapter` (`id = "lineageos-chargingcontrol-v1"`):
  - `probe`: `matched = lineageOsVersion != null && codename in QUALIFIED_CODENAMES`;
    `controlEnabled = matched && hasLineageSettingsProvider && isSystemUser`;
    `contributionWanted = false` (unqualified Lineage is handled by the lab adapter).
  - `verification = SYNC_READBACK`, `preferShizukuForWrites = true`.
  - `supportedPolicies = SUPPORTED_LIMITS.map(::FixedLimit) + Unrestricted`;
    `defaultProtectivePolicy = FixedLimit(80)`; `sessionOverridePolicy = Unrestricted`.
  - `read`: decode trio; `enabled=0`→`Unrestricted`; `enabled=1 & mode=3 & limit∈SUPPORTED_LIMITS`→
    `FixedLimit(limit)` (guard `FixedLimit` 50..100 with range check); every other enabled state (mode 1/2,
    unsupported limit, malformed) → `Unknown(unrecognizedValue=true)`; unreadable → `Unknown` (not flagged).
  - `mutationsFor(FixedLimit p)` = limit→mode→enabled (`p`, `3`, `1`); `Unrestricted` = enabled `0`.
    `apply` writes then read-back-verifies (Samsung/Oplus pattern).
  - `observedSettingUris` = table URI + per-key URIs for enabled/mode/limit (register both forms; confirm which
    the provider notifies at qualification).
  - `nativeSettingsIntent`: Lineage charging-control deep link, fallback `ACTION_BATTERY_SAVER_SETTINGS`.
- `LineageLabAdapter` (`DisabledLabAdapter`, `id = "lineageos-lab"`): `matches = lineageOsVersion != null`
  (diagnostics/contribution for unqualified Lineage builds).
- `AdapterRegistry`: prepend `lineage, lineageLab` **before all OEM adapters**.

### 4. Least privilege
- `AutoWssGrantCoordinator`: skip the WSS auto-grant when the selected adapter `preferShizukuForWrites`
  (WSS can't write these providers). Fixes Lineage and incidentally Oplus. + policy/flow tests.

### 5. Verification / boot recovery (qualification-gated)
- Keep `SYNC_READBACK`. During qualification, physically confirm: setting persists across reboot, Lineage's
  controller re-drives the HAL after a cold boot, and an external mid-session change is observed. If boot testing
  reveals an observer-startup race, add a delayed-rewrite/async-configured strategy before enabling. `mIsLimitSet`
  is a qualification signal only (below the limit `false` is expected), correlated with real charging current/
  status at and above threshold — not a runtime apply check.

### 6. Strings / docs / reporting
- Strings: `adapter_name_lineageos`, `adapter_detail_requires_lineageos`,
  `adapter_detail_lineageos_no_provider`, `adapter_detail_lineageos_ready`, reuse `secondary_user`.
- Device-support report: include `lineageOsVersion` + `hasLineageSettingsProvider` (light schema add).
- Onboarding/README: correct the "ADB/WSS **or** Shizuku" copy — this adapter (like Oplus) needs Shizuku for writes.
- Docs: `.claude/rules/privileged-access.md` (gate + write-policy keys + known gap; ledger row only after
  qualification), `.claude/rules/architecture.md` (adapter section), `.claude/CLAUDE.md` (adapter summary).

### 7. Tests (JUnit5 + Kotest)
- `LineageOsDetector.parse`.
- Adapter `read` mapping: Verified only for restorable states; AUTO/CUSTOM/unsupported-limit/malformed →
  `Unknown(unrecognizedValue=true)`; absent-key behavior; unreadable → `Unknown` unflagged.
- `mutationsFor` ordering (limit→mode→enabled) + partial-failure/readback-mismatch fails apply.
- `LineageSettingWritePolicy` domains: reject mode `1`, limit `101`, leading-zero `080`, non-allowlisted key.
- `probe` matrix: Lineage builds reporting Google/Samsung/Xiaomi/OnePlus/Oppo/realme + stock (property absent);
  qualified vs non-qualified codename; provider absent; secondary user.
- `AdapterRegistry` precedence: Lineage adapters win over OEM lab adapters on Lineage builds.
- Contribution capture requests exactly `[SECURE, GLOBAL, SYSTEM]` on a stock device (regression).
- `AutoWssGrant` skips WSS for a `preferShizukuForWrites` adapter.

### 8. Qualification (Pixel 6 + LineageOS, device pending)
Full ledger protocol incl.: characterize absent keys; native 70/80/85/100 + AUTO/CUSTOM **refusal without
mutation**; provider notification URI forms; **`dumpsys lineagehealth mIsLimitSet` + real charging cessation**
wired/wireless below/at/above threshold; access tiers; shell-permission denial; binder death; rootless-Shizuku
reboot recovery; sessions/boot recovery; R8 `foss` beta; both flavor unit suites + both debug assembles/lints.

## Out of scope
- Extending the contribution wizard to *capture* `lineagesettings` (only the regression guard is in scope).
- Runtime `dumpsys` verification; schedule (AUTO/CUSTOM) modes; reconnect gesture.

## Risks
1. HAL absent/broken → write succeeds, no limit. Mitigated by qualified-codename gate + known gap.
2. Lineage fork strips shell-UID write grant → write fails; caught by read-back verification.
3. Provider notification URI form varies → register table + per-key; confirm at qualification.
