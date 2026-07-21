# Xiaomi Charging-Protection Spike Results

Ground truth for the Xiaomi adapter, gathered by driving the MIUI/HyperOS Battery-protection UI
and diffing `settings list` snapshots after every change (same methodology as the Pixel and
Samsung spikes).

## Devices

| Device | Model | Android | HyperOS | Result |
|---|---|---|---|---|
| Xiaomi 13T | 2306EPN60G | 15 (SDK 35) | 2.0 (`ro.mi.os.version.name=OS2.0`, `ro.mi.os.version.code=2`) | Binary mapping verified (2026-07-21) |

## Gate

The charge-protection setting is a **HyperOS ROM feature**, not a per-model one, so the adapter is
gated to the HyperOS **major version**: Xiaomi manufacturer (which covers Redmi/POCO — they report
`Xiaomi` as manufacturer) + `ro.mi.os.version.code == 2` (HyperOS 2.x) + system user. Use
`ro.mi.os.version.code`, **not** the frozen legacy `ro.miui.ui.version.code` (=816), which does not
cleanly separate HyperOS generations. HyperOS 1, pre-HyperOS MIUI, and a future HyperOS 3 fall to
the diagnostics-only lab adapter — record newly qualified generations here before widening.

**Known assumption**: the mapping was verified on one HyperOS 2.0 device and is assumed to hold
across HyperOS 2.x. The key is absent in factory state even on devices that *have* the feature, so
key-presence cannot distinguish "feature exists" from "feature absent" — on a HyperOS 2 device that
genuinely lacks Battery protection, Amply shows a verified Adaptive state and a control the OS
ignores. This is a false claim of control, not a battery hazard, and affects only that subset.

## HyperOS 2.0 mapping (verified on 2306EPN60G)

One key in the **`secure`** namespace (per-user; only writes need WSS):

| Battery-protection UI state | `security_pc_secure_protect_mode_key` |
|---|---|
| Charge fully | `0` |
| Intelligent charging (heuristic 80% hold, "in applicable situations") | `1` |
| Factory state (never toggled) | key **absent** — the UI treats absent as Intelligent |

- There is **no hard-cap / fixed-limit mode** on this device — Intelligent is adaptive-style
  (the OS decides when to hold at 80%), so the adapter maps it to `ChargePolicy.Adaptive`.
- The key was the ONLY observable change across all three settings namespaces on mode switches.
- `persist.vendor.smartchg=6` is static across mode changes on both the UI and external write
  paths — a capability flag, not state.

## External-write behavior

- `settings put secure security_pc_secure_protect_mode_key <n>` sticks (no revert observed) and
  the OEM Battery-protection screen honors the value on next load.
- The OEM screen does **not** live-update while open (no content observer on their side);
  Amply's own native-change observation still works because it watches the settings URI, which
  fires for any writer.
- **Enforcement caveat**: the Intelligent 80% hold is heuristic and could not be triggered on
  demand (device at 100%, no learned charging pattern). External writes are most likely
  equivalent to the OEM toggle (the key is the only observable state on the OEM's own path),
  but daemon-level enforcement of externally written values is **pending long-term
  observation** — recorded here deliberately.

## Hardware qualification runs

| Date | Device | Scenario | Result |
|---|---|---|---|
| 2026-07-21 | 2306EPN60G | Adapter selected + gated under app UID; dashboard renders the two-chip Adaptive/100% selector with honest adaptive copy | PASS |
| 2026-07-21 | 2306EPN60G | WSS read matrix: absent→Adaptive, `0`→Unrestricted, `1`→Adaptive, all Verified via DIRECT_WSS (no `null`-filtering fakeout) | PASS |
| 2026-07-21 | 2306EPN60G | Screen-open with the key deleted does NOT materialize it (no spurious native-change cancel) | PASS |
| 2026-07-21 | 2306EPN60G | Writes via the app both directions (Adaptive↔100%), read-back confirmed | PASS |
| 2026-07-21 | 2306EPN60G | Unrecognized-value refusal: external `2` then "Charge to 100% once" refuses, key left at `2`, no write | PASS |
| 2026-07-21 | 2306EPN60G | Session E2E at 100%: start → write `0` → RESTORE_FULL → restore `1`, all DIRECT_WSS Verified | PASS |
| 2026-07-21 | 2306EPN60G | Minified (R8) foss beta: boots, the version-detector reflection resolves the gate, write path works | PASS |
| n/a | 2306EPN60G | Shizuku read column | Not run — Shizuku installed but its native lib is unextracted on this MIUI build; adb-start unavailable. Same `adapter.read()` logic as the PASSed WSS path; Shizuku→direct fallback is unit-tested |
| n/a | 2306EPN60G | Mid-session native-change cancel / reboot recovery | Not holdable — device pinned at 100% auto-restores on session start (RESTORE_FULL), so no session persists to interrupt. Both mechanisms are adapter-agnostic and hardware-verified on the Samsung devices |
