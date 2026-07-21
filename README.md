# Amply

Amply is an experimental Android controller for OEM battery charge-protection modes. Its primary action temporarily allows a full charge, then restores the user's protective policy at 100%, on unplug, or at a safety timeout.

Three control adapters exist. Pixel charging optimization is capability-gated to Pixel 6a and newer phones on Android 15 or newer when Google's charging-optimization controller is present. Samsung battery protection is gated to verified One UI generations (One UI 8, and the legacy One UI 4/5 toggle) on the device's main user. Xiaomi charging protection is gated to the qualified Xiaomi 13T on HyperOS 2.0. Pixel combinations without that capability, Samsung devices on unverified One UI versions (6/7, 9+), unqualified Xiaomi devices, and OnePlus/Oppo remain diagnostics-only.

## Capabilities

- App dashboard with truthful verified/requested/unknown state
- Branded light/dark themes, opt-in Material You, and contrast choices
- First-run welcome and caveat pages; the interactive setup guide lives on the dashboard and returns whenever durable WSS access is missing
- Hierarchical General, Support, and acknowledgements settings; changelog and privacy policy link to the project website
- Explicitly consented local debug-log recording and sharing
- Optional “reconnect for 100%” gesture at the active 80% hardware limit
- Quick Settings tile for “100% once” and restore
- Glance widget with 80% protection and one-time full-charge actions
- `WRITE_SECURE_SETTINGS` and Shizuku access paths
- Persisted, user-visible temporary-session monitor with reboot recovery
- Conditional Settings → Diagnostics workflow for Shizuku-only before/after discovery with redacted reports
- `foss` and `gplay` product flavors

## Setup

Build and install a debug flavor, then choose one access path:

```text
adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS
```

Alternatively, start Shizuku, grant Amply access, and optionally use Amply's setup card to grant durable WSS. WSS-only control can write Pixel's hidden values but Android blocks direct third-party reads. Amply therefore also watches Android's public charging-hardware state: the active Pixel 80% profile is reported live, while a normal profile is left unknown because it could mean unrestricted or currently inactive adaptive charging. Shizuku provides exact configured-setting readback while it is running. On tested Pixels, Google's policy worker takes roughly 10–15 seconds to propagate a setting change to the charging HAL.

The optional reconnect gesture runs a low-work foreground monitor. Once Android reports that charging is being held by the 80% policy, unplugging and reconnecting within 10 seconds starts the existing one-time 100% session. Ordinary charging, stale cached policy state, temperature limits, and slow reconnects do not trigger it.

## Build

```shell
./gradlew testFossDebugUnitTest assembleFossDebug
./gradlew testGplayDebugUnitTest assembleGplayDebug
```

Compile/target SDK is 36, minimum SDK is 26. Build and test on JDK 21 (Robolectric needs it for SDK 36); compiled
bytecode targets Java 17.

## Safety boundary

Amply has no arbitrary shell API. Its Shizuku user service executes argument-separated commands, validates namespaces and values, and allowlists every writable setting. Diagnostic reports redact common identifiers and include only setting differences.

The temporary override relies on a foreground service because dormant apps cannot receive power-disconnect broadcasts reliably. Force-stopping Amply or revoking its privilege can prevent restoration. Google Play publication must remain gated on approval of the declared `specialUse` foreground-service use case.

See [docs/PIXEL_SPIKE_RESULTS.md](docs/PIXEL_SPIKE_RESULTS.md) for the physical Pixel results, [docs/PIXEL_SPIKE.md](docs/PIXEL_SPIKE.md) for the qualification procedure, and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for implementation details.

## License

GPL-3.0-or-later.
