# Samsung Battery-Protection Spike Results

Ground truth for the Samsung adapters, gathered by driving Samsung's own Settings UI and diffing
`settings list` snapshots after every change (same methodology as `PIXEL_SPIKE.md`).

## Devices

| Device | Model | Android | One UI | Result |
|---|---|---|---|---|
| Galaxy Tab A9+ | SM-X210 | 16 (SDK 36) | 8.0 (`80000`) | Full multi-mode mapping verified (2026-07-21) |
| Galaxy S20 FE | SM-G781B | 12 (SDK 31) | 4.1 (`40100`) | Legacy toggle observed via settings dump (2026-07-21) |

One UI version read from `ro.build.version.oneui` (e.g. `80000` = 8.0). One UI 6.x/7.x
(Basic/Adaptive/Maximum era) and 9.x+ are **unverified** — no device available — and stay
diagnostics-only until qualified. Record new results here.

## One UI 8.0 mapping (verified on SM-X210)

All keys live in the **`global`** namespace: world-readable without any permission; writes need
`WRITE_SECURE_SETTINGS` (or Shizuku).

| Settings UI state | `protect_battery` | `battery_protection_threshold` |
|---|---|---|
| Protection off | `0` | untouched |
| Standard (charge to 100%, pause, resume at 95%) | `3` | untouched |
| Maximum (stop at threshold) | `1` | `80`/`85`/`90`/`95` |

- `battery_protection_threshold` is **absent until the slider is first moved**; absent means 80.
- Bookkeeping keys written by Samsung Settings only (Amply never touches them):
  `prev_protect_battery`(+`_ltc`) — prior mode saved on toggle-off, `-1` when idle;
  `battery_protection_default_value=3`; `init_protection_to_adaptive=1`.
- "Schlafzeitschutz" / sleep-time protection (Standard-mode sub-feature) was not exercised;
  candidate keys `key_sleep_charging`, `auto_on_protect_battery`.

## External-write behavior (the important part)

- `settings put global protect_battery <n>` applies **immediately** and the Settings UI reflects
  it live (a content observer is registered). No revert was observed. There is no asynchronous
  Settings-Intelligence-style middleman as on Pixel, and no same-value-write observer trap.
- External writes **bypass** `prev_protect_battery`. After an external `0`, Samsung's own
  re-enable toggle falls back to the OEM default (Standard), not the user's prior mode — Amply
  must restore the exact prior policy itself.
- Charging-HAL enforcement of externally written values: verified on hardware as part of the
  adapter qualification runs (see below).

## Legacy One UI 4.1 (SM-G781B)

Only `protect_battery` exists, as a plain `0`/`1` toggle with a fixed **85%** cap. No threshold,
mode values, or `prev_*` keys.

## Native settings entry point

`com.samsung.android.lool/com.samsung.android.sm.battery.ui.protection.BatteryProtectionActivity`,
exported with intent action `com.samsung.android.sm.ACTION_BATTERY_PROTECTION` (verified
launchable on One UI 8.0).

## Hardware qualification runs

| Date | Device | Scenario | Result |
|---|---|---|---|
| _pending_ | SM-X210 | Full session E2E (Maximum@80 → PauseAtFull → restore), WSS + Shizuku paths, reboot-during-session, native-change cancellation | — |
| _pending_ | SM-G781B | Legacy toggle E2E | — |
