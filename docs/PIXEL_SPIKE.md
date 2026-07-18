# Pixel device qualification

Pixel 8 / Android 17 and Pixel 9 Pro / Android 16 passed the core direct-control gate on 2026-07-15; see [PIXEL_SPIKE_RESULTS.md](PIXEL_SPIKE_RESULTS.md). Direct control uses an official-device-floor plus runtime-capability gate, while this procedure grows the physical qualification matrix and catches build-specific regressions.

## 1. Characterize the native implementation

1. Record model, build fingerprint, Android version, Settings Intelligence version, and available native charge policies.
2. Capture `settings list secure`, `settings list system`, and `settings list global` through an authorized ADB shell.
3. Manually select fixed 80%, adaptive, and unrestricted in native Settings, capturing a snapshot after each transition.
4. Confirm the two candidate keys and look for additional changed values or side effects.
5. Pull the device's Settings Intelligence APK and inspect the charging controller with JADX/APK Analyzer for binder calls, broadcasts, or controller work beyond the settings writes.

## 2. Validate hardware behavior

Exercise the raw candidate transitions while unplugged and while charging below 80%, near 80%, and above 80%. Repeat with wired and wireless charging. For every transition record:

- native Settings UI state;
- battery status, current and charge counter from `dumpsys battery`/BatteryManager;
- whether charging starts or stops without a replug;
- observable logs from relevant battery/health components;
- transition latency and any error.

The gate passes only if table writes affect the real charging policy, not just the Settings UI.

## 3. Validate Amply access tiers

1. Grant only WSS: verify writes work, hidden reads remain truthfully “Requested,” and the grant survives reboot/update.
2. Revoke WSS and use only Shizuku: verify authoritative reads, writes, denial handling, binder death, and Shizuku restart behavior.
3. Enable both: verify direct writes followed by Shizuku readback.
4. Change the native setting outside Amply and confirm refresh never claims stale state as verified.

## 4. Validate one-time sessions

Start from app, tile, and widget while plugged and unplugged. Cover full charge, early disconnect, manual restore, 15-minute arm expiry, 24-hour safety expiry, process death, service restart, reboot, Shizuku death, WSS revocation, and manual native-setting changes.

Pixel may periodically exceed 80% for battery calibration; this is informational and must not be classified as an adapter failure.

## Go/no-go

- **Go:** direct writes reliably control hardware and restore safely. Enable only the tested model/Android-major support row.
- **Shizuku-only:** if Settings Intelligence requires an additional safe operation that can be invoked through the typed service, disable WSS control for Pixel.
- **No-go:** if no stable safe operation controls hardware, retain only the native Settings shortcut and stop direct-control release work.
