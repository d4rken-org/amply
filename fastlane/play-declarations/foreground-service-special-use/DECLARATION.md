# Declaration — Foreground service: `specialUse`

App: **Amply** (`eu.darken.amply`)
Form: Play Console → App content → **Foreground service permissions** (Monitor and improve → App content)

Declared permission: `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`
Service: `eu.darken.amply.fullcharge.core.ChargeSessionService` (`android:foregroundServiceType="specialUse"`)

The manifest already carries the matching `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value; the Console text
below must stay consistent with it. If one changes, change both.

---

## 1. Use case to select

The pre-set list has no entry that fits, so enter the use case manually:

> **Restoring a user's battery charge-protection setting when a temporary override ends.**

---

## 2. "Describe the app functionality that is using this foreground service type"

Amply switches the charge-protection mode that the phone's own system software provides (for
example a limit that stops charging around 80% to slow battery ageing).

Its main feature is a **temporary** override: the user asks for one full charge, Amply lifts their
protective limit, and then **puts the limit back automatically** — at 100%, the moment the charger
is unplugged, or after a safety timeout. Between those two points the user's protection is
deliberately switched off, and only Amply can switch it back on.

The foreground service exists to supervise exactly that window. While it runs it:

1. **Restores the protective policy** when the charge completes, when the cable is pulled, or when
   a 15-minute arming / 24-hour safety timeout expires — this is the whole point of the service.
2. **Detects a deliberate unplug/replug gesture** (opt-in) that starts a one-time full charge
   without opening the app.
3. **Alerts the user to unplug** when the battery reaches a level they picked (opt-in charge alarm).
4. **Records local charge measurements** for the in-app charging history (opt-in, on-device only).

The service never starts on its own. It starts only from an explicit user action — starting a
one-time full charge, enabling the reconnect gesture, or setting a charge alarm — and it stops
itself when that work is finished. While running it shows an ongoing notification stating what it
is doing, with a **"Restore now"** action so the user can end it immediately.

The reason this needs a foreground service, rather than a background job or a broadcast receiver,
is that Android does not deliver `ACTION_POWER_CONNECTED` / `ACTION_POWER_DISCONNECTED` to
manifest-registered receivers on modern versions, and an app in a dormant/cached state cannot
observe the unplug at all. The unplug is the event that must trigger the restore. If the app is not
running at that moment, the user's charge protection stays switched off after they walk away with
their phone.

---

## 3. "What is the user impact if the task is deferred (does not start immediately)?"

The protective limit is lifted at the moment the user confirms the one-time full charge. If the
supervising service starts late, the phone is already charging past the user's limit with nothing
watching for the stop conditions. A charge that finishes during that gap is never noticed, so the
protective policy is not restored: the battery is left sitting at 100% and the phone keeps charging
without protection until the user next opens the app. That is precisely the battery-ageing harm the
app exists to prevent, and the user has no way to see that it happened.

## 4. "What is the user impact if the task is interrupted (paused and/or restarted)?"

Amply persists the pending restore target, so a restarted service re-converges on it and a reboot
triggers the same recovery path. But nothing can recover the events missed while it was not
running: an unplug that happens during the interruption is not delivered to the app at all, so a
restore that should have happened the moment the cable was pulled is postponed until the app runs
again. The user's protection stays off for that entire period without any indication. For the same
reason, an interrupted charge alarm silently fails to alert at the level the user chose, and the
opt-in charging history records a gap.

---

## 5. Why no other foreground service type fits

`specialUse` is used only because every defined type was ruled out:

| Type | Why it does not apply |
|---|---|
| `dataSync` | No data is transferred or synchronized. It is also runtime-capped (Android 15 limits it to ~6h/day), which cannot span an overnight charge or the 24-hour safety timeout. |
| `shortService` | Capped at a few minutes; a charge cycle is hours. |
| `connectedDevice` | Concerns interaction with an external device over Bluetooth/USB/etc. A wall charger is not a device the app communicates with; Amply reads the phone's own battery state. |
| `health` | Reserved for the user's fitness/health data. This is device battery state. |
| `systemExempted` | Reserved for system-level/exempted apps; Amply is an ordinary third-party app. |
| `location`, `camera`, `microphone`, `mediaPlayback`, `mediaProjection`, `phoneCall`, `remoteMessaging` | No such functionality exists in the app. |

This is the "limited scenarios" case described in the Play Console guidance: the work meets the
characteristics required of a foreground service (user-initiated, user-visible, must complete
promptly, and interrupting it causes user-visible harm) but matches no defined type.

---

## 6. Demo video

Produced by `./record.sh` (see [`../README.md`](../README.md)). Recorded on a physical Pixel 7a,
portrait 720×1600, ~64 s including title and end cards. The rendered file sits next to this
document as `declaration.mp4` and is git-ignored — upload it, don't commit it.

The video demonstrates the **unplug** stop condition rather than the 100% one. That is deliberate:
it is the condition that cannot work without a running foreground service, because Android does not
deliver the disconnect to a dormant app at all. It also needs no simulated charge progression, so
nothing on screen is faked beyond the disconnect itself.

| # | Shot | On screen |
|---|------|-----------|
| 1 | **Title card** | "Amply — eu.darken.amply — Restoring a battery charge limit after a temporary full charge (FOREGROUND_SERVICE_SPECIAL_USE)". |
| 2 | **Starting state** | Dashboard: plugged in, "80% limit active", confirmed by the charging hardware. |
| 3 | **User action** | The user taps **"Charge to 100% once"** — the explicit action that starts the service. |
| 4 | **Service is running and visible** | The notification shade shows the ongoing notification: "Charging to 100% once — Charge protection returns at 100% or when unplugged", with its **"Restore now"** action. |
| 5 | **Protection is off** | The dashboard shows the override is in effect: the phone would now charge past 80%. |
| 6 | **The user unplugs** | The charger is disconnected — the event a dormant app would never see. |
| 7 | **Automatic restore** | The service notices, writes the protective policy back **by itself**, and the dashboard returns to the 80% limit with no user action. |
| 8 | **The service stops** | The shade shows the notification gone: the service ran only as long as its work lasted. |
| 9 | **Verified end state** | Plugged back in, the dashboard reads "80% limit active — Confirmed by Android's charging hardware". |
| 10 | **End card** | "The protective limit returns automatically at 100%, on unplug, or after a safety timeout." |

Shots 6–7 are the policy-critical moment: they show the work that is lost if the service is deferred
or interrupted. Shots 3–4 show it is user-initiated and user-visible; shot 8 shows it does not
linger.

**The disconnect and reconnect are driven with `adb shell dumpsys battery unplug` / `reset`** so the
recording does not depend on physically pulling the cable of the device running the screen capture.
Everything else — the service, its notification, the restore decision, the settings write, and the
hardware confirmation at the end — is the real app on real hardware. `record.sh` reads the
underlying system setting before, during and after the take and refuses to accept a run where the
limit was not actually lifted and restored.

---

## 7. Keep consistent

- **Store listing** (`fastlane/metadata/android/en-US/full_description.txt`) must keep documenting
  the one-time full charge and the automatic restore prominently — Google cross-checks the declared
  functionality against the listing.
- **Manifest** `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` must keep describing the same four functions.
- Re-submit the declaration whenever the service's responsibilities change.

Policy reference: <https://support.google.com/googleplay/android-developer/answer/13392821>
