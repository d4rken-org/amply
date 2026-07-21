# Xiaomi Charging-Protection Spike Results

Ground truth for the Xiaomi adapter, gathered by driving the MIUI/HyperOS Battery-protection UI
and diffing `settings list` snapshots after every change (same methodology as the Pixel and
Samsung spikes).

## Devices

| Device | Model | Android | HyperOS | Result |
|---|---|---|---|---|
| Xiaomi 13T | 2306EPN60G | 15 (SDK 35) | 2.0 (`ro.mi.os.version.name=OS2.0`, `ro.miui.ui.version.code=816`) | Binary mapping verified (2026-07-21) |

`ro.miui.ui.version.code` identifies a software *family*, not a build — the v1 gate therefore
pins the exact qualified model in addition to the code. Record newly qualified devices here
before widening the gate.

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
| 2026-07-21 | 2306EPN60G | Minified (R8) foss beta: boots, MiuiVersionDetector reflection resolves the gate, write path works | PASS |
| n/a | 2306EPN60G | Shizuku read column | Not run — Shizuku installed but its native lib is unextracted on this MIUI build; adb-start unavailable. Same `adapter.read()` logic as the PASSed WSS path; Shizuku→direct fallback is unit-tested |
| n/a | 2306EPN60G | Mid-session native-change cancel / reboot recovery | Not holdable — device pinned at 100% auto-restores on session start (RESTORE_FULL), so no session persists to interrupt. Both mechanisms are adapter-agnostic and hardware-verified on the Samsung devices |
