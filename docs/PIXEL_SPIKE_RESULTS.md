# Pixel physical-device spike results

Date: 2026-07-15

## Device under test

- Google Pixel 8 (`shiba`)
- Android 17 beta, API 37
- Build `CP21.260306.017`
- Settings Intelligence `1.1.0.915935889.sr` (`1000291841`)
- Wired AC power, battery at 80–81%

No device serial or user data is retained in this report.

## Native mapping

Diffing complete secure/global/system snapshots around native UI changes isolated the charging transition to two secure settings:

| Native policy | `charge_optimization_mode` | `adaptive_charging_enabled` |
| --- | ---: | ---: |
| Limit to 80% | 1 | 0 |
| Adaptive charging | 0 | 1 |
| Optimization off / unrestricted | 0 | 0 |

The Settings Intelligence APK declares `WRITE_SECURE_SETTINGS`. Its bytecode reads both keys and registers a content-URI-triggered `ChargingPolicyUpdateWorker` for `charge_optimization_mode`. No additional setting mutation was associated with the native policy transition.

## Hardware result

Raw `settings put secure` transitions controlled the actual charging hardware without a cable replug:

| Requested policy | Battery/HAL observation |
| --- | --- |
| Limit to 80% | `Not charging`, sysfs `charging_policy=4`, near-zero/negative battery current |
| Unrestricted | `Charging`, sysfs `charging_policy=1`, sustained roughly 1.7–2.1 A input |
| Adaptive | Settings mapped to `0/1`; at the test time the device resumed charging, as expected for its active schedule |

External writes are asynchronous. Controlled round trips took 12 seconds to reach unrestricted policy 1 and 11 seconds to return to fixed-limit policy 4. The secure-setting values change immediately, so readback verifies the requested setting but not instantaneous HAL convergence.

## Amply result

The `fossDebug` APK was installed on the device and granted only `WRITE_SECURE_SETTINGS` from ADB.

- WSS-only reads were blocked by Android and Amply truthfully displayed `Requested` rather than `Verified`.
- Amply's persistent Unrestricted and Limit to 80% buttons reached HAL policies 1 and 4, respectively.
- A one-time full-charge session stored Limit to 80% as its restore target, started an Android 17 `specialUse` foreground service, posted its ongoing notification, and reached unrestricted charging.
- Manual restore returned to HAL policy 4 and removed the service and notification.
- A reversible BatteryService disconnect simulation triggered automatic restore and stopped the session. BatteryService was reset to the physical AC-powered state afterward.
- The original native policy, Limit to 80%, was restored at the end of testing.

A follow-up UI check found that retaining the last WSS-only request could still be misleading after a native Settings change. Amply now observes the public `ACTION_BATTERY_CHANGED` charging-state extra. Pixel's active fixed-limit profile reports long-life state `4`, so native 80% changes are displayed live without Shizuku. Hardware state `1` is deliberately shown as unknown because a configured but currently inactive adaptive policy produced the same state as unrestricted charging during the probe.

Shizuku 13.6 was installed but its privileged server was not running. This device had no active Wi-Fi connection, so its wireless-debugging startup and permission/readback path could not be exercised in this run. WSS does not depend on Shizuku after the one-time ADB grant.

## Verdict and remaining coverage

The core idea is feasible with `WRITE_SECURE_SETTINGS`; Shizuku is optional for setting readback and in-app WSS granting. Direct control is capability-gated to officially supported Pixel 6a-or-newer phones on Android 15+ with Google's charging-optimization controller present. The physical qualification matrix remains narrower than that supported capability range.

Before widening support or declaring release qualification, still cover wireless charging, below-80% transitions, a natural 100% completion, reboot recovery, full timeout durations, process death, tile/widget entry points, and Shizuku-only/both-backend operation on a Wi-Fi-connected device.

## Pixel 9 Pro qualification

Also tested on 2026-07-15:

- Google Pixel 9 Pro (`caiman`)
- Android 16, API 36
- Build `CP1A.260505.005`
- Settings Intelligence `1.1.0.937862210.sr` (`1000299141`)
- Wired AC power, battery at 84–85%

The runtime capability gate resolved Google's charging-optimization activity/action and enabled Amply. With ADB-granted WSS, Amply's own policy buttons produced:

| Amply request | Secure values | Battery/HAL result | Latency |
| --- | --- | --- | ---: |
| Limit to 80% | mode `1`, adaptive `0` | `Not charging`, long-life state `4` | 12 s |
| Unrestricted | mode `0`, adaptive `0` | `Charging`, normal state `1` | 12 s |
| Adaptive restore | mode `0`, adaptive `1` | Original policy restored | — |

This independently confirms the same setting mapping and asynchronous Settings Intelligence worker path on Pixel 9 Pro / Android 16. The device began and ended with Adaptive charging selected.

Shizuku 13.5.4 was installed on this device, but its legacy USB starter did not keep the privileged server alive on the Android 16 build. Amply's WSS path did not depend on Shizuku and passed qualification.
