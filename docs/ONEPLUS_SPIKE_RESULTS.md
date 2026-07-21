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
| _pending_ | CPH2621 | Adapter selected + gated under app UID; dashboard renders the three-way selector | — |
| _pending_ | CPH2621 | WSS-only write is rejected (confirms system-namespace constraint); Shizuku write succeeds | — |
| _pending_ | CPH2621 | Read matrix (limit / smart / neither); mode writes via app; session E2E; native-change cancel; minified beta | — |
