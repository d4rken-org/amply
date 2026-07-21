#!/usr/bin/env bash
# Generate the Google Play "feature graphic" (1024x500, 24-bit PNG, no alpha).
#
# Composites: dark-navy radial background (matching the app icon) + the real
# 512px launcher icon as a rounded app-tile + the "Amply" wordmark in the
# lime->cyan brand gradient + a two-line tagline.
#
# Reproducible: reuses the shipped icon and Noto Sans (Roboto-alike). Requires
# ImageMagick. Run from the repo root or anywhere: paths resolve to the repo.
#
#   tools/marketing/gen_feature_graphic.sh
#
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
icon="$repo_root/fastlane/metadata/android/en-US/images/icon.png"
out="$repo_root/fastlane/metadata/android/en-US/images/featureGraphic.png"

font_bold="/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
font_reg="/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"
[ -f "$font_bold" ] || font_bold="DejaVu-Sans-Bold"
[ -f "$font_reg" ] || font_reg="DejaVu-Sans"

# Brand palette (sampled from icon.png)
lime="#D0FC38"     # gradient start (logo top)
cyan="#00B1D5"     # gradient end   (logo bottom)
bg_top="#0B1233"   # background gradient, lighter top
bg_bot="#04051A"   # background gradient, darker bottom
glow="rgba(46,70,150,0.90)"  # radial glow behind the tile
tagline_fill="#C7D2E6"

W=1024; H=500
tile=300                       # app-tile side
tile_x=72                      # tile left margin (vertically centered)
tile_r=66                      # corner radius (~22%)
text_x=430                     # left edge of the text column
word_pt=132                    # wordmark point size
word_center=196                # vertical center of the wordmark
tag_pt=41                      # tagline point size

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# 1) Background: vertical gradient + soft off-center radial glow behind the tile
convert -size ${W}x${H} gradient:"$bg_top"-"$bg_bot" "$tmp/base.png"
convert -size 760x760 radial-gradient:"$glow"-"rgba(46,70,150,0)" "$tmp/glow.png"
# center the glow roughly behind the tile (~x=222,y=250)
convert "$tmp/base.png" "$tmp/glow.png" -geometry +$((222 - 380))+$((250 - 380)) \
  -compose screen -composite "$tmp/bg.png"

# 2) App-tile: resize icon, round its corners, add a soft drop shadow
convert "$icon" -resize ${tile}x${tile} "$tmp/icon.png"
convert -size ${tile}x${tile} xc:black -fill white \
  -draw "roundrectangle 0,0,$((tile-1)),$((tile-1)),$tile_r,$tile_r" "$tmp/mask.png"
convert "$tmp/icon.png" "$tmp/mask.png" -alpha off -compose CopyOpacity -composite "$tmp/tile.png"
convert "$tmp/tile.png" \
  \( +clone -background black -shadow 55x18+0+12 \) +swap \
  -background none -layers merge +repage "$tmp/tile_sh.png"

# 3) Wordmark: white text mask -> diagonal lime->cyan gradient via CopyOpacity
convert -background none -fill white -font "$font_bold" -pointsize $word_pt \
  label:"Amply" "$tmp/word_mask.png"
ww=$(identify -format "%w" "$tmp/word_mask.png")
wh=$(identify -format "%h" "$tmp/word_mask.png")
convert -size ${ww}x${wh} xc: \
  -sparse-color barycentric "0,0 $lime $((ww-1)),$((wh-1)) $cyan" "$tmp/word_grad.png"
convert "$tmp/word_grad.png" "$tmp/word_mask.png" -alpha off \
  -compose CopyOpacity -composite "$tmp/word.png"
# subtle shadow so the wordmark holds up on the dark background
convert "$tmp/word.png" \
  \( +clone -background black -shadow 70x4+0+3 \) +swap \
  -background none -layers merge +repage "$tmp/word_sh.png"

# 4) Compose: bg + tile (vertically centered) + wordmark + tagline
word_y=$((word_center - wh / 2))
tag_y=$((word_center + wh / 2 + 24))
convert "$tmp/bg.png" \
  "$tmp/tile_sh.png" -gravity West -geometry +$((tile_x - 12))+0 -compose over -composite \
  "$tmp/word_sh.png" -gravity NorthWest -geometry +${text_x}+${word_y} -compose over -composite \
  -font "$font_reg" -pointsize $tag_pt -fill "$tagline_fill" -interline-spacing 10 \
  -gravity NorthWest -annotate +${text_x}+${tag_y} "One-tap battery\ncharge-protection controls" \
  "$tmp/flat.png"

# 5) Flatten to opaque 24-bit PNG (no alpha) as Play requires
convert "$tmp/flat.png" -background "$bg_bot" -flatten -alpha off \
  -depth 8 -define png:color-type=2 -define png:bit-depth=8 "$out"

echo "Wrote: $out"
identify -format "  %wx%h  %[channels]  %m  %B bytes\n" "$out"
