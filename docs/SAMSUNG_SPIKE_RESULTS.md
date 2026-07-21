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
| 2026-07-21 | SM-X210 | Detector + gate under app UID (debug build): modern adapter selected, six policy choices rendered | PASS |
| 2026-07-21 | SM-X210 | Policy writes via WSS: 85% (`1`+threshold `85`), back to 80%; verified readback in UI | PASS |
| 2026-07-21 | SM-X210 | Session E2E: Maximum@80 → PauseAtFull (`3`, threshold untouched) → manual restore (`1`/`80`) | PASS |
| 2026-07-21 | SM-X210 | Native-change cancellation: external `protect_battery=0` during session cancelled without restoring; external value respected | PASS |
| 2026-07-21 | SM-X210 | Uniform session refusal: "Charge to 100% once" while Unrestricted starts no session, writes nothing | PASS |
| 2026-07-21 | SM-G781B | Legacy gate + dashboard (85%/100% choices), session E2E: `1` → `0` → restore `1`; threshold key never created | PASS |
| 2026-07-21 | SM-G781B | Reboot during session: boot recovery restored `fixed:85`, outcome CONVERGED via sync readback (no nudge delay) | PASS |
| 2026-07-21 | SM-G781B | Minified (R8) foss beta: startup + One UI detector reflection + legacy gate work. Uncovered a pre-existing R8 startup crash (`WorkDatabase_Impl.<init>` stripped) present on main; fixed via Room keep rule in the same branch | PASS after fix |
