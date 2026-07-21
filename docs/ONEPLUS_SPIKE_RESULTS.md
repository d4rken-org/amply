# OnePlus / ColorOS Charging-Protection Spike Results

Ground truth for the OnePlus (Oplus) adapter, gathered by driving the ColorOS Battery-health UI
and diffing `settings list` snapshots after every change (same methodology as the Pixel, Samsung,
and Xiaomi spikes).

## Devices

| Device | Model | Android | ColorOS | Result |
|---|---|---|---|---|
| OnePlus Nord CE4 Lite | CPH2621 | 15 (SDK 35) | 15 (`ro.build.version.oplusrom=V15.0.0`) | Mapping verified (2026-07-21) |

## Gate

The charge-protection settings are a **ColorOS ROM feature**, shared across the whole Oplus
family (OnePlus / Oppo / Realme). Control is gated to the ROM **major version**:
`ro.build.version.oplusrom == 15`. That property is Oplus-exclusive, so it doubles as the family
signal — no manufacturer check is needed. Plus system user (the keys are per-user, charging
hardware is device-wide). Older ColorOS, a future ColorOS 16, and non-Oplus devices fall to the
diagnostics-only lab adapter. Verified on OnePlus; **assumed** across Oppo/Realme (same ROM) —
record newly qualified devices here.

## Mapping (verified on CPH2621, ColorOS 15)

Two **mutually-exclusive** toggles under Settings → Battery → Battery health, both in the
**`system`** namespace:

| Battery-health UI | Key (`system`) | Semantics | Policy |
|---|---|---|---|
| **Charging limit** | `regular_charge_protection_switch_state` (0/1) | Hard cap — "battery level will always be kept at 80% while charging" | `FixedLimit(80)` |
| **Smart charging** | `smart_charge_protection_switch_state` (0/1) | Adaptive — "learns your habits and defers charging to 100% until shortly before you need it" | `Adaptive` |
| (both off) | — | Unrestricted | `Unrestricted` |

- **Mutual exclusion is OEM-enforced**: enabling one auto-disables the other. Both-on is treated
  as an inconsistent external state (Unknown/unrecognized, never overwritten).
- Each toggle has a paired read-only `_status` mirror (`regular_charge_protection_status`, etc.)
  that follows the switch automatically — Amply writes only `_switch_state`.
- The limit is a **fixed 80%** with no user-adjustable threshold.
- `smart_charge_switch_state` (distinct) and `charge_protection_current_state` stayed 0 throughout
  — not the actionable keys.

## Enforcement & external writes

- **Enforcement is real and directly observable** (unlike Xiaomi): with Charging limit on, the
  device sat plugged at 80% showing "80% Fully charged" and battery status = discharging-while-
  plugged (the hold). No "pending observation" caveat needed for OnePlus.
- External `settings put system <key> <0|1>` **sticks** (not reverted). The ColorOS UI does NOT
  live-observe the key — an already-open Battery-health screen showed a stale switch; re-entering
  the screen read the true value. Amply's own URI observer still fires.

## Write-path constraint (important)

These keys are in the **`system`** namespace. `WRITE_SECURE_SETTINGS` covers secure/global but
NOT system, so the direct-WSS write path used by the other adapters **cannot** write them —
**OnePlus control requires Shizuku** (shell UID writes system freely). Reads are unprivileged
(system is world-readable), so state display works on any backend; only writes need Shizuku. The
adapter sets `preferShizukuForWrites` and verifies by read-back equality, so a WSS-only attempt
fails honestly rather than silently. The privileged allowlist (`SettingWritePolicy`) admits both
keys in the `system` namespace with domain {0,1}.

## Hardware qualification runs

| Date | Device | Scenario | Result |
|---|---|---|---|
| 2026-07-21 | CPH2621 | Adapter selected + gated under app UID; dashboard reads "80% limit" (regular=1) and renders the 80/Adaptive/100 selector; state read via WSS (system is world-readable) | PASS |
| 2026-07-21 | CPH2621 | **WSS-only write is rejected** — apply reports "Policy not verified / write failed" and the key is untouched (read-back verification catches it honestly). Confirms the system-namespace constraint on hardware | PASS |
| 2026-07-21 | CPH2621 | **Shizuku write succeeds** for all three policies: 100%→(0,0), Adaptive→(0,1), 80%→(1,0); mutual exclusion is written correctly; "Read back through shizuku" verified | PASS |
| 2026-07-21 | CPH2621 | WSS-only UX: controls disabled + "Shizuku required to change charging" banner shown; state still readable | PASS |
| n/a | CPH2621 | Session E2E / native-change cancel / minified beta | Not run this pass — device at 80% under its own limit; the shared session/observer/R8 paths are verified on the other adapters. Left for a follow-up device pass. |
