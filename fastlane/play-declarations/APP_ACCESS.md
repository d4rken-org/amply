# Play Console — App access

Form: Play Console → App content → **App access**

Amply has no login, no account, and no region lock, so **"All functionality is available without
special access"** is the correct answer for the *access* question itself. But Amply's headline
feature is gated by the device and by a permission a reviewer cannot grant from the phone, so add
the instructions below so a reviewer does not conclude the app is broken.

Paste as an instruction entry (name it e.g. "Direct charge control — device gated"):

---

Amply needs no account or login. Every screen is reachable immediately after install.

Two things about this app will look unusual during review:

**1. The main feature depends on the device.** Amply controls the charge-protection setting built
into the phone's own system software. That setting exists only on certain manufacturers and system
versions, so on any other device Amply deliberately reports that it cannot control charging and
falls back to read-only information. This is intended behaviour, not an error. It is stated in the
store listing and on the app's first screen. On a device without support you can still review the
battery details screen, the charge alarm, the settings, and the guided setup — none of them need
any grant.

**2. Direct control requires `WRITE_SECURE_SETTINGS`, granted from a computer.** The permission is
declared in the manifest but is not grantable from a phone UI; the user grants it once over ADB
from a computer, or through Shizuku. Amply cannot and does not obtain it by itself, and it works
without it (with reduced functionality). Amply's setup screen shows the exact command.

To review the full feature set on a supported device (a Pixel 6a or newer running Android 15 or
newer), grant it once with the phone connected to a computer:

    adb shell pm grant eu.darken.amply android.permission.WRITE_SECURE_SETTINGS

The app then enables its charge-policy controls; no restart or further setup is needed.

---

## Related

The `WRITE_SECURE_SETTINGS` declaration is also the reason the app's typed privileged boundary
matters: Amply never executes shell strings and writes only an explicit allowlist of settings keys.
That is repo documentation, not something the Console asks for, but it is the honest answer if
review comes back asking what the app does with the permission.
