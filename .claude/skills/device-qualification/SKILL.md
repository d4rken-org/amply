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
| GrapheneOS | Pixel 9 Pro XL `komodo`, GrapheneOS 2026080501 / Android 17 — **REMOTE qualification via issue #49** (tester-run protocol, not maintainer hardware) | **Enforcement observed**: held at 80% with shield, `dumpsys battery` status=4/Charging state=4/policy=2 (limit on) vs 2/1/1 (off); shell-UID writes move the Settings UI live, **latch at plug-session start** — mid-session writes have no hardware effect until unplug→replug, replug reliably applies the current value | Key isolation (`settings list` diff → single `global battery_charge_limit` 0/1), write→UI both directions, mid-session no-op both directions, replug latch both directions, hardware signal both states. **NOT run**: app-context access tiers (WSS write from Amply, `app.grapheneos.*` package visibility), sessions/boot recovery, wireless, factory-absent key state, secondary user | 2026-08-12 |
| Xiaomi (HyperOS 3) | Redmi Note 14 `24117RN76G` (`tanzanite`), HyperOS 3.0.302 / Android 16 (`ro.mi.os.version.code=3`) — **REMOTE qualification via issue #48** (contributor-run protocol, not maintainer hardware) | **Both-direction enforcement of EXTERNAL shell-UID writes observed** (the same write path as Amply's Shizuku service): `settings put … 2` below the cap → Battery protection active, Settings UI follows immediately, held at 80% for ~20 min under active use (voltage 4228 mV holding vs 4391 mV charging, charge counter 4283 vs 4341 corroborate; sysfs `current_now` permission-denied, so no current reading); `settings put … 0` mid-hold → charging resumes past 80 immediately. **No hardware hold signal**: `dumpsys battery` reports `status: 2` / `Charging state: 0` / `Charging policy: 0` in both states → read-back-only verification | Key mapping (three modes incl. `2` = Battery protection @80, cap fixed — no percent picker), external write → UI both directions, sustained hold, mid-hold release. Beta run 2026-08-14 added: app-context three-mode control (direct WSS), factory-absent key = Intelligent (confirmed). Session restore FAILED in that run (observer noise cancel — app bug, fixed; see Known gaps). **NOT run**: Shizuku tier, boot recovery, wireless, R8, session re-verify post-fix | 2026-08-14 |
| GrapheneOS (follow-up) | Same device, **0.3.2-beta0 on-device report via issue #49** | **Package detection VERIFIED from app context** (`is_grapheneos=true` with the FLAG_SYSTEM check); **unprivileged key read DENIED** — `has_battery_charge_limit=false` while the very same report showed `battery_charging_status=4` (limit enforcing). Root cause in GrapheneOS source: the key is `@Protected(read = SYSTEM_UI, readWrite = SETTINGS)` (frameworks_base `c30c6393`); SettingsProvider throws SecurityException for all other packages **including WSS holders**, with the shell UID explicitly exempt ("ADB is used for testing", `e87c93a2`) — so the tester's earlier adb runs ARE the Shizuku-path evidence. Factory-absent semantics resolved from source: `BoolSetting(..., default false)` → absent = off | Detection + fail-closed probe verified live; adapter re-gated to Shizuku-only in response | 2026-08-13 |

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
- **GrapheneOS** — landed **live** on remote qualification (issue #49; the only OEM row not tested on maintainer
  hardware), then **re-gated to Shizuku-only** after the 0.3.2-beta0 on-device report (see the follow-up ledger
  row). Resolved since the landing PR: package visibility ✔ verified from app context; app-context WSS access ✘
  resolved as DENIED (`@Protected` — the reason for the re-gate, not a bug in Amply); factory-absent key state ✔
  resolved from upstream source (`BoolSetting` default false → absent decodes as Unrestricted). Still open, all
  failing closed or cosmetic:
  - **Shizuku end-to-end on real hardware unverified** — the shell-UID `settings get/put` mechanism is proven (the
    tester's adb runs), but Amply's own Shizuku service driving it, plus sessions/boot recovery/R8 smoke, await the
    next beta report on issue #49.
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
- **Xiaomi** — adaptive hardware enforcement of external writes unconfirmed; treat the adapter as provisional until
  the 80% hold is physically observed.
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
      FDE.ai driving the charge limit. Thread: support mail "Amply device-support discovery", 2026-08-13; the
      contributor was asked whether the settings screen shows any third mode / 80% option.
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
        `rules/architecture.md`); **session-lifecycle re-verification is the headline ask for the next beta**.
        Boot recovery and R8 smoke remain NOT RUN.
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
