# Architecture

Amply follows a **feature/core/ui** organization (as used by CAPod and Octi), with SD Maid SE's typed Shizuku
UserService boundary. This file, the code, and its comments are the source of truth for the design.

## Single Module

There is one Gradle module, `:app`. Do not add `app-common-*` / `app-tool-*` style modules — that is a different
project's convention. Code is grouped by **feature**, not framework layer.

## Package Map

See "Package Layout" in `.claude/CLAUDE.md`.

Feature-specific preference facades live with their owning feature but share the one `AppDataStore` instance. They
declare their settings with the `createValue()` DSL (`common/datastore`) rather than touching `store.data` — with a
single shared store, every write reaches every collector, so the deduplication has to live in the primitive. See
`code-style.md`.

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
- **Plug-latched pending** (adapters with `policyLatchesAtPlug`, GrapheneOS): the ROM samples the configured policy
  only at plug-session start, so a write made while plugged carries `PendingRequest(awaitingReplug = true)` — a
  *condition*, not a countdown, with **no expiry**. It resolves only on evidence (`computeRefreshPending`'s latched
  arm): written-unplugged, an observed unplug (live, or the persisted `unpluggedSeenAt` watermark), hardware state 4
  for the exact target, or — for full-charge targets — the battery observably charging *above* the adapter's cap.
  Surfaces show a "replug to apply" hint instead of a spinner; the hint may linger while nothing observes a replug
  (accepted staleness), but the reverse error — claiming applied when not — cannot occur.
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

## OEM Adapters

Per-adapter detail (Samsung, Xiaomi, OnePlus/ColorOS, LineageOS, GrapheneOS, Pixel — keys, value domains, write
ordering, session overrides) lives in the **`oem-adapters` skill** — read it before changing anything under
`charging/core/adapter`.

## Temporary Session & Recovery

- Before removing the limit, Amply persists the exact verified/requested protective policy (or the stored baseline).
- A `specialUse` foreground service monitors the sticky battery broadcast (~30 s) and restores on: full charge,
  disconnect-after-connection, a 15-minute arming timeout, or a 24-hour safety timeout.
- **Replug grace window** (plug-latched adapters only): a disconnect does not restore immediately — the engine emits
  `MARK_DISCONNECTED` and opens a persisted 30s window (`REPLUG_GRACE_MILLIS`, wall clock, survives process death);
  a replug inside it emits `MARK_REPLUGGED` and continues the session (the plug transition is what latches the
  override), expiry or a backwards clock restores as before. `full` and the 24h safety timeout keep priority. On
  every other adapter `replugGraceMillis` is 0 and the decision table is unchanged.
- While active, the service watches the adapter's settings URIs; an unexpected native/system change **cancels without
  restoring**, so Amply never overwrites a newer external choice. Cancellation requires a **real** change
  (`NativeChangeGuard`): settings notifications are dispatched asynchronously, so the session's own override write can
  arrive after the observer registers, and an OEM provider may notify without any value change (both observed on
  HyperOS 3 `tanzanite`, issue #48 — blind cancellation ended the session with the protective policy never restored).
  On sync-readback adapters a notification whose readback still decodes to the session override is ignored as noise;
  without sync readback (Pixel) any notification still cancels.
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
- Pixel/Samsung/Xiaomi/Oplus keys are all live on gated devices (see the `oem-adapters` skill). New writable keys must
  be spike-verified and added to `SettingWritePolicy` with an explicit per-key value domain.
