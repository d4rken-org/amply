# Architecture

Amply follows the feature/core/ui organization used by CAPod and Octi, CAPod's short-lived tile and Glance entry-point patterns, and SD Maid SE's typed Shizuku UserService boundary.

## Package layout

Code is grouped by feature rather than framework layer:

- `charging/core`: policies, device capability checks, OEM adapters, WSS, and Shizuku
- `fullcharge/core`: temporary sessions, recovery, and the reconnect gesture
- `main/ui`: activity, onboarding, dashboard, settings screens, setup guide, tile, and widget
- `diagnostics/core` and `diagnostics/ui`: privileged settings comparison and its guided UI
- `common/theming`: persisted brand, Material You, mode, and contrast preferences
- `common/settings`: reusable hierarchical settings rows and section components
- `common/debug`: opt-in debug sessions and logging backends
- `common`: the shared DataStore owner and cross-feature primitives

Feature-specific preference facades live with their owning feature while sharing one process-safe DataStore instance.

## UI shell

The root theme uses explicit paired light/dark brand schemes so every foreground color has a matching surface color. Material You is an opt-in theme style rather than an implicit device-dependent default. Theme mode, style, and accent are persisted in DataStore and exposed from General settings.

First run presents the product intent across two pages: a welcome page and a caveat page covering device-by-device support and the one-time access setup. The interactive WSS/Shizuku setup guide is not shown during onboarding — it is rendered on the dashboard whenever durable WSS is absent. Settings use an index plus focused General, Support, and Acknowledgements sub-screens; the changelog, privacy policy, and project-help destinations are external links (`AmplyLinks` holds the project URLs). The developer-facing setting-discovery workflow lives in a Diagnostics sub-screen and is only listed when an installed package declares Shizuku's API permission.

Shizuku installation detection intentionally resolves the owner of `ShizukuProvider.PERMISSION` rather than checking a fixed package name. Permissions use a global namespace, so this recognizes renamed forks and Shizuku's hidden-package mode without broad installed-package visibility.

## Debug logging

`Logging` fans structured events out to independent backends. Debug builds always attach Logcat; Support can attach a synchronized file logger after an explicit consent dialog. Stopping a recording packages the event log and basic build/device metadata into a zip in private cache storage. Nothing is transmitted automatically: Android's share sheet is opened only after a separate user action, and completed recordings can be deleted in-app.

## Data flow

`AdapterRegistry` selects an OEM adapter from immutable device information; live adapters declare a capability surface (session override policy, protective default, verification strategy, reconnect-gesture support) consumed by the session, recovery, and UI layers. `AccessResolver` independently probes direct WSS and Shizuku. `ChargingRepository` selects the strongest backend per operation: Shizuku for reads, direct WSS for durable writes, then Shizuku for verification when both are available.

`ChargeObservation` is deliberately not a Boolean. A state can be verified, merely last-requested, unknown, unsupported, or blocked on setup. Hidden Pixel secure settings are never described as verified from WSS-only access. On supported Pixels, Amply also consumes `BatteryManager.EXTRA_CHARGING_STATUS`, but only while external power is present: long-life (`4`) verifies that the fixed limit is active and adaptive (`5`) verifies an active adaptive profile. Unplugged, the sticky broadcast retains its last powered value, so hardware state is never treated as verification and the display falls back to the last request. Normal (`1`) remains unknown without Shizuku because inactive adaptive charging and unrestricted charging are indistinguishable.

Because the charging HAL applies a setting asynchronously (measured 11–12 s), a successful write records a `PendingRequest(target, requestedAt)` alongside the observation. `ChargingState.isSettling(now)` reports "applying" until either a `BATTERY_HARDWARE` verification *for that exact target* arrives or a 15-second window elapses — a settings-level (Shizuku) readback or a hardware reading for a different policy does not count, so the dashboard hero card and widget honestly show "waiting for the system" rather than a premature confirmation. A `SettleScheduler` (WorkManager, unique-replaceable) fires one refresh at the window's end so surfaces that do not observe the state flow (the static widget, the tile) still clear across process death; the in-app dashboard additionally runs a local clock for a prompt countdown.

While a temporary session is active, the foreground service observes the adapter's settings URIs. An unexpected native/system change cancels the session without restoring, so Amply cannot overwrite a newer external choice. The widget's persistent-policy buttons route through a serialized `ACTION_SET_PERSISTENT_POLICY` service command that cancels any running session without restoring and force-writes the chosen policy, so an explicit "always 80%/100%" choice is atomic rather than racing the session's own writes.

## Samsung adapters

Two live adapters drive the world-readable `global` keys `protect_battery` (0=off, 1=Maximum, 3=Standard/pause-at-full) and `battery_protection_threshold` (80/85/90/95): a One UI 8.x multi-mode adapter (session override: pause-at-full) and a One UI 4.x/5.x legacy toggle adapter (fixed 85% cap). Writes apply synchronously and verify by read-back equality; unverified One UI generations fall through to the diagnostics-only lab adapter. Gated to the system user because the keys are device-wide while sessions are per-user. Ground truth: `SAMSUNG_SPIKE_RESULTS.md`.

## Xiaomi adapter

One live adapter gated to the HyperOS ROM version (Xiaomi manufacturer, which also covers Redmi/POCO, + `ro.mi.os.version.code == 2` = HyperOS 2.x, + system user) — the charge-protection setting is a ROM feature, not a per-model one. Single per-user `secure` key `security_pc_secure_protect_mode_key`: 0=charge fully, 1=Intelligent charging (heuristic 80% hold, mapped to the Adaptive policy); absent means Intelligent. Synchronous read-back verification; daemon-level enforcement of external writes is pending long-term observation. HyperOS 1, pre-HyperOS MIUI, and a future HyperOS 3 fall to the diagnostics-only lab adapter. A HyperOS 2 device that lacks the feature also reads the key absent → a documented, harmless false claim of control. Ground truth: `XIAOMI_SPIKE_RESULTS.md`.

## Pixel adapter

The adapter writes only:

- `secure/adaptive_charging_enabled`
- `secure/charge_optimization_mode`

Fixed 80% writes adaptive `0` then mode `1`; unrestricted writes mode `0` then adaptive `0`; adaptive writes mode `0` then adaptive `1`. The mapping and real charging behavior passed the physical-device gate on Pixel 8 / Android 17 (API 37) and Pixel 9 Pro / Android 16 (API 36). Google's Settings Intelligence worker applies external secure-setting changes asynchronously; measured charging-HAL delay was 11–12 seconds.

Control requires all of: Google manufacturer, a Google-supported Pixel 6a-or-newer phone model, Android 15/API 35 or newer, telephony capability, and a resolvable Settings Intelligence charging-optimization action. This runtime capability gate avoids both a brittle exact-model allowlist and unsafe Android-version-only matching. Pixel Tablet is excluded.

## Temporary session

Before removing the limit, Amply persists the exact verified/requested protective policy, or the user's stored protective baseline. A `specialUse` foreground service monitors the sticky battery broadcast every 30 seconds and restores on full, disconnect after a connection, a 15-minute arming timeout, or a 24-hour safety timeout. Boot recovery attempts a direct restore and leaves a recovery notification when Shizuku must be restarted. Because a boot-time settings write can race Settings Intelligence's observer registration and never reach the charging HAL, the restore runs inside the service with a bounded convergence check: while the hardware state is trustworthy it re-writes until the HAL confirms or a budget expires, otherwise it sends one delayed re-write nudge; the pending target is persisted so a killed service resumes the check. Re-writes briefly invert `charge_optimization_mode` before applying the target, because a same-package same-value write does not fire the settings observer at all.

The same service powers the opt-in reconnect gesture. By default it arms only when the public battery broadcast simultaneously reports external power, charging-policy hardware state `4`, a non-charging battery status, and an expected limit-range battery level. An opt-in "any charge level" option instead arms whenever the device is plugged and Amply's persistently configured policy is protective; percent, battery status, and the hardware hold are deliberately ignored, and this arming basis — including an already-open reconnect window — is revoked the moment the option is switched off or the persistent policy stops being protective. A powered-to-unpowered transition opens a reconnect window of 2–10 seconds measured on `elapsedRealtime`: the two-second debounce floor filters momentary power cuts such as a car cutting accessory power during engine start, and a rejected too-fast or too-late replug immediately re-evaluates arming. Reconnecting inside the window starts the normal persisted temporary session. Battery evaluations are serialized through a single channel so the broadcast receiver, the 30-second poll, and the window-expiry nudge can never observe plug transitions out of order. A persistent notification is required because Android does not deliver `ACTION_POWER_CONNECTED` and `ACTION_POWER_DISCONNECTED` to modern manifest receivers.

## Privileged boundary

The Shizuku process exposes a typed AIDL interface for get, put, WSS grant, and diagnostic snapshots. `ProcessBuilder` receives separate arguments; no shell string is evaluated. Writes require a valid namespace, key syntax, and an explicit per-key value domain (`SettingWritePolicy`) — the boundary rejects out-of-domain values itself. Samsung and Xiaomi keys are live on gated devices; the OnePlus candidate key is present for future lab work but no production code invokes it.
