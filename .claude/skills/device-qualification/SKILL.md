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

## The in-app guided run is not a ledger row

`charging/core/qualification/` lets a user run the cut → resume → cut challenge on their own device. A pass
records `EnforcementStatus.SELF_QUALIFIED`, which enables control **for that user, on that exact build**, and
produces a report (`qualification_schema=1`) carrying `Build.DEVICE` — the codename an allowlist entry needs and
the field the contribution wizard omits.

**A self-qualified pass never becomes a "Verified devices" row on its own, and never writes into a
`QUALIFIED_CODENAMES` allowlist.** It is one run, on one unit, usually unattended, with no wireless leg, no
access-tier matrix, no session/boot-recovery coverage and no R8 build — i.e. step 2 of the protocol partially, and
none of steps 3-5. What it *is* good for is the thing that used to take days of email: it settles the
does-the-hardware-obey question with a machine-collected, timestamped result instead of contributor prose, so a
maintainer row becomes a matter of covering the remaining steps rather than starting from nothing.

Treat an incoming qualification report the way the `tanzanite` enforcement evidence was treated: strong evidence
for one question, explicitly scoped, recorded with its gaps named.

## Verified devices (physically tested)

The gate's supported *scope* (see Capability Gates) is broader than what has been physically tested below. Widen a gate
only after adding a row here. Detailed run narratives live in each adapter's landing commit.

| OEM | Tested device / build | Hardware evidence | Coverage | Landed |
|---|---|---|---|---|
| Pixel | Pixel 8 `shiba` A17/API37; Pixel 9 Pro `caiman` A16/API36; Pixel 7a `lynx` A16/API36 | Full — sysfs `charging_policy` follows writes (~11–12s) | Access tiers, sessions, boot recovery, wireless hold, at-threshold, reconnect gesture, natural 100%, interrupted-session detection (7a) | 2026-07-15/-19/-20/-25 |
| Samsung | Galaxy Tab A9+ SM-X210 One UI 8.0; Galaxy S20 FE SM-G781B One UI 4.1 | Full — sync readback + HAL enforcement | Modern multi-mode + legacy toggle, session E2E, native-change cancel, reboot recovery, R8 beta | 2026-07-21 |
| Xiaomi | Xiaomi 13T `2306EPN60G` (`aristotle`) HyperOS 2.0 (`ro.mi.os.version.code=2`); 2026-08-16 re-run on `OS2.0.216.0.VMFEUXM` / Android 15 | **Partial, now characterized** — external shell-UID writes provably drive the daemon identically to native UI taps (`getProtectMode` → `checkUiModeProtect` → `setEnable`, ~80 ms, both directions, no hidden UI-only flag). The adaptive 80% hold is still **unobserved**: charged 59→100% with no plateau, `getNightChargingState` = 0 on all 140 evaluations. Cause identified — the gate is a *learned* charging-routine model (`key_ave_night_charge_start_minutes`, `…_sd`, `key_enter_night_charge_times`), not a clock window (forced clock to 02:30 did not open it). **BLOCKED on an unused test device, not FAILED.** No hardware hold signal (`Charging state`/`policy` = 0/0) | Read matrix, both-direction writes, session at 100%, unknown-value refusal, R8 beta (2026-07-21). Added 2026-08-16: external-vs-UI write-path equivalence, native UI reflects external key, learned-schedule root cause, clock-forcing negative | 2026-07-21 / 2026-08-16 |
| OnePlus (Oplus) | OnePlus Nord CE4 Lite `CPH2621` ColorOS 15 (`ro.build.version.oplusrom=V15.0.0`) | Full — enforcement directly observable (device holds at 80%); external writes stick | Two mutually-exclusive `system` keys (Charging limit / Smart charging), WSS-only write rejected + Shizuku write succeeds for all three policies, WSS-only UX (controls disabled + Shizuku-required banner) | 2026-07-21 |
| GrapheneOS | Pixel 9 Pro XL `komodo`, GrapheneOS 2026080501 / Android 17 — **REMOTE qualification via issue #49** (tester-run protocol, not maintainer hardware) | **Enforcement observed**: held at 80% with shield, `dumpsys battery` status=4/Charging state=4/policy=2 (limit on) vs 2/1/1 (off); shell-UID writes move the Settings UI live, **latch at plug-session start** — mid-session writes have no hardware effect until unplug→replug, and on this device/build the replug applied the written value in both directions. **The replug half does not generalize**: refuted on `frankel` (Pixel 10, 2026081301) — see the known-gaps entry | Key isolation (`settings list` diff → single `global battery_charge_limit` 0/1), write→UI both directions, mid-session no-op both directions, replug latch both directions, hardware signal both states. **NOT run**: app-context access tiers (WSS write from Amply, `app.grapheneos.*` package visibility), sessions/boot recovery, wireless, factory-absent key state, secondary user | 2026-08-12 |
| Xiaomi (HyperOS 3) | Redmi Note 14 `24117RN76G` (`tanzanite`), HyperOS 3.0.302 / Android 16 (`ro.mi.os.version.code=3`) — **REMOTE qualification via issue #48** (contributor-run protocol, not maintainer hardware) | **Both-direction enforcement of EXTERNAL shell-UID writes observed** (the same write path as Amply's Shizuku service): `settings put … 2` below the cap → Battery protection active, Settings UI follows immediately, held at 80% for ~20 min under active use (voltage 4228 mV holding vs 4391 mV charging, charge counter 4283 vs 4341 corroborate; sysfs `current_now` permission-denied, so no current reading); `settings put … 0` mid-hold → charging resumes past 80 immediately. **No hardware hold signal**: `dumpsys battery` reports `status: 2` / `Charging state: 0` / `Charging policy: 0` in both states → read-back-only verification | Key mapping (three modes incl. `2` = Battery protection @80, cap fixed — no percent picker), external write → UI both directions, sustained hold, mid-hold release. Beta run 2026-08-14 added: app-context three-mode control (direct WSS), factory-absent key = Intelligent (confirmed). Session restore FAILED in that run (observer noise cancel — app bug, fixed in #65). Re-verified 2026-08-16 on v0.3.4-beta0: **session restore PASSES** — full-charge session ran to 100% and Battery protection was re-written automatically while still plugged. **NOT run**: Shizuku tier, boot recovery, wireless, R8, unplug-early restore | 2026-08-16 |
| GrapheneOS (follow-up) | Same device, **0.3.2-beta0 on-device report via issue #49** | **Package detection VERIFIED from app context** (`is_grapheneos=true` with the FLAG_SYSTEM check); **unprivileged key read DENIED** — `has_battery_charge_limit=false` while the very same report showed `battery_charging_status=4` (limit enforcing). Root cause in GrapheneOS source: the key is `@Protected(read = SYSTEM_UI, readWrite = SETTINGS)` (frameworks_base `c30c6393`); SettingsProvider throws SecurityException for all other packages **including WSS holders**, with the shell UID explicitly exempt ("ADB is used for testing", `e87c93a2`) — so the tester's earlier adb runs ARE the Shizuku-path evidence. Factory-absent semantics resolved from source: `BoolSetting(..., default false)` → absent = off | Detection + fail-closed probe verified live; adapter re-gated to Shizuku-only in response | 2026-08-13 |
| GrapheneOS (2nd device) | Pixel 10 `frankel`, GrapheneOS 2026081301 / Android 17, **Amply 0.5.0-beta0 (foss/beta, R8-minified) on-device report via issue #49** | **No new hardware observation** — the reporter states the app works with Shizuku granted, but reported neither an observed 80% hold nor a session run, so this row adds no enforcement evidence. The `komodo` enforcement observation above remains the only one | **Amply's own Shizuku path verified on hardware for the first time**: with Shizuku set up the app reads the key and offers the control, where the 0.3.2-beta0 run without Shizuku showed "setting not present". Second device, and the first on a Pixel generation other than the 9 Pro XL all prior work used. R8 smoke clean (the `beta` build type minifies and shrinks resources), and `ReconnectSupport.NONE` renders correctly as an unavailable gesture rather than a dead control. **NOT run**: observed 80% hold through the app, full-charge session + restore, boot recovery, wireless, secondary user | 2026-08-23 |

## Known gaps

- **LineageOS** — the live adapter now **matches every LineageOS build that ships the `lineagesettings` provider**;
  `QUALIFIED_CODENAMES` still ships **empty** but is only a maintainer fast path, no longer what makes the adapter
  reachable. Control is gated on **per-build enforcement evidence** instead: a device is a candidate with controls
  off until the user explicitly enables control on an unconfirmed build, and loses control permanently for that
  build if the battery is observed charging past the cap. A codename still goes into `QUALIFIED_CODENAMES` only
  after full qualification (real charging cessation at the limit, wired + wireless, below/at/above threshold) plus
  a "Verified devices" row — that list now buys skipping the opt-in, not access itself.
  - **Observation can only refute, never confirm** (established 2026-08-17, see the oriole re-observation below):
    no passively observable signal distinguishes a cap hold from a thermal or weak-supply pause. Both present as
    plugged + `BATTERY_STATUS_NOT_CHARGING` + a static level. `EXTRA_CHARGING_STATUS` does **not** break the tie —
    it is session-scoped, measured still reading `4` while the device charged ten points *below* its cap. A guided
    two-cap cut/resume/cut challenge (raise the cap, verify charging resumes and re-stops at the new threshold) is
    the known way to earn real confirmation and is deliberately not implemented.
  - **Pixel 6 (oriole) on LineageOS 23.2 / Android build `BP4A.251205.006` — re-observed 2026-08-17. The
    2026-07-22 LOS 23.2 NO-GO below does NOT hold on this build.** `dumpsys lineagehealth` binds
    `ccprovider.Limit` (so the HAL *does* advertise the LIMIT mode bit again), `charging_control_mode` reads back
    as `3` and is **not** coerced to `1`, and with `enabled=1 / mode=3 / limit=70` the device sat at exactly 70 %
    on AC for ~4 h: `status: 4` (NOT_CHARGING), `Charging state: 4`, `Charging policy: 1`, voltage 4072 mV.
    **Causal check**: raising `charging_control_charging_limit` to `80` resumed charging within 20 s —
    `status: 2`, voltage 4072 → 4183 mV, temperature 294 → 311, charge counter 2908000 → 2910000 — i.e. the
    setting demonstrably drives the charging hardware, which is stronger evidence than an observed plateau.
    The limit was restored to `70` and re-verified by read-back; `enabled`/`mode` were never written.
    **This is NOT a GO and oriole is NOT in `QUALIFIED_CODENAMES`**: only part of step 2 was run — wired only, no
    wireless, no below/at/above sweep, no hold observed at the raised cap, and none of steps 3-5 (access tiers,
    sessions, boot recovery, R8). It supersedes the "HAL dropped LIMIT" claim for *this build only*.
    **Unexplained later observation, recorded so this row does not overclaim**: a few hours after the run above,
    the device was found at **78 %** with `charging_control_charging_limit` still reading `70` — i.e. a level
    above the cap. It is **not attributable** and must not be read either as enforcement failing or as anything
    else: by then the phone had left this run's control entirely — physically unplugged and moved, and its
    system clock force-set to `Tue Jul 21 02:01 CEST` (a month in the past, at 02:00) by another workflow, which
    is a scheduled/night-charge experiment signature. Any of that could produce the rise. The controlled
    observations above stand as recorded; this one is logged only so a later reader does not find it and
    conclude the row was written selectively. Re-check under controlled conditions before treating either as
    settled.
    **Why it matters beyond oriole**: same device, same Lineage major version, different build, opposite HAL
    capability. That is the concrete case for HAL capability being **build-scoped, not codename-scoped**, and it
    is why enforcement evidence is keyed on a composite build identity (fingerprint + incremental + build time +
    provider package version) rather than a codename — LineageOS spoofs `Build.FINGERPRINT` to stock, so the
    fingerprint alone cannot carry it.
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
- **GrapheneOS** — landed **live** on remote qualification (issue #49; the only OEM row not tested on maintainer
  hardware), then **re-gated to Shizuku-only** after the 0.3.2-beta0 on-device report (see the follow-up ledger
  row). Resolved since the landing PR: package visibility ✔ verified from app context; app-context WSS access ✘
  resolved as DENIED (`@Protected` — the reason for the re-gate, not a bug in Amply); factory-absent key state ✔
  resolved from upstream source (`BoolSetting` default false → absent decodes as Unrestricted). Still open, all
  failing closed or cosmetic:
  - **Shizuku read path verified, session behaviour still not** — the 0.5.0-beta0 report on `frankel` (see the
    2nd-device ledger row) shows Amply's own Shizuku service reading the key and offering the control on real
    hardware, under R8, so those two are closed. What that report does *not* cover, because the reporter did not
    run them: an 80% hold observed through the app rather than over adb, a full-charge session and its restore,
    and boot recovery. Every enforcement claim on this ROM still rests on the `komodo` adb runs, and one of them
    (the replug latch) is refuted on `frankel` — see the next entry.
  - **External writes are not honoured on every build — REFUTED on `frankel`** (Pixel 10, GrapheneOS 2026081301 /
    Android 17; issue #49, 2026-08-25). With Amply out of the loop entirely, a bare
    `settings put global battery_charge_limit 0` made while unplugged, and confirmed off in the ROM's own Settings
    UI, was still ignored at the next plug-in: the device held at exactly 80% (level climbed 74→80, then
    NOT_CHARGING at 80 with power connected, no level above 80 all day, thermals ~33 °C so not a throttle; the
    app's package appears nowhere in the 52k-line capture). The same device's **native Settings toggle works
    normally**, so the divergence is the external-write channel, not the cap. Cause **undetermined**: `komodo` was
    qualified on 2026080501 and `frankel` observed on 2026081301, neither device tested on the other's build, so a
    ROM release change and a Pixel 10 generation difference fit the evidence equally. No public upstream report
    matches this direction (the known ones are the inverse — charging past the cap while the limit is on). One
    caveat on the evidence: the capture carries no settings-provider logging, so "the key read 0 at plug time"
    rests on the reporter's statement plus the ROM's UI, not on instrumentation.
    - **Blast radius**: every write on this adapter shares one path, so on a device behaving like this the
      dashboard/widget/tile persistent writes, session restore, and boot recovery are all hardware-level no-ops
      that read back as verified. Boot recovery's convergence loop returns DONE on the first tick for the same
      reason (a matching settings readback is treated as convergence). Nothing in the app can notice: this adapter
      does not set `enforcementEvidenceRequired`, and the passive verdict engine only detects a climb *past* a
      cap, which is the opposite direction from a ROM enforcing a cap that is configured off.
    - **The protective direction is UNTESTED, and it is the open safety question.** Both observed failures wrote
      the limit *off* while the ROM kept enforcing, which leaves the battery protected and merely makes the UI
      dishonest. Whether a write turning the limit *on* is equally ignored — which would mean Amply claims
      protection that is not enforced — has never been run by anyone. Asked of the reporter 2026-08-25. Do not
      widen, re-qualify, or relax anything on this ROM until it is answered.
  - **State 4 below the limit unverified** — evidence was sampled at the 80% hold; if the ROM reports 4 only while
    holding, a FixedLimit pending clears late (at the hold) instead of instantly. Cosmetic.
  - **A plugged restore configures but cannot enforce** — restore-at-100%, the 24h safety timeout,
    manual restore, and a plugged boot recovery all write the protective value while a plug session
    is running; the ROM won't enforce it until the next replug, and no code path can change that
    (mid-session writes are ignored by design). Amply's state is correct — config protective,
    session/recovery closed, pending-until-replug hint shown — and the exposure is one charge cycle,
    bounded by the plug session the user is already in. Deliberately NOT treated as a defect.
  - **Wireless charging and secondary users**: NOT RUN (gated to system user).
- **Oplus (OnePlus/Oppo/Realme)** — the live gate reads `ro.build.version.oplusrom`, which **does not exist on
  pre-rebrand ColorOS**, so every ColorOS 11-era build is invisible to it and lands on `OnePlusLabAdapter`. This is
  correct behaviour (those builds are unqualified either way), but it made reports from them uninformative.
  - **Oppo F11 Pro `CPH1969` (`OP4863`), Android 11 / ColorOS 11 — device-support report only, 2026-08-15. NOT a
    qualification run: no physical test, no Shizuku, no settings capture.** The report carried
    `oplus_rom_version=none` and nothing else about the family, because the report probed only the Samsung,
    GrapheneOS, and LineageOS keys — the two ColorOS keys Amply already knows were never read. Absence of the
    property could not be distinguished from an SELinux-denied read either (`SystemPropertyReader` returns `""` on
    denial). Prompted two report changes: an unprivileged presence probe of the two `system` keys, and tri-state
    probe results (`present|absent|read_denied`) so a refused read stops rendering as a proven negative. **Still
    open:** whether pre-rebrand ColorOS carries those keys under any name is unknown, and no legacy property
    (`ro.build.version.opporom`) is read, so pre-ColorOS-12 builds remain undetectable as Oplus by ROM version. A
    device with a *working* pre-15 ColorOS charge-protection feature would need a new gate signal, not a widened
    version constant — and the usual bar applies: physically observed charging cessation, not a settings mapping.
- **Xiaomi** — **external-write handling RESOLVED (2026-08-16); the adaptive 80% hold remains unobserved and is
  now believed UNQUALIFIABLE on an unused test device.** Split the old "adaptive enforcement unconfirmed" gap into
  its two halves — one is now closed, the other is characterized rather than merely open.
  - **Closed: external shell-UID writes are functionally identical to native UI taps** (Xiaomi 13T `aristotle`,
    HyperOS 2 `OS2.0.216.0.VMFEUXM`, Android 15, maintainer hardware). Both paths produce the same daemon chain
    with the same values, within ~80 ms of the write:
    `ChargeProtectionUtils: getProtectMode mode:N` → `SmartChargeProtectManager: checkUiModeProtect:N` →
    `BaseChargeProtect_: MODE_NIGHT,setEnable fromShouldWork:false,to:false,enable:{true|false}`.
    Verified in both directions and both origins (UI tap to Charge fully / Intelligent, external
    `settings put … 0` / `… 1`). **There is no hidden internal flag that only the Settings UI sets** — an
    explicitly tested hypothesis, refuted. The native UI also reflects an externally-written key on next open.
    So Amply's write mechanism is complete and correct on HyperOS 2; nothing in the adapter needs changing for
    the write path.
  - **Still unobserved: the hold itself.** With the key at `1` (Intelligent, written externally), the device
    charged **59% → 100% continuously with no plateau at any level** — uniform ~4 min per point, voltage and
    charge counter rising monotonically through 80 (4265→4293 mV, counter 3390000→3493000), `status: 2`
    throughout. `getNightChargingState` was evaluated **140 times across the run and returned `0` every time**
    (60 s cadence while charging). No hardware hold signal exists — `Charging state: 0` / `Charging policy: 0`
    in all states, same as HyperOS 3.
  - **Root cause of the non-engagement: the gate is a LEARNED schedule, not a clock window.** Strings in
    `com.miui.securitycenter` (`com.miui.powercenter.nightcharge`) show the feature keeps a statistical model of
    habitual overnight charging: `key_ave_night_charge_start_minutes`, `key_night_charge_start_minutes_sd`,
    `key_night_charge_end_minutes_sd` (standard deviations), `key_enter_night_charge_times` (occurrence count),
    `key_earliest_night_charge_end_minutes`, `key_night_charge_record`, plus four distinct
    `isNeedNightChargeProtection return false case 1..4` rejection paths. Directly corroborated: forcing the
    device clock to 02:30 (via `cmd time_detector set_time_state_for_tests`; shell UID lacks both `SET_TIME` and
    `SUGGEST_MANUAL_TIME_AND_ZONE`) did **not** open the window — `isNightChargeProtectionOpen` stayed `false`.
    Time of day alone is insufficient; the daemon wants a low-variance charging history the device does not have.
  - **Consequence for qualification: this device cannot settle it.** The 13T is a maintainer *test* device with no
    normal daily use, so it can never accumulate the routine the model requires. Qualifying Adaptive would need
    either weeks of genuine (or convincingly simulated) nightly charging at a consistent time, or root to seed
    SecurityCenter's private prefs. Record the July "could not be triggered" and this run as the **same** result
    with a now-known cause, not as two independent failures. **Adaptive on Xiaomi is therefore BLOCKED, not
    FAILED** — no evidence exists that it fails to hold, only that its precondition never became true.
  - **Consequence for the adapter, RESOLVED in the same change** (see `XiaomiChargingAdapter.kt`,
    `defaultProtectivePolicy = Adaptive`): Amply's protective policy on HyperOS 2 is a mode that, by the OEM's own
    design, only acts inside a learned overnight window, so a device with Adaptive configured charges to 100%
    outside it. The read was never *wrong* (the mode genuinely is configured) but "protected" overstated it. The
    default stays — HyperOS 2 offers no unconditional protective mode — and the honesty moved to presentation:
    `ChargePolicy.enforcementIsConditional` + `ChargeObservation.provesPolicyInEffect()` withhold the confirmed
    checkmark from a conditional policy that only settings can vouch for. **Verified on `aristotle` itself**
    (2026-08-16, foss debug + direct WSS): Adaptive renders with the neutral shield and "the system chooses when
    it applies", 100% still renders with the green check and the plain readback line, and both apply without a
    settling spinner — the last point being the visible symptom if the predicate ever leaks into pending logic.
    Note HyperOS blocks adb installs behind an on-device "Install via USB" dialog with a 5s auto-deny, so an
    install must be confirmed on screen while it is awake.
  - **Dead end, do not re-investigate:** `global battery_charging_state_enforce_level` and
    `battery_charging_state_update_delay` (both `-1` on this device) look like an enforcement lever from their
    names — they are not. Resolved 2026-08-16 by disassembling the device's own `/system/framework/services.jar`
    (`dexdump`): both are read by **`com.android.server.power.stats.BatteryStatsImpl$Constants`**, alongside
    `KEY_BATTERY_CHARGED_DELAY_MS`, `KEY_MAX_HISTORY_FILES`, and `KEY_KERNEL_UID_READERS_THROTTLE_TIME`. This is
    stock AOSP **battery-statistics bookkeeping** (when batterystats treats the device as charged, for history
    reset), not Xiaomi charge control, and it cannot cap charging current. Never written to. Recorded here
    specifically so the plausible-sounding name does not cost anyone a second investigation.
  - **HyperOS 3 candidate mapping (contribution report, 2026-08-07 — unqualified at the time; since landed,
    see the LANDED bullet below).** A
    Redmi Note 14 `24117RN76G` (`tanzanite`, Android 16 / SDK 36, `ro.mi.os.version.code=3`, ROM
    `3.0.302.0.WOGMIXM.C08`) reported via the contribution wizard that the **same key**
    `secure/security_pc_secure_protect_mode_key` now carries **three** modes: `0` = Charge fully and
    `1` = Intelligent charging (labels matching HyperOS 2), plus a new `2` = **Battery protection** — apparently a
    hard-cap mode, which HyperOS 2 lacks entirely. The device correctly fell through to `XiaomiLabAdapter`, and the
    existing decode treats `2` as `Unknown(unrecognizedValue=true)` (refuse-don't-clobber — the test that pins this
    was written as a garbage-value guard; `2` is now known to be a real, named OEM mode). This is a settings mapping
    only: **no behavioral evidence** (the optional effect prompts were skipped), the cap percentage is unknown
    (modeling mode `2` needs a concrete `FixedLimit` percent), factory/absent-key semantics on HyperOS 3 are
    unknown, and whether value `2` is HyperOS-3-wide or model-specific was initially unconfirmed (answered
    2026-08-13: **not** HyperOS-3-wide — see the marblein data point below). A full qualification would
    also need the boundary write domain widened from `{"0","1"}` (`ChargingControlUserService`). The contributor
    has a working Shizuku setup (clean three-namespace, three-mode capture) — a strong candidate for a follow-up
    qualification run; the report thread is in support mail ("Amply device-support discovery", 2026-08-07) and
    GitHub issue #48.
    - **Follow-up (2026-08-13, comment on PR #52, same `tanzanite` device): cap-percent candidate 80 — still
      unqualified.** The contributor claims mode `2` hard-caps at 80% and attached a `dumpsys battery` snapshot
      at the cap (`level: 80`, `AC powered: true`). Not accepted as hold evidence: it is a single snapshot (no
      sustained-hold observation, no current reading — a battery passing through 80 looks identical), the dump
      itself reports `status: 2` (= CHARGING) with `Charging state: 0` / `Charging policy: 0` (no hardware hold
      signal), and how mode `2` was set (native UI vs external write) is unstated — so daemon enforcement of
      **external** writes, the decisive question for Amply and open even on qualified HyperOS 2, remains
      unproven. Follow-up runs requested in the issue #48 thread: sustained hold at 80 with `current_now`, and
      the both-direction external-write test (adb `settings put` to `2` below the cap → hold; back to `0`
      mid-hold → charging resumes past 80). Delivered 2026-08-14 — see the enforcement bullet below.
    - **Both-direction external-write enforcement DEMONSTRATED (2026-08-14, issue #48, same `tanzanite`
      device).** The contributor ran the requested protocol via on-device adb shell — the **shell UID, the same
      write path Amply's Shizuku service uses**. (1) Below 80% plugged, UI manually set to "Charge fully",
      `settings put secure security_pc_secure_protect_mode_key 2` → Battery Protection activated and the
      Settings UI reflected it immediately, so the daemon reacts to external key writes, not just its own UI.
      (2) Held at 80% for ~20 minutes plugged under active use without gaining a point; sysfs
      `current_now` was permission-denied so there is no current reading, but the dumps corroborate the hold
      indirectly (voltage 4228 mV at the hold vs 4391 mV after resume; charge counter 4283 vs 4341). (3)
      `settings put … 0` mid-hold → charging resumed immediately, UI followed, level passed 80. Caveats: hold
      evidence is level observation plus voltage/counter deltas, not a current measurement, and both dumps
      show `status: 2` / `Charging state: 0` / `Charging policy: 0` — HyperOS 3 exposes **no hardware hold
      signal**, so a future adapter gets readback-only verification (like Samsung, unlike Pixel/GrapheneOS).
      This answers the decisive daemon-enforcement question for `tanzanite` mode `2`; the remaining items
      (gate design, boundary widening, factory/absent-key semantics, sessions/access-tiers/R8) are tracked in
      the landing bullet below.
    - **Second HyperOS 3 data point (support mail, 2026-08-13): the hard-cap mode is NOT HyperOS-3-wide.** A
      Poco F5 `23049PCD8I` (`marblein`, Android 15 / SDK 35, HyperOS 3.0.2, ROM `OS3.0.2.0.VMRINXM`) reported
      via the contribution wizard only the two HyperOS-2-style modes on the same key (`1` = Intelligent
      charging, `0` = Charge fully) — no value `2` and no hard-cap option captured. So mode `2` is model-
      and/or Android-16/OS-3.0.3-dependent, and a future HyperOS 3 gate cannot infer the hard-cap mode from
      `ro.mi.os.version.code == 3` alone. Caveats: the wizard saw `changed_rows=2` but the contributor withheld
      one row in the privacy review (mapping possibly incomplete), and the device was previously rooted with
      FDE.ai driving the charge limit. Thread: support mail "Amply device-support discovery", 2026-08-13.
      **Confirmed by native-UI screenshot (2026-08-14, same thread):** the F5's Battery protection screen
      shows a "Charging protection" group with exactly two entries — "Charge fully" and "Intelligent charging"
      (described as stopping at 80% "in applicable situations", i.e. the adaptive mode) — and no hard-cap
      option anywhere. This upgrades the finding from a wizard-capture inference to a direct observation of
      the OEM UI. It does **not** resolve the cause: `tanzanite` differs from `marblein` in both model and
      Android 16 / ROM 3.0.3, so model-dependence vs Android-16/OS-3.0.3-dependence remain confounded, and
      the codename gate stays the only sound design either way.
      **Support status for `marblein`: correctly diagnostics-only, and NOT a candidate for
      `xiaomi-hyperos3-v1`.** That adapter's protective default is `FixedLimit(80)` = mode `2`, which this
      device lacks; allowlisting the codename would let `apply()` write `"2"`, read it back from the settings
      row, and decode `Verified(FixedLimit(80))` while the daemon ignores it — a false claim of an active cap.
      The device's real surface is the two-mode HyperOS 2 one (`1`/`0`), reachable only by widening
      `QUALIFIED_HYPEROS_VERSION` from `2`, whose sole protective mode is Adaptive — blocked on the
      project-wide open item above (adaptive enforcement unobserved on both HyperOS generations), not on
      anything specific to this device.
    - **LANDED 2026-08-14: `xiaomi-hyperos3-v1`, gated to a qualified-codename allowlist (`tanzanite` only) —
      GrapheneOS-precedent landing** (remote enforcement qualification via issue #48, see the Verified devices
      row). **First on-device verification run (2026-08-14, issue #48; contributor-run, presumably on
      v0.3.3-beta0 — the first release carrying the adapter):**
      - **Absent-key decode CONFIRMED**: `settings delete` → `settings get` returns `null`, and both the native
        battery settings and Amply fall back to Intelligent charging. Absent = Intelligent is the real factory
        semantic; the shipped decode is correct.
      - **App-context writes + three-mode switching CONFIRMED**: switching all three modes from inside Amply is
        mirrored by the system settings immediately and vice versa, with the key value following each change
        (contributor polled `settings get` per switch). Access tier in the run: direct WSS (dashboard showed
        "Read back through direct wss"); the Shizuku tier remains unexercised on this device.
      - **Temporary session FAILED to restore — real app bug, adapter-independent, FIXED post-run**: the
        session's native-change observer received a settings notification carrying no value change (the
        session's own override write delivered late by async dispatch, or a HyperOS spurious notification) and
        cancelled the session without restoring; the device charged to 100% with the protective policy never
        re-written. Root-caused from the run's artifacts — the contributor's 22:22 screenshot shows the
        dashboard already idle at 96% while their 2s key monitor shows no value change after the 21:59:08
        override write. Fixed by `NativeChangeGuard` (readback-verified cancellation, see
        `rules/architecture.md`).
      - **Session restore RE-VERIFIED PASS (2026-08-16, issue #48, v0.3.4-beta0 — the first release carrying
        the #65 fix).** With Battery protection active, the contributor started the temporary full-charge
        session; the battery charged past the cap to 100% and Amply re-wrote Battery protection automatically
        on reaching full, **with the cable still connected**. So the `RESTORE_FULL` path fires without needing
        a disconnect, and `NativeChangeGuard` no longer eats the session on this device's notification
        pattern — the guard fix is now confirmed against the exact device that produced the bug. This closes
        the only failing item from the 2026-08-14 run; `tanzanite` becomes the second remote-qualified device
        with a verified session lifecycle. **Still NOT run** here: the disconnect-early restore path
        (`RESTORE_DISCONNECTED`), the Shizuku access tier, boot recovery, and an R8 beta/release smoke.
      - **Cap is fixed at 80%** (no percent picker in the Battery protection screen), and mode `2`'s own
        description says the device will "charge fully only when scheduled" — HyperOS reserves an OEM-side
        scheduled full charge while in Battery protection. No code impact (verification is settings readback;
        sessions override with `0`), but a future "charged to 100% while protected" report may be this OEM
        behavior rather than a bug.
      - **Adaptive (mode `1`) enforcement undemonstrated** — identical provisional status as HyperOS 2 (the
        top-level Xiaomi gap above); only mode `2` has demonstrated hardware enforcement.
      - The gate cannot widen past the codename allowlist: record any new HyperOS 3 device here plus a
        Verified-devices row before adding its codename to `XiaomiHyperOs3ChargingAdapter.QUALIFIED_CODENAMES`.
- **Pixel** — wireless at-threshold hold/charge-past and the widget under Shizuku-only remain unexercised (both share
  the verified wired mechanism).
- **HONOR / MagicOS** — **no adapter of any kind exists** (not even a lab adapter), so HONOR devices fall through
  every probe to the null branch of `AdapterRegistry` and `adapter=none` is a genuine registry miss. There is no
  HONOR-related read anywhere in the codebase: no property, no system feature, no package lookup.
    - **First HONOR mapping candidate (support mail, 2026-08-14): HONOR Magic8 Pro `BKQ-N49`** (codename `HNBKQ`
      from the fingerprint, MagicOS 10.0.0.193, Android 16 / SDK 36, contribution schema 2, app 0.3.2-beta0). The
      three earlier HONOR reports produced nothing: #35 (`MTN-NX1M`, Magic8 Lite) captured `changed_rows=0` across
      all three namespaces, #40 and #47 carried no capture at all.
    - The contributor captured the **full 2×2 factorial** of two independent features, which is the only capture
      design that can attribute two keys to two features. Both changed rows are in the `system` namespace:

      | Mode | Smart battery capacity | smart charge | `UserSmartPeakCap` | `asw_ui_state` |
      | --- | --- | --- | --- | --- |
      | off | off | off | 1 | 1 |
      | Smart battery capacity | **on** | off | 0 | 1 |
      | smart charge | off | **on** | 1 | 0 |
      | both | **on** | **on** | 0 | 0 |

      `UserSmartPeakCap` anti-correlates perfectly with Smart battery capacity, `asw_ui_state` with smart charge;
      both are **1-when-disabled**. Inverted polarity is not itself a problem (every adapter owns its decode), and
      "peak capacity 1 = uncapped" fits the name equally well.
    - **Blocker 1 — no behavioral evidence.** All four `user_reported_effect` values are `unsure`, so nothing
      here distinguishes a real control from a mirror. `asw_ui_state` is specifically suspect: a `_ui_`-named key
      is the same class as the Oplus `_status` mirrors that `OnePlusChargingAdapter` deliberately never writes.
      If it is a mirror, the real smart-charge control produced no diff row at all and lives outside the settings
      providers. **Next step is the both-direction external-write test** (`settings put system <key> <value>` from
      the shell UID, then check whether HONOR's own battery screen and the charging hardware follow), the same
      test that settled `tanzanite`. The contributor completed the Shizuku-gated wizard, so they have a working
      Shizuku setup and are a strong candidate to run it.
    - **Blocker 1, HALF resolved (support mail, 2026-08-16).** The contributor ran the external-write test from
      Termux via Shizuku/rish. **Both keys accept the write and HONOR's own battery screen follows the value in
      both directions**, for `UserSmartPeakCap`/Smart battery capacity and for `asw_ui_state`/Smart charge alike.
      That **rules out the read-only-mirror class** the Oplus comparison pointed at — the ColorOS `_status` keys
      are read-only (`95618cc`: "The OEM enforces exclusion and mirrors a read-only `_status`"), and these are
      not. The `_ui_` name is no longer a reason to distrust `asw_ui_state` specifically.
      **It does NOT satisfy step 2 of the protocol**, which passes only when writes move the *real charging
      hardware*, "not just the Settings UI". The surviving failure mode is a key the Settings UI genuinely reads
      *and* writes while a separate mechanism drives the charger — precisely the oriole/LineageOS 20 result
      (write accepted, `ChargingControlController` picked it up and reported the new config back, battery charged
      92→95% at ~1.2 A). Treat UI-follows-write as a **precondition met**, never as qualification.
    - **Smart charge is adaptive, not a cap (same mail).** Overnight the device reaches 100% by morning, matching
      HONOR's described hold-then-top-off behaviour. So the feature maps to `ChargePolicy.Adaptive`, not a
      `FixedLimit`, and there is no plateau for the step-2 hardware test to observe — **only Smart battery
      capacity is hardware-testable here**, and it is also the key carrying the level hazard below. Note the
      Adaptive honesty gap recorded for Xiaomi above applies identically: `allowsFullCharge`
      (`ChargePolicy.kt:12-13`) excludes Adaptive, so Amply would call this "protective" for a mode that reaches
      full on its own.
    - **Blocker 2 — no gate signal identified.** The fingerprint is stock-shaped
      (`HONOR/BKQ-N49/HNBKQ:16/HONORBKQ-N49/10.0.0.193C636E4R106P1:user/release-keys`), so fingerprint sniffing
      is out for the same reason it was for LineageOS. Whether a MagicOS-exclusive property analogous to
      `ro.mi.os.version.code` exists **and is readable from an `untrusted_app` process** is unknown and cannot be
      settled by adb `getprop` (adb runs as shell — see the SELinux trap above). `Build.MANUFACTURER == "HONOR"`
      alone is a manufacturer gate with no ROM scoping, weaker than every existing OEM gate.
      **Unchanged by the 2026-08-16 follow-up, and it outlives the mapping**: even a clean hardware result leaves
      no safe way to switch an adapter on only where it applies. Asked the contributor for `pm list features` and
      `pm list packages -s` filtered on honor/magic, i.e. the two mechanisms that *are* app-readable and already
      carry LineageOS (`org.lineageos.android` system feature) and GrapheneOS (`app.grapheneos.*` core packages).
      A property would not do, for the reason above. Also note the app itself cannot answer this: property names
      are compile-time constants, the AIDL has no property op
      (`IChargingControlService.aidl`), and `SystemPropertyReader` structurally cannot distinguish denial from
      absence — so probing a candidate MagicOS property needs a code change, not a contributor run.
    - **Blocker 2, IDENTITY MECHANISM RESOLVED (support mail, 2026-08-17); version scoping still open.** The
      contributor supplied both lists from `HNBKQ`. App-readable signals now known to exist on MagicOS 10:
        - **System features** (`hasSystemFeature`, no permission, no `<queries>` entry — the LineageOS pattern):
          `com.hihonor.software.features.honor`, `com.hihonor.system.feature`,
          `com.hihonor.software.features.full`, `com.hihonor.software.features.handset`,
          `com.hihonor.software.features.oversea`, `com.hihonor.magic.api.23`.
        - **Core system packages** (PackageManager + `FLAG_SYSTEM` — the GrapheneOS pattern):
          `com.hihonor.systemserver`, `com.hihonor.systemmanager`, `com.hihonor.powergenie`,
          `com.hihonor.controlcenter`, `com.hihonor.android.launcher`.
        - Properties exist too (`ro.build.version.magic=MagicOS_10.0.0`, `ro.build.magic_api_level=42`,
          `ro.magic.cversion=C636`) but stay the **wrong** route for the SELinux reason above; they are recorded
          only as corroboration. `ro.product.device=HNBKQ` confirms the fingerprint-derived codename.
      **What is still missing is ROM-generation scoping.** `com.hihonor.magic.api.23` does not line up with
      `ro.build.magic_api_level=42`, so that feature reads as a fixed namespace marker rather than a version
      discriminator — i.e. the features answer "is this MagicOS", not "is this MagicOS 10". Every existing OEM
      gate is version-scoped (One UI range, `ro.mi.os.version.code`, `oplusrom == 15`); a features-only HONOR gate
      would be the first that is not, which is exactly the weakness called out above for `Build.MANUFACTURER`.
      Resolve that before any gate is written, not after.
    - **Level-reporting hazard to test at qualification.** The reporter states Smart battery capacity "still
      displays 100% when fully charged" while capping. A ROM reporting a synthetic 100% at a real ~80% would
      trip `full = status == BATTERY_STATUS_FULL || percent >= 100` in `ChargeSessionService`, ending a session
      early via `RESTORE_FULL`, and would corrupt `StatsLimitHitDetector`. Verify before any adapter ships.
      Three sharpenings (2026-08-16), because the phrasing above understates it:
        - **The predicate is a disjunction, so `BATTERY_STATUS_FULL` alone trips it**
          (`ChargeSessionService.kt:374`). Guarding only `percent >= 100` would close nothing: a ROM that fakes
          the level almost certainly drives `EXTRA_STATUS` through the same platform path. `full` is also the
          **first** branch of `SessionDecisionEngine.decide` (`SessionDecision.kt:122`), outranking the safety
          timeout, the disconnect path and the replug grace window, and the resulting `RESTORE_FULL` is silent —
          Amply re-applies the protective policy and believes it finished the job.
        - **Nothing establishes that the *broadcast* carries the synthetic value.** The contributor reported what
          HONOR *displays*. SystemUI reads the same broadcast so the two usually agree, but that is an inference.
          The qualification measurement must read `dumpsys battery` (level/status/voltage/charge counter), not
          the status bar.
        - **No workaround exists to build on.** There is no level clamp, plausibility check, or level-vs-hardware
          cross-check anywhere in the app; `BatteryReadoutFactory.kt:54-57` rejects only a malformed level/scale
          pair. Tolerating a lying ROM would be new work with no existing seam, so do not describe it as a
          compatibility tweak. Blast radius beyond sessions: `StatsLimitHitDetector.kt:44` returns `false`
          unconditionally at ≥100 (the "limit reached" signal would never fire on exactly this device class), and
          `ChargeAlarmEngine.kt:39` would fire at any configured target outside a session (inside one,
          `sessionActive` suppresses it).
      **Measurement asked for (2026-08-16):** charge to a displayed 100% with Smart battery capacity ON, record
      `dumpsys battery`, then switch the feature OFF with the cable in and re-record. Voltage and charge counter
      climbing afterwards proves the cap is real and the 100% cosmetic; unchanged means the cell was already full
      and the mode caps nothing. Same evidence shape that settled `tanzanite`.
      **RESULT (2026-08-17), and it reframes the feature.** Contributor-run, `HNBKQ`:

      | State | Charge counter | status | level | voltage |
      | --- | --- | --- | --- | --- |
      | Smart battery capacity ON, plugged, HONOR showing 100% (stable ≥1 min) | 6978 | 2 | 100 | 4551 |
      | Feature OFF, cable never removed | 6978 (unchanged) | — | — | — |
      | Feature OFF, after unplug→replug | 7256 | 5 | 100 | 4421 → 4472 |

        - **The synthetic 100% is CONFIRMED in the broadcast, not just the status bar.** `level: 100` with 278 mAh
          of real headroom, so the sharpenings above are now evidenced rather than inferred. The dumpsys `status: 2`
          alongside a flat counter also means the ROM reports "charging" while holding.
        - **But the cap is only ~4%, so Smart battery capacity is NOT an 80%-cap equivalent.** The C636/Singapore
          variant is the 7100 mAh model (China 7200, Europe 6270; the contributor's own AIDA64 reading of ~7121 mAh
          corroborates), so 278 mAh ≈ 3.8%. An 80% cap would have plateaued near 5800 mAh. This is a
          top-of-charge voltage trim, a different class of feature from every currently supported adapter.
          Consequence: **neither HONOR key is a hard cap** — Smart charge is adaptive (reaches 100% overnight),
          Smart battery capacity trims ~4% — so an adapter here could offer Adaptive on/off and no percentage at
          all, with the `allowsFullCharge` honesty gap applying. Weigh that against the build cost before
          committing to a MagicOS adapter; the mapping being correct is no longer the deciding question.
        - **`policyLatchesAtPlug` behaviour OBSERVED.** Disabling the feature with the cable in moved nothing; the
          278 mAh only went in after unplug→replug. Same latch-at-plug-session-start family as GrapheneOS, so an
          adapter would need `policyLatchesAtPlug = true`, the pending-until-replug state, and the replug grace
          window rather than anything new. The reconnect gesture would be unsupported for the same timing reason.
        - Caveats: single run, one sample per state, and the voltage column is not interpretable across rows
          (4551 was measured under charge, 4421 at `status: 5` resting). The charge counter is the load-bearing
          number. Note the ~4% figure is a **ratio** (6978/7256), so it survives the charge-counter unit question
          raised by the telemetry defect below — a uniform 1000× scaling cancels. Only the absolute
          "278 mAh" framing depends on the unit, and the nominal-capacity cross-check independently supports mAh.
    - **Third key, out of scope: `secure/charge_separation_all_scenarios_switch`** (bypass charging, `1`/`0`,
      reported 2026-08-16). Contributor observation: bypass engages while the screen is on and normal charging
      resumes with the screen off. Recorded as context only — Amply has **no bypass concept at all** (the sole
      codebase mention is a KDoc line in `GrapheneOsChargingAdapter.kt:25` describing what GrapheneOS's key does
      underneath), and `ChargePolicy` cannot express a bypass-only state. Not a candidate for the write allowlist.
    - `rom_version=magicos 10.0.0.193` in the report is **free text typed by the contributor**, not a detector
      output; `one_ui_version=none` / `hyperos_version=none` are the real detectors correctly returning null.
      Note also that contribution reports carry **no codename field** (unlike the direct device-support report),
      so an allowlist entry can only come from the fingerprint or a follow-up. Asked the contributor to run the
      "Just send device info" action on the unsupported-device card (`DeviceSupportReporter` emits `device=`
      from `Build.DEVICE`, plus `brand`/`product` and the tri-state key probes; no Shizuku needed) rather than
      another wizard pass, which would omit the codename again.
    - **Three app defects this device exposed (2026-08-17), two of them not HONOR-specific.**
        - **`FIXED`: "Open battery settings" landed on Battery Saver on every unmapped device.**
          `ChargingRepository.nativeSettingsIntent()` returned null for `adapter == null` and its only caller
          substituted `ACTION_BATTERY_SAVER_SETTINGS` outright, so `ACTION_POWER_USAGE_SUMMARY` was never tried —
          despite every lab adapter preferring it, and despite the manifest already declaring its `<queries>`
          visibility. Same user-visible symptom as the LineageOS case above, reached by a different path (that one
          fell through to the Pixel component intent; this one had no adapter object to ask). Affected HONOR,
          Motorola, Nothing, Sony, Fairphone, Vivo, Tecno — anything without an adapter. Fixed by making the
          repository fall back to `OemChargingShortcuts.genericBatterySettings`, with the chain extracted so the
          lab adapters and the null path share one implementation. **Not confirmed to change what this contributor
          sees**: whether `POWER_USAGE_SUMMARY` resolves on MagicOS 10 is still unverified.
        - **OPEN (deferred, deliberate): "Just send device info" cannot appear on an unrecognized ROM.**
          `UnsupportedDeviceCard.kt` gates it on `hasSupportLead` (`ChargingRepository.kt`), a ten-way disjunction
          of known-ROM markers, every one of which is structurally false for HONOR. Shipped in `v0.3.2-beta0`
          (`bc55ceb`, PR #44) with a pinning test, so this contributor's build could never have shown it. The gate
          is defensible (it exists to avoid dead-end reports) but perverse here: the less Amply recognizes a
          device, the less it lets the user report it — and the report being withheld is the only one carrying
          `Build.DEVICE`, which is exactly what an allowlist entry needs. This contributor hand-dumped properties
          because of it. Held rather than changed: reversing a deliberate decision on one data point is thin, and
          the Shizuku wizard path was still open to them. Revisit if another unknown-ROM contributor hits it.
        - **CONFIRMED and FIXED: the ROM reports battery telemetry in milli-units.** Symptom was charge power
          rendering `0.0 W` / `4 mA` while charging. Both figures come from one field,
          `BATTERY_PROPERTY_CURRENT_NOW`: a ROM reporting mA where Android documents µA turns a real 4 A into the
          integer `4000`, which formats as "4 mA" and computes to `4551 mV × 4000 µA / 1e6 = 18 mW`, printing as
          "0.0 W" (reproduced exactly in `StatsPowerCalculatorTest`).
          **Settled 2026-08-17 by screenshot** (`Screenshot_…_MainActivity.jpg`, EXIF `model=BKQ-N49`,
          `10.0.0.193(C636E4R106P1)`): Amply's own "Charge counter" row reads **7 mAh**, which is
          `round(6978 / 1000)` — the same raw `6978` the contributor's `dumpsys` showed, so both read one source
          and it is scaled by 1000 on a 7100 mAh cell. Second, unprompted corroboration in the same screenshot:
          **`Current now` = 0 mA while `Status` = Discharging with the screen on**, which is impossible; a real
          ~300 mA arrives as `300` and `round(300/1000)` is 0. The competing "genuine end-of-charge trickle"
          explanation is dead.
          **Fix: a ROM gate AND the anomaly, both required** (`BatteryUnitCalibration.romMisreportsUnits` +
          `BatteryReadoutFactory.chargeCounterLooksMilliScaled`). MagicOS is recognised by system feature
          (`com.hihonor.software.features.honor` / `com.hihonor.system.feature`, either suffices, no `<queries>`
          and no permission), and the reading must *also* show an implied full-charge capacity below 100_000 µAh
          — `counter × 100 / percent`, which normalizes out the charge level, separating a correct phone
          (≥ ~1_000_000 µAh at any level) from a milli device (single-digit thousands) by an order of magnitude
          on both sides. So a correctly-reporting MagicOS build is left alone, and no other ROM can ever be
          touched.
          **Two pure-inference designs were built and abandoned first. Record the reason, it is not obvious:**
            1. *Counter anomaly alone rescales both fields.* Refuted in review — `CURRENT_NOW` and
               `CHARGE_COUNTER` are independent HAL fields, so an impossible counter proves nothing about
               current. A device with a broken, stale or freshly-reset counter but healthy current would have
               had a real 50 mA turned into 50 A, computing to ~200 W, which slips under
               `StatsPowerCalculator`'s 250 W ceiling and lands in recorded stats as a *believable* lie.
            2. *Require the defect in both fields at once* (impossible counter AND `BATTERY_STATUS_CHARGING`
               while drawing under 5 mA), latched because that evidence only exists while charging. Also
               refuted, and by Amply's own domain: **a device at a charge-limit hold reports exactly that** —
               `StatsLimitHitDetector` uses `abs(current) < 50_000 µA` while `CHARGING` as its hold signal.
               Amply *creates* holds deliberately, so a healthy phone holding at 80% with a broken counter
               satisfied the conjunction and latched. There is no impossibility boundary here to build on.
          The lesson generalizes: **the states that look like this defect are states this app manufactures**, so
          a purely data-driven unit inference is unsound in this codebase specifically. Being wrong about a ROM's
          units shows wrong numbers; being wrong about a healthy device corrupts good ones — the gate can only
          ever do the former. Generalizing "all MagicOS" from one device is a deliberate bounded bet, far cheaper
          than the adapter equivalent because the failure mode is a wrong battery figure, not a false claim that
          a battery is protected. Other affected ROMs stay uncorrected until one is confirmed and added.
          Other accepted limits: a device reporting **no** counter is undetectable, and the charger-advertised
          `max_charging_*` extras are deliberately not rescaled, being separate extras never observed populated
          on such a device.
          **Known wart, accepted:** `ChargeStatsRecorder` persists the readout, so history recorded before the fix
          keeps the uncorrected values and a chart spanning the upgrade shows a 1000× step. Only affects devices
          that were already reporting garbage, and no migration can identify which stored rows came from a
          milli-reporting device.
    - **Status after the 2026-08-17 follow-up: still NOT qualified.** Blocker 1's hardware half is now ANSWERED
      but the answer is unfavourable — the cap is real yet only ~4%, and neither key is a hard limit, so the open
      question is no longer "does it work" but "is an Adaptive-only MagicOS adapter worth building". Blocker 2's
      identity mechanism is resolved; its version scoping is not. Nothing here justifies an adapter, a lab
      adapter, or a manufacturer read yet.
    - Tracking: GitHub issue #66.
