# Architecture

Amply follows a **feature/core/ui** organization (as used by CAPod and Octi), with SD Maid SE's typed Shizuku
UserService boundary. This file, the code, and its comments are the source of truth for the design.

## Single Module

There is one Gradle module, `:app`. Do not add `app-common-*` / `app-tool-*` style modules — that is a different
project's convention. Code is grouped by **feature**, not framework layer.

## Package Map

```
eu.darken.amply
├── charging/core            policies, capability gate, OEM adapters, WSS + Shizuku access
│   ├── access/shizuku       Shizuku detection, user-service client, AIDL boundary
│   └── adapter              AdapterRegistry + per-OEM adapters (Pixel, Samsung, Xiaomi live; OnePlus lab-only)
├── fullcharge/core          temporary session, boot recovery, reconnect gesture, decision engines
├── diagnostics/core + ui    privileged before/after setting comparison, guided workflow
├── main
│   ├── core                 app-level wiring
│   └── ui                   MainActivity, onboarding, dashboard, settings, setup, tile, widget
└── common
    ├── AppDataStore         single process-safe Preferences DataStore owner
    ├── theming              brand / Material You / mode / contrast prefs
    ├── settings             reusable hierarchical settings rows + sections
    └── debug/logging        Logging fan-out + backends (Logcat, File)
```

Feature-specific preference facades live with their owning feature but share the one `AppDataStore` instance.

## Data Flow

- `AdapterRegistry` selects an OEM adapter from **immutable device information**. Live adapters declare a
  capability surface (`sessionOverridePolicy`, `defaultProtectivePolicy`, `verification`,
  `reconnectGestureSupported`) that the session/recovery/UI layers consume instead of hardcoding Pixel behavior.
- `AccessResolver` independently probes direct WSS and Shizuku.
- `ChargingRepository` selects the strongest backend per operation: **Shizuku for reads, direct WSS for durable
  writes, then Shizuku for verification** when both are available.
- **Settling state**: a successful write records `PendingRequest(target, requestedAt)`; surfaces show "applying…"
  until a `BATTERY_HARDWARE` verification *for that exact target* arrives or a 15s window elapses. A settings-level
  (Shizuku) readback, or a hardware reading for a *different* policy, does **not** clear it — the old policy
  legitimately still reads during the ~11–12s Pixel HAL transition. A WorkManager `SettleScheduler` fires one refresh
  at the window's end so the static widget/tile clear across process death.
- **Widget persistent-policy writes are atomic**: the ∞80% / ∞100% buttons route through a serialized
  `ACTION_SET_PERSISTENT_POLICY` command that cancels any running session **without restoring** and force-writes the
  chosen policy, so an explicit always-on choice never races the session's own writes.

## `ChargeObservation` is not a Boolean

State can be: verified, merely last-requested, unknown, unsupported, or blocked-on-setup. Hidden Pixel secure settings
are **never** described as verified from WSS-only access (Android blocks third-party reads of them).

On supported Pixels, `BatteryManager.EXTRA_CHARGING_STATUS` is consumed **only while external power is present**:
long-life (`4`) verifies the fixed limit is active, adaptive (`5`) verifies an active adaptive profile. Unplugged, the
sticky broadcast keeps its last powered value, so hardware state is never treated as verification — display falls back
to the last request. Normal (`1`) stays unknown without Shizuku (inactive adaptive vs. unrestricted are
indistinguishable).

## Samsung Adapters

Two live adapters, gated by `ro.build.version.oneui` ranges plus `protect_battery` presence plus system user
(all in world-readable `global` namespace; only writes need WSS):

- **Modern (One UI 8.x)**: `protect_battery` 0=off / 1=Maximum / 3=Standard(pause at 100%, resume 95%), plus
  `battery_protection_threshold` 80|85|90|95 (absent = 80, only valid ticks decode; malformed → Unknown).
  Policies: FixedLimit(80/85/90/95), PauseAtFull, Unrestricted. Session override = **PauseAtFull** (reaches 100%
  while keeping Samsung's own safety net). Threshold is written before mode.
- **Legacy (One UI 4.x/5.x)**: `protect_battery` 0/1 toggle, fixed 85% cap. Policies: FixedLimit(85),
  Unrestricted. Session override = Unrestricted.

Writes apply **synchronously** (`VerificationStrategy.SYNC_READBACK`): `apply()` requires read-back equality, no
pending-settle window, boot recovery converges on settings readback, and no reapply-inversion trick is needed.
The reconnect gesture is Pixel-only (`reconnectGestureSupported`). One UI 6/7 and 9+ fall through to the
diagnostics-only lab adapter. An external `protect_battery=0` makes One UI forget the user's prior mode (it falls back
to the OEM default on re-enable), so Amply restores the exact prior policy itself rather than trusting Samsung's
bookkeeping. Verified devices + coverage: see the qualification ledger in `privileged-access.md`.

## Xiaomi Adapter

One live adapter (`xiaomi-hyperos2-v1`), gated to the HyperOS ROM version (the setting is a ROM
feature, not a per-model one): manufacturer Xiaomi (covers Redmi/POCO — they report Xiaomi as
manufacturer) + `ro.mi.os.version.code == 2` (HyperOS 2.x) + system user. Use `ro.mi.os.version.code`,
NOT the frozen legacy `ro.miui.ui.version.code`. Single key
`secure/security_pc_secure_protect_mode_key`: `0`=charge fully, `1`=Intelligent (heuristic 80% hold →
`ChargePolicy.Adaptive`), absent=Intelligent (factory state). No hard-cap mode exists. SYNC_READBACK
with read-back equality; session override = Unrestricted; protective default = Adaptive. HyperOS 1,
pre-HyperOS MIUI, and a future HyperOS 3 fall to `XiaomiLabAdapter` (diagnostics + contribution). Two
documented assumptions: the feature is treated as present on any HyperOS 2 device (a device lacking it
reads the key absent → a harmless false claim of control), and daemon-level enforcement of external
writes is pending long-term observation (see the qualification ledger in `privileged-access.md`).

## Pixel Adapter

Writes **only** two secure settings:

- `secure/adaptive_charging_enabled`
- `secure/charge_optimization_mode`

Ordering matters:

- Fixed 80%: adaptive `0`, then mode `1`
- Unrestricted: mode `0`, then adaptive `0`
- Adaptive: mode `0`, then adaptive `1`

Google's Settings Intelligence worker applies external secure-setting changes **asynchronously** (measured
charging-HAL delay ≈ 11–12 s on tested Pixels). A same-package same-value write does **not** fire the settings
observer — re-writes briefly invert `charge_optimization_mode` before applying the target.

## Temporary Session & Recovery

- Before removing the limit, Amply persists the exact verified/requested protective policy (or the stored baseline).
- A `specialUse` foreground service monitors the sticky battery broadcast (~30 s) and restores on: full charge,
  disconnect-after-connection, a 15-minute arming timeout, or a 24-hour safety timeout.
- While active, the service watches the adapter's settings URIs; an unexpected native/system change **cancels without
  restoring**, so Amply never overwrites a newer external choice.
- Boot recovery runs the restore *inside the service* with a bounded convergence check (re-write until the HAL
  confirms or budget expires), because a boot-time write can race the observer registration. The pending target is
  persisted so a killed service resumes.

Decision logic is extracted into pure engines (`SessionDecisionEngine`, `BootRecoveryEngine`, `QuickFullChargeGesture`)
that are unit-tested on the JVM — keep new decision logic in these testable units, not buried in the service.

## Reconnect Gesture

Opt-in, with two arming bases:

- **Limit hold (default)**: the public battery broadcast **simultaneously** reports external power, charging-policy
  hardware state `4`, a non-charging battery status, and an expected limit-range level. Once latched during a plug
  period it survives option flips (the evidence was the hardware hold itself).
- **Any level (opt-in sub-option)**: plugged AND Amply's *persistent* configured policy
  (`ChargingPreferences.lastPersistentPolicy`, never updated by temporary session writes) is protective. Percent,
  battery status, and hardware hold are deliberately ignored. This basis is revoked immediately — including an open
  reconnect window — when the option is switched off or the persistent policy stops being protective.

A powered→unpowered transition opens a reconnect window of **2–10 seconds** (`elapsedRealtime`-based): the 2s
debounce floor filters momentary power cuts (car ignition, connector jostle), and a rejected too-fast/too-late replug
re-evaluates arming immediately. A reconnect inside the window starts the normal persisted session. Battery
evaluations are serialized through a single channel in `ChargeSessionService` — the receiver, 30s poll, and window
expiry nudge must never mutate `QuickFullChargeGesture` concurrently. A persistent notification is required because
Android does not deliver `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` to modern manifest receivers.

## Pitfalls

- Capability gate requires **all** of: Google manufacturer, supported Pixel 6a+ model, Android 15/API 35+, telephony
  capability, and a resolvable Settings Intelligence charging-optimization action. Pixel Tablet is excluded. Do not
  replace this runtime gate with an exact-model allowlist or a version-only check.
- Shizuku installation is detected by resolving the owner of `ShizukuProvider.PERMISSION`, **not** a fixed package
  name — this recognizes renamed forks and hidden-package mode. Don't hardcode a package name.
- OnePlus candidate keys exist in the privileged layer for a future lab adapter but **no production code invokes
  them**. Don't wire them into shipping paths. (Samsung and Xiaomi keys are live — see their adapter sections.)
