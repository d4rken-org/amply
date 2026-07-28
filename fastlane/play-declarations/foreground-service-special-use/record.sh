#!/usr/bin/env bash
# Record the specialUse foreground-service declaration screencast.
#
# The video shows the one thing the service exists for: the user starts a
# one-time full charge (their 80% protection is switched off), an ongoing
# notification shows the service supervising it, and at 100% the service puts
# the protective limit back by itself.
#
# Runs against a PHYSICAL, capability-gated Pixel — an emulator fails the gate
# and would render the diagnostics-only UI, demonstrating nothing. Battery
# levels are driven with `dumpsys battery set` so a charge cycle fits in the
# recording; the service, the restore decision and the settings write are real.
#
# Usage: ./record.sh [adb-serial]   (default 31071JEHN17531 / Pixel 7a lynx)
#        NOREC=1 ./record.sh ...     validate the tap chain without recording
SERIAL="${1:-31071JEHN17531}"
OUTDIR="${OUTDIR:-/tmp/amply-demo/foreground-service-special-use}"
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../_common.sh"

printf 'Amply\neu.darken.amply\n\nRestoring a battery charge limit\nafter a temporary full charge\n(FOREGROUND_SERVICE_SPECIAL_USE)' > "$OUTDIR/title.txt"
printf 'The protective limit returns\nautomatically at 100%%,\non unplug, or after\na safety timeout.\n\nNo data leaves the device.' > "$OUTDIR/end.txt"

# ---- pre-state (off camera) -------------------------------------------------
echo "Pre-state: wake, reset battery override, mute + clear the shade, verify the 80% limit…"
# The video is uploaded to Google: no foreign notifications, no Wi-Fi SSID in frame.
# Restore the device's own state whatever happens from here on.
restore_device() { bat_reset; dnd off; wifi enable; }
trap restore_device EXIT

screen_wake
bat_reset
"${ADB[@]}" shell settings put system screen_off_timeout 1800000 >/dev/null
dnd priority          # nothing new arrives mid-take
wifi disable          # the quick-settings chip would otherwise show the SSID
notif_clear

mode_before="$(policy_mode)"
if [ "$mode_before" != "1" ]; then
  echo "ABORT: charge_optimization_mode is '$mode_before', expected '1' (the 80% limit)." >&2
  echo "       The demo must START protected, or the restore has nothing to restore." >&2
  echo "       Set the 80% policy in the app (or: settings put secure charge_optimization_mode 1)." >&2
  exit 1
fi

app_stop; app_launch; pause 3
# Fresh install lands in onboarding; page through it off camera.
for _ in 1 2 3 4 5; do
  if have "Get started"; then tap "Get started"; pause 1.5; break; fi
  if have "Next"; then tap "Next"; pause 1.2; else break; fi
done
pause 1.5
if ! have "Charge to 100% once"; then
  swipe_up; pause 1
fi
if ! have "Charge to 100% once"; then
  echo "ABORT: the dashboard does not offer 'Charge to 100% once'." >&2
  echo "       Check the device is inside the capability gate and WSS is granted:" >&2
  echo "         adb -s $SERIAL shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS" >&2
  exit 1
fi
# Restart into a clean dashboard and let it settle BEFORE the capture starts.
# Launching inside the recording would put the previously-focused app on screen
# for the ~2.5s of the launch animation — whatever the user happened to have open.
app_stop; pause 1
app_launch; pause 4

# ---- recording --------------------------------------------------------------
rec_start
pause 2.5
cap "Plugged in — the battery is held\nat the 80% protection limit"
pause 3.5

tap "Charge to 100% once" || { echo "! could not start the session"; rec_stop; exit 1; }
cap "The user asks for one full charge"
pause 5

shade_open
cap "A foreground service supervises\nthe override while it lasts"
pause 6
shade_close
pause 2.5
mode_during="$(policy_mode)"

cap "The limit is off now — the phone\nwould charge on past 80%"
pause 4

# The unplug is the scenario the service exists for: Android does not deliver it
# to a dormant app, so without the service the limit would stay off.
bat_unplug
cap "The user unplugs the charger"
pause 4.5
cap "The service sees the unplug and puts\nthe protective limit back"
pause 9

shade_open
cap "Its work is done, so it stopped"
pause 5
shade_close
pause 2.5

# Real readings return: plugged, still 80%, hardware reporting the limit is
# holding — the app can now show it as verified rather than merely requested.
bat_reset
cap "Plugged back in: the hardware confirms\nthe 80% limit is holding again"
pause 11

rec_stop

# ---- post-state -------------------------------------------------------------
bat_reset
pause 3
mode_after="$(policy_mode)"
echo "charge_optimization_mode: before=$mode_before during=${mode_during:-?} after=$mode_after"
ok=1
[ "${mode_during:-}" = "0" ] || { echo "WARNING: the limit was never lifted (during='${mode_during:-?}') — the take shows no override." >&2; ok=0; }
[ "$mode_after" = "1" ] || { echo "WARNING: the limit was not restored (after='$mode_after')." >&2; ok=0; }
if [ "$ok" = 1 ]; then
  echo "OK: limit lifted for the session and restored by the service on unplug."
else
  echo "Do NOT ship this take." >&2
  exit 1
fi
