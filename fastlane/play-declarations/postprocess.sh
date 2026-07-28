#!/usr/bin/env bash
# Assemble a declaration video from a recorder's output dir:
#   <OUTDIR>/raw.mp4 + captions.txt + title.txt + end.txt  ->  <OUTDIR>/declaration.mp4
#
# Usage: ./postprocess.sh [OUTDIR]
set -euo pipefail

OUTDIR="${1:-/tmp/amply-demo/foreground-service-special-use}"
RAW="$OUTDIR/raw.mp4"; CAPS="$OUTDIR/captions.txt"; OUT="$OUTDIR/declaration.mp4"
TITLE="$OUTDIR/title.txt"; END="$OUTDIR/end.txt"

FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
FONTB=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
W=720; H=1600; FPS=30; BG=0x00332B      # Amply brand dark teal

[ -f "$RAW" ] || { echo "missing $RAW — run record.sh first"; exit 1; }
[ -f "$TITLE" ] || printf 'Amply\neu.darken.amply' > "$TITLE"
[ -f "$END" ]   || printf 'Protection is restored\nautomatically.' > "$END"
TXTDIR="$(mktemp -d)"; trap 'rm -rf "$TXTDIR"' EXIT

dur=$(ffprobe -v error -select_streams v:0 -show_entries format=duration -of csv=p=0 "$RAW")

# --- timed-caption drawtext chain from captions.txt --------------------------
chain=""
if [ -f "$CAPS" ]; then
  mapfile -t lines < "$CAPS"
  n=${#lines[@]}
  for ((i=0;i<n;i++)); do
    start=${lines[i]%%|*}; text=${lines[i]#*|}
    if (( i+1 < n )); then end=${lines[i+1]%%|*}; else end=$dur; fi
    printf '%b' "$text" > "$TXTDIR/c$i.txt"   # %b expands a literal \n into a line break
    chain+=",drawtext=expansion=none:fontfile=${FONT}:textfile=${TXTDIR}/c$i.txt:fontcolor=white:fontsize=27:line_spacing=10:box=1:boxcolor=0x000000C0:boxborderw=16:x=(w-text_w)/2:y=h-190:enable='between(t,${start},${end})'"
  done
fi

ffmpeg -y -loglevel error -stats \
  -f lavfi -i "color=c=${BG}:s=${W}x${H}:d=3.5:r=${FPS}" \
  -i "$RAW" \
  -f lavfi -i "color=c=${BG}:s=${W}x${H}:d=4:r=${FPS}" \
  -filter_complex "
    [0:v]format=yuv420p,drawtext=expansion=none:fontfile=${FONTB}:textfile=${TITLE}:fontcolor=white:fontsize=32:line_spacing=18:x=(w-text_w)/2:y=(h-text_h)/2[t];
    [1:v]fps=${FPS},scale=${W}:${H},setsar=1,format=yuv420p${chain}[m];
    [2:v]format=yuv420p,drawtext=expansion=none:fontfile=${FONTB}:textfile=${END}:fontcolor=white:fontsize=36:line_spacing=18:x=(w-text_w)/2:y=(h-text_h)/2[e];
    [t][m][e]concat=n=3:v=1:a=0[v]
  " -map "[v]" -c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p -movflags +faststart "$OUT"

echo "Wrote $OUT"
ffprobe -v error -select_streams v:0 -show_entries format=duration,size:stream=width,height -of default=noprint_wrappers=1 "$OUT"
