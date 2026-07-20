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
adb shell pm grant eu.darken.amply.debug android.permission.WRITE_SECURE_SETTINGS
```

(Use the `.debug` suffix for debug builds.) Alternatively start Shizuku, grant Amply access, and optionally use the
setup card to grant durable WSS.

## No Arbitrary Shell API

The Shizuku user service (`ChargingControlUserService`, AIDL `IChargingControlService`) exposes a **typed** interface
for get / put / WSS grant / diagnostic snapshots. Hard rules:

- `ProcessBuilder` receives **separate arguments** — no shell string is ever evaluated. Never construct a command
  string and pass it to a shell.
- Writes require a valid **namespace**, valid key/value **syntax**, and an explicit **key allowlist**. Do not widen
  the allowlist without an explicit, reviewed reason.
- Samsung / OnePlus candidate keys are present but **must not** be invoked by production code.

## Capability Gate

Direct Pixel control requires **all** of:

- Google manufacturer
- A Google-supported Pixel 6a-or-newer model (Pixel Tablet excluded)
- Android 15 / API 35 or newer
- Telephony capability
- A resolvable Settings Intelligence charging-optimization action

This runtime gate deliberately avoids both a brittle exact-model allowlist and an unsafe version-only match. Devices
that fail the gate remain **diagnostics-only**. Do not loosen or short-circuit the gate to "make it work" on an
unqualified device — record the device in `docs/PIXEL_SPIKE_RESULTS.md` instead.

## Foreground Service Requirement

The temporary override uses a `specialUse` foreground service because dormant apps cannot reliably receive
power-disconnect broadcasts. Consequences to respect:

- Force-stopping Amply or revoking its privilege can prevent restoration of the protective policy.
- **Google Play publication must stay gated on approval of the declared `specialUse` foreground-service use case.**

## Diagnostics

The Settings → Diagnostics workflow (Shizuku-only) captures before/after setting differences. Reports **redact common
identifiers** and include only setting differences — never widen what a diagnostic report emits.
