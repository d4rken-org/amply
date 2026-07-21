# Privileged Access & Safety Boundary

Amply writes hidden/secure Android settings to control OEM charge protection. This is the highest-risk surface in the
app. Read this before modifying anything under `charging/core/access/`, the AIDL, or the capability gate.

## Two Access Paths

`AccessResolver` probes both independently:

1. **Direct `WRITE_SECURE_SETTINGS`** — durable writes. Android blocks third-party *reads* of the hidden Pixel values,
   so WSS-only control can write but cannot verify hidden state.
2. **Shizuku** — provides exact configured-setting readback while running, and is preferred for reads and
   verification.

`ChargingRepository` combines them: Shizuku for reads, direct WSS for durable writes, Shizuku for verification.

Granting WSS in development:

```bash
adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS
```

(All variants share the applicationId `eu.darken.amply`.) Alternatively start Shizuku, grant Amply access, and
optionally use the setup card to grant durable WSS.

## No Arbitrary Shell API

The Shizuku user service (`ChargingControlUserService`, AIDL `IChargingControlService`) exposes a **typed** interface
for get / put / WSS grant / diagnostic snapshots. Hard rules:

- `ProcessBuilder` receives **separate arguments** — no shell string is ever evaluated. Never construct a command
  string and pass it to a shell.
- Writes require a valid **namespace**, valid key/value **syntax**, and an explicit **key allowlist**. Do not widen
  the allowlist without an explicit, reviewed reason.
- Every writable key carries an explicit **per-key value domain** (`SettingWritePolicy`) — the boundary itself
  rejects out-of-domain values. The Samsung keys (`global protect_battery`, `global battery_protection_threshold`)
  and the Xiaomi key (`secure security_pc_secure_protect_mode_key`) are **live** on gated devices (see Capability
  Gates). OnePlus candidate keys remain present but **must not** be invoked by production code.

## Capability Gates

### Pixel

Direct Pixel control requires **all** of:

- Google manufacturer
- A Google-supported Pixel 6a-or-newer model (Pixel Tablet excluded)
- Android 15 / API 35 or newer
- Telephony capability
- A resolvable Settings Intelligence charging-optimization action

This runtime gate deliberately avoids both a brittle exact-model allowlist and an unsafe version-only match. Devices
that fail the gate remain **diagnostics-only**. Do not loosen or short-circuit the gate to "make it work" on an
unqualified device — record the device in `docs/PIXEL_SPIKE_RESULTS.md` instead.

### Samsung

Samsung control requires **all** of: Samsung manufacturer, a **verified One UI range** (One UI 8.x for the
multi-mode adapter; One UI 4.x/5.x for the legacy toggle adapter — read from `ro.build.version.oneui`), a present
`global protect_battery` key, and the **system user** (the keys are device-wide; sessions are per-user). One UI
6.x/7.x and 9.x+ are unverified and fall through to the diagnostics-only lab adapter — do not widen the ranges
without a qualified device; record results in `docs/SAMSUNG_SPIKE_RESULTS.md`.

Unlike Pixel's hidden secure settings, Samsung's `global` keys are world-readable: configured-state verification
works without Shizuku, and writes apply synchronously (no Settings-Intelligence-style async middleman).

### Xiaomi

Xiaomi control requires **all** of: Xiaomi manufacturer, the exact qualified model (`2306EPN60G`),
`ro.miui.ui.version.code == 816`, and the system user. The version code identifies a software family, not a
build — do not widen to a code-only or range gate without qualifying additional devices; record results in
`docs/XIAOMI_SPIKE_RESULTS.md`. The single key is per-user `secure`, applied synchronously; daemon-level
enforcement of external writes is pending long-term observation (see the spike doc).

## Foreground Service Requirement

The temporary override uses a `specialUse` foreground service because dormant apps cannot reliably receive
power-disconnect broadcasts. Consequences to respect:

- Force-stopping Amply or revoking its privilege can prevent restoration of the protective policy.
- **Google Play publication must stay gated on approval of the declared `specialUse` foreground-service use case.**

## Diagnostics

The Settings → Diagnostics workflow (Shizuku-only) captures before/after setting differences. Reports **redact common
identifiers** and include only setting differences — never widen what a diagnostic report emits.
