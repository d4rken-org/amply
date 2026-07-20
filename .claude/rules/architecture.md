# Architecture

Amply follows a **feature/core/ui** organization (as used by CAPod and Octi), with SD Maid SE's typed Shizuku
UserService boundary. Full prose design reference: `docs/ARCHITECTURE.md`. This file is the AI-actionable distillation.

## Single Module

There is one Gradle module, `:app`. Do not add `app-common-*` / `app-tool-*` style modules — that is a different
project's convention. Code is grouped by **feature**, not framework layer.

## Package Map

```
eu.darken.amply
├── charging/core            policies, capability gate, OEM adapters, WSS + Shizuku access
│   ├── access/shizuku       Shizuku detection, user-service client, AIDL boundary
│   └── adapter              AdapterRegistry + per-OEM adapters (Pixel is the only live one)
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

- `AdapterRegistry` selects an OEM adapter from **immutable device information**.
- `AccessResolver` independently probes direct WSS and Shizuku.
- `ChargingRepository` selects the strongest backend per operation: **Shizuku for reads, direct WSS for durable
  writes, then Shizuku for verification** when both are available.

## `ChargeObservation` is not a Boolean

State can be: verified, merely last-requested, unknown, unsupported, or blocked-on-setup. Hidden Pixel secure settings
are **never** described as verified from WSS-only access (Android blocks third-party reads of them).

On supported Pixels, `BatteryManager.EXTRA_CHARGING_STATUS` is consumed **only while external power is present**:
long-life (`4`) verifies the fixed limit is active, adaptive (`5`) verifies an active adaptive profile. Unplugged, the
sticky broadcast keeps its last powered value, so hardware state is never treated as verification — display falls back
to the last request. Normal (`1`) stays unknown without Shizuku (inactive adaptive vs. unrestricted are
indistinguishable).

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

Opt-in. The same foreground service arms only when the public battery broadcast **simultaneously** reports external
power, charging-policy hardware state `4`, a non-charging battery status, and an expected limit-range level. A
powered→unpowered transition opens a 10-second window; reconnecting inside it starts the normal persisted session. A
persistent low-priority notification is required because Android does not deliver `ACTION_POWER_CONNECTED` /
`ACTION_POWER_DISCONNECTED` to modern manifest receivers.

## Pitfalls

- Capability gate requires **all** of: Google manufacturer, supported Pixel 6a+ model, Android 15/API 35+, telephony
  capability, and a resolvable Settings Intelligence charging-optimization action. Pixel Tablet is excluded. Do not
  replace this runtime gate with an exact-model allowlist or a version-only check.
- Shizuku installation is detected by resolving the owner of `ShizukuProvider.PERMISSION`, **not** a fixed package
  name — this recognizes renamed forks and hidden-package mode. Don't hardcode a package name.
- Samsung / OnePlus candidate keys exist in the privileged layer for future lab adapters but **no production code
  invokes them**. Don't wire them into shipping paths.
