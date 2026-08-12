---
name: device-qualification
description: Amply's physical qualification protocol for OEM charge-control adapters, the ledger of verified devices (Pixel/Samsung/Xiaomi/OnePlus), and known gaps per OEM. Use when qualifying a new device or ROM, widening a capability gate, adding a codename to an allowlist, or recording device test results.
---

# Device Qualification & Ledger

Companion to `.claude/rules/privileged-access.md`, which holds the always-loaded safety boundary and the capability
gates themselves. **Never widen a gate without adding a row to "Verified devices" below.**

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
   without mutating the setting. Also confirm the interrupted-session surface both ways: a **force-stop that leaves a
   restore owed** must, on next open, restore the limit *and* show the dashboard interruption card, while a **reboot**
   must restore via boot recovery and show **no** card (the boot-count guard). A device that holds at its limit often
   reports `NOT_CHARGING`, which ends a session immediately — drive the lifecycle with `adb shell dumpsys battery set
   status 2 / set level N` (then `dumpsys battery reset`) so the session behaves as it would mid-charge.
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
| Pixel | Pixel 8 `shiba` A17/API37; Pixel 9 Pro `caiman` A16/API36; Pixel 7a `lynx` A16/API36 | Full — sysfs `charging_policy` follows writes (~11–12s) | Access tiers, sessions, boot recovery, wireless hold, at-threshold, reconnect gesture, natural 100%, interrupted-session detection (7a) | 2026-07-15/-19/-20/-25 |
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
  - **Pixel 6 (oriole) on LineageOS 23.2 / Android 16 — retested 2026-07-22 after an anti-rollback firmware bump,
    result NO-GO (HAL no longer offers LIMIT mode at all).** A *sharper* root cause than the LOS 20 run: the
    `vendor.lineage.health.IChargingControl` HAL reports `getSupportedMode()` **without the LIMIT bit**, so
    `ChargingControlController.isChargingModeSupported(LIMIT)` is false and LineageOS **coerces `charging_control_mode=3`
    → `1` (AUTO)** — verified live: every raw shell-UID `content insert` of `mode=3` (even while `enabled=0`) read back
    as `1`. Enabling then logs `LineageHealth: No alarm found, auto charging control has no effect` and `Setting charge
    deadline: … 73353`; the active provider is `ccprovider.Deadline` (Google Adaptive Charging — time/alarm-based, **no
    fixed-percent cap**). `charging_policy`/`charge_stage` sysfs never changed. So oriole is NO-GO on **both** builds for
    different reasons: LOS 20 *accepted* LIMIT but the HAL silently didn't cut; LOS 23.2's HAL dropped LIMIT and falls
    back to the Deadline mechanism. Consequence for the adapter (unchanged — correct by design): SYNC_READBACK
    `apply(FixedLimit)` writes `mode=3`, reads back `mode=1 ≠ 3` → decodes `Unknown(unrecognizedValue=true)` → `apply`
    returns false, so it **refuses without a false claim of control**. Reinforces the allowlist bar: a device must both
    expose the LIMIT mode bit **and** actually cut charging. `charging_control_*` again **absent in factory state**.
  - **Pixel 6 (oriole) on LineageOS 23.2-20260720-NIGHTLY / Android 16 — app-level compatibility pass 2026-08-03.**
    HAL qualification **not re-run** (same build as the run above, verdict stands). This pass found a defect that made
    the whole Lineage path unreachable in production: **Amply never detected LineageOS at all.** All five
    `ro.lineage.*` properties are labelled `u:object_r:custom_version_prop:s0`, which SELinux denies to
    `untrusted_app` (`avc: denied { read } … tcontext=custom_version_prop … app=eu.darken.amply`).
    `SystemProperties.get` returns `""` on denial instead of throwing, so `SystemPropertyReader`'s `runCatching`
    never fired, nothing was logged, and `LineageOsDetector.detect()` silently returned null — `getprop` over adb had
    always worked because it runs as `shell`. Both Lineage adapters skipped and selection fell through to
    `google-pixel-lab-v1`, which (a) made `QUALIFIED_CODENAMES` dead — a qualified codename could never activate,
    (b) hid the "Help add support" wizard (`contributionWanted` defaults false on the Pixel adapter, true on the lab
    adapter), and (c) pointed "open battery settings" at Battery Saver, since the Pixel intent targets the absent
    Google Settings-Intelligence component and never tries `POWER_USAGE_SUMMARY` (which *does* resolve on LOS).
    Fixed by gating on the app-readable `org.lineageos.android` system feature (`DeviceInfo.isLineageOs`); the
    version property is kept as a **secondary identity signal** (OR-ed in, normally null on real hardware). The
    same run added a `dumpsys lineagehealth` probe to the device-support report. **It is an observation, never a
    verdict, and never qualifies a device.** Two reasons: selection is mode-dependent (upstream picks Deadline
    before Limit for `MODE_AUTO`/`MODE_MANUAL`, so this device's `Provider: Deadline` at `Mode: 1` merely means
    nothing was learned), and there is no negative case at all — `Toggle` also accepts `MODE_LIMIT` and enforces
    the cap itself, so binding it is a capable mechanism rather than a rejection. Even `NATIVE_LIMIT` proves
    nothing: oriole bound `Limit` on LOS 20 and still charged past the cap. Only physical observation of the
    charge current qualifies a device. Oriole's NO-GO rests on the `mode=3` write reading back as `1` plus the
    LOS 20 charge-past observation — both separate evidence from this probe. Note LineageOS **spoofs `Build.FINGERPRINT`** to stock
    (`google/oriole/oriole:16/…/release-keys`), so fingerprint sniffing is not a fallback. Otherwise clean on this
    ROM: install/launch/onboarding/dashboard/settings with no crashes, honest "Unsupported device" reporting, live
    battery monitoring across simulated plug/level transitions, and the charge alarm firing at threshold.
- **Xiaomi** — adaptive hardware enforcement of external writes unconfirmed; treat the adapter as provisional until
  the 80% hold is physically observed.
  - **HyperOS 3 candidate mapping (contribution report, 2026-08-07 — unqualified, stays diagnostics-only).** A
    Redmi Note 14 `24117RN76G` (`tanzanite`, Android 16 / SDK 36, `ro.mi.os.version.code=3`, ROM
    `3.0.302.0.WOGMIXM.C08`) reported via the contribution wizard that the **same key**
    `secure/security_pc_secure_protect_mode_key` now carries **three** modes: `0` = Charge fully and
    `1` = Intelligent charging (labels matching HyperOS 2), plus a new `2` = **Battery protection** — apparently a
    hard-cap mode, which HyperOS 2 lacks entirely. The device correctly fell through to `XiaomiLabAdapter`, and the
    existing decode treats `2` as `Unknown(unrecognizedValue=true)` (refuse-don't-clobber — the test that pins this
    was written as a garbage-value guard; `2` is now known to be a real, named OEM mode). This is a settings mapping
    only: **no behavioral evidence** (the optional effect prompts were skipped), the cap percentage is unknown
    (modeling mode `2` needs a concrete `FixedLimit` percent), factory/absent-key semantics on HyperOS 3 are
    unknown, and whether value `2` is HyperOS-3-wide or model-specific is unconfirmed. A full qualification would
    also need the boundary write domain widened from `{"0","1"}` (`ChargingControlUserService`). The contributor
    has a working Shizuku setup (clean three-namespace, three-mode capture) — a strong candidate for a follow-up
    qualification run; the report thread is in support mail ("Amply device-support discovery", 2026-08-07).
- **Pixel** — wireless at-threshold hold/charge-past and the widget under Shizuku-only remain unexercised (both share
  the verified wired mechanism).
