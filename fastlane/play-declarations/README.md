# Play Console — declarations

Copy-paste source for the Play Console forms that gate review, plus the tooling that regenerates
the demo video they require. Package name: `eu.darken.amply`.

| File | Console location |
|------|------------------|
| [`foreground-service-special-use/DECLARATION.md`](./foreground-service-special-use/DECLARATION.md) | App content → **Foreground service permissions** |
| [`APP_ACCESS.md`](./APP_ACCESS.md) | App content → **App access** |
| [`DATA_SAFETY.md`](./DATA_SAFETY.md) | App content → **Data safety** |

## Order of operations

1. Upload a build containing the permission to a track (internal testing is enough).
2. Record and host the demo video (below), then fill in the foreground-service declaration.
3. Fill in app access and data safety.
4. Submit. Publication stays gated on the `specialUse` use case being approved — see the `release`
   skill.

## Demo video

Play requires, per declared foreground service type, a description, a statement of user impact if
the task is deferred or interrupted, and **a link to a video demonstrating the feature**. No length
or hosting spec is published for it; keep it under two minutes at 720p or better, and host it as an
unlisted YouTube video or a link-shared Google Drive file.

The video is regenerated on a device with no manual screen recording: the recorder drives Amply
with label-based taps (robust to layout changes) while `screenrecord` captures, then post-processing
adds a title card, burned-in captions, and an end card. **The scripts are committed; the `.mp4` is
not** — `.gitignore` excludes `fastlane/play-declarations/**/*.mp4`, so the render can live next to
its `DECLARATION.md` (ready to upload) without ever entering the repository. Intermediate artifacts
(`raw.mp4`, `captions.txt`, the card text) stay in `/tmp/amply-demo/`.

| File | Role |
|------|------|
| `_common.sh` | UI helpers (label-based tap/scroll), battery simulation, shade control, privacy hygiene, `screenrecord` start/stop |
| `find_node.py` | Locates a UI node by text/content-desc in a `uiautomator` dump |
| `postprocess.sh [OUTDIR]` | ffmpeg: title card + timed captions + end card → `<OUTDIR>/declaration.mp4` |
| `<declaration>/record.sh` | The recorded flow, its pre-state checks, and its title/end-card text |

```bash
# Requires a PHYSICAL, capability-gated device (an emulator fails the gate and
# would record the diagnostics-only UI). Default serial is the Pixel 7a.
./foreground-service-special-use/record.sh 31071JEHN17531
./postprocess.sh /tmp/amply-demo/foreground-service-special-use \
                 foreground-service-special-use/declaration.mp4

# NOREC=1 ./foreground-service-special-use/record.sh   # validate the tap chain, no recording
# TRIM_START=2.5 ./postprocess.sh …                    # drop N seconds off the front
```

`TRIM_START` (or a `trim.txt` in the output dir) exists to salvage a take whose opening frames
caught something they shouldn't have; caption timings are shifted to match. It should not normally
be needed — `record.sh` launches Amply and lets it settle *before* the capture starts, because
launching inside the recording puts the previously-focused app on screen for the duration of the
launch animation.

### What the recorder guarantees

- **It refuses a bad take.** It aborts if the device does not start at the 80% limit (the restore
  would have nothing to restore), and fails at the end unless the underlying system setting was
  observed going protected → lifted → protected again. A take that did not actually demonstrate the
  feature is never silently produced.
- **It keeps the user's data out of frame.** The finished video goes to Google, and the notification
  shade and quick-settings strip would otherwise show other apps' notifications and the Wi-Fi SSID.
  The recorder clears the shade, enables Do Not Disturb, and turns Wi-Fi off for the take, then
  restores all three on exit (including on failure).
- **It restores the device.** Battery override, Do Not Disturb and Wi-Fi are reset via an exit trap.

### Keep consistent

The declared functionality must stay documented in the store listing
(`fastlane/metadata/android/en-US/full_description.txt`) and must match the manifest's
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value — Google cross-checks both. Re-record and re-submit
whenever the service's responsibilities change.
