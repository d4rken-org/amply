# shellcheck shell=bash
# Shared helpers for the Play declaration screencast recorders.
# Sourced by each declaration folder's record.sh. The sourcing script must set
# OUTDIR (and may set SERIAL/PKG/SIZE/NOREC) BEFORE sourcing this file.
set -uo pipefail

SERIAL="${SERIAL:-31071JEHN17531}"          # Pixel 7a (lynx)
PKG="${PKG:-eu.darken.amply}"
SIZE="${SIZE:-720x1600}"                    # 1080x2400 scaled 1:1 in aspect
NOREC="${NOREC:-0}"
ADB=(adb -s "$SERIAL")
LIBDIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UIX="$OUTDIR/ui.xml"; RAW="$OUTDIR/raw.mp4"; CAPS="$OUTDIR/captions.txt"
mkdir -p "$OUTDIR"; : > "$CAPS"

# ---- ui helpers -------------------------------------------------------------
# uiautomator intermittently fails with "could not get idle state" during
# animations (worse under screenrecord load) and then writes nothing, leaving a
# stale dump. Retry, and only overwrite $UIX with a dump that has nodes.
dump() {
  local out
  for _ in 1 2 3 4; do
    if "${ADB[@]}" shell uiautomator dump /sdcard/__ui.xml 2>&1 | grep -q "dumped to"; then
      out="$("${ADB[@]}" exec-out cat /sdcard/__ui.xml)"
      if [ -n "$out" ] && printf '%s' "$out" | grep -q "<node"; then
        printf '%s' "$out" > "$UIX"; return 0
      fi
    fi
    sleep 0.4
  done
  [ -s "$UIX" ] && return 1
  "${ADB[@]}" exec-out cat /sdcard/__ui.xml > "$UIX" 2>/dev/null || true
  return 1
}
_find() { python3 "$LIBDIR/find_node.py" "$@" 2>/dev/null; }

tap() {  # tap <needle> [idx] [-c]   (-c = substring match)
  local needle="$1" idx="${2:-0}" flag="${3:-}" xy
  for _ in $(seq 1 12); do
    dump
    if [ "$flag" = "-c" ]; then xy=$(_find -c "$UIX" "$needle" "$idx"); else xy=$(_find "$UIX" "$needle" "$idx"); fi
    if [ -n "$xy" ]; then "${ADB[@]}" shell input tap $xy; return 0; fi
    sleep 0.5
  done
  echo "  ! tap not found: '$needle'" >&2; return 1
}
have() {  # have <needle> [-c]  -> 0 if present on screen
  local needle="$1" flag="${2:-}"
  dump
  if [ "$flag" = "-c" ]; then _find -c "$UIX" "$needle" >/dev/null; else _find "$UIX" "$needle" >/dev/null; fi
}
swipe_up() { "${ADB[@]}" shell input swipe 540 1900 540 900 500; }    # scroll down a list
swipe_down() { "${ADB[@]}" shell input swipe 540 900 540 1900 500; }
back() { "${ADB[@]}" shell input keyevent BACK; }
pause() { sleep "${1:-1.6}"; }
shade_open() { "${ADB[@]}" shell cmd statusbar expand-notifications; }
shade_close() { "${ADB[@]}" shell cmd statusbar collapse; }
# Captions may contain a literal "\n" to force a line break; postprocess.sh expands it.
# Keep each line under ~34 characters or it overflows the 720px frame.
# NOTE: the caption text goes through the environment, not `awk -v`. awk expands
# escape sequences in -v assignments, which would turn the literal "\n" into a real
# newline and break the one-caption-per-line format postprocess.sh parses.
cap() {
  local now; now=$(date +%s.%N)
  CAP_TEXT="$1" awk -v a="$now" -v b="$REC_T0" 'BEGIN{printf "%.2f|%s\n", a-b, ENVIRON["CAP_TEXT"]}' >> "$CAPS"
}

# ---- privacy hygiene for the recording --------------------------------------
# The finished video is uploaded to Google. Anything in the notification shade or
# the quick-settings strip is in frame: other apps' notifications, the Wi-Fi SSID,
# the user's location and language. Clear and mute it for the take, restore after.
notif_clear() {
  shade_open; pause 1.2
  tap "Clear all" >/dev/null 2>&1 || true
  pause 1
  shade_close; pause 1
}
dnd() { "${ADB[@]}" shell cmd notification set_dnd "$1" >/dev/null 2>&1; }   # priority|off
wifi() { "${ADB[@]}" shell svc wifi "$1" >/dev/null 2>&1; }                  # disable|enable

# ---- battery simulation -----------------------------------------------------
# Only the battery *readings* are driven here — the service, the restore decision
# and the settings write are the real app on real hardware. The current recording
# uses unplug/reset only (pulling the cable of the device running the screen
# capture is not an option); the level/status helpers exist for takes that need to
# demonstrate the 100% stop condition instead.
# BATTERY_STATUS_CHARGING=2, BATTERY_STATUS_FULL=5.
bat_level() { "${ADB[@]}" shell dumpsys battery set level "$1" >/dev/null; }
bat_status() { "${ADB[@]}" shell dumpsys battery set status "$1" >/dev/null; }
bat_plug() { "${ADB[@]}" shell dumpsys battery set usb 1 >/dev/null; }
bat_unplug() { "${ADB[@]}" shell dumpsys battery unplug >/dev/null; }
bat_reset() { "${ADB[@]}" shell dumpsys battery reset >/dev/null; }

# ---- charge policy readback (Pixel) -----------------------------------------
# charge_optimization_mode: 1 = the 80% limit, 0 = unrestricted/off.
policy_mode() { "${ADB[@]}" shell settings get secure charge_optimization_mode | tr -d '\r'; }

# ---- app helpers ------------------------------------------------------------
app_launch() { "${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }
app_stop() { "${ADB[@]}" shell am force-stop "$PKG"; }
screen_wake() {
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1
}

# ---- recording control ------------------------------------------------------
rec_start() {
  if [ "$NOREC" = "0" ]; then
    echo "Recording → $RAW"
    "${ADB[@]}" shell rm -f /sdcard/__demo.mp4
    "${ADB[@]}" shell screenrecord --size "$SIZE" --bit-rate 8000000 --time-limit 180 /sdcard/__demo.mp4 &
    REC_CLIENT=$!
    sleep 1.2
  else
    echo "NOREC=1 → validating taps without recording"
  fi
  REC_T0=$(date +%s.%N)
}
rec_stop() {
  if [ "$NOREC" = "0" ]; then
    "${ADB[@]}" shell pkill -INT screenrecord 2>/dev/null || true
    wait "$REC_CLIENT" 2>/dev/null || true
    sleep 1
    "${ADB[@]}" pull /sdcard/__demo.mp4 "$RAW" >/dev/null && echo "Saved $RAW"
  fi
  echo "Captions:"; cat "$CAPS"
}
