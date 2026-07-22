#!/usr/bin/env bash
# Generate the GitHub README / Pages hero banner (.assets/banner.png).
#
# The repo banner is the same 1024x500 branded hero as the Play "feature
# graphic" (icon tile + "Amply" wordmark + tagline), so this mirrors the
# generated feature graphic instead of duplicating its compositing logic.
# If the icon or brand changes, regenerate the feature graphic first
# (tools/marketing/gen_feature_graphic.sh), then re-run this. Requires
# ImageMagick. Run from anywhere: paths resolve to the repo root.
#
#   tools/marketing/gen_banner.sh
#
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
src="$repo_root/fastlane/metadata/android/en-US/images/featureGraphic.png"
dst="$repo_root/.assets/banner.png"

[ -f "$src" ] || { echo "Missing $src — run tools/marketing/gen_feature_graphic.sh first" >&2; exit 1; }

mkdir -p "$(dirname "$dst")"
cp "$src" "$dst"

echo "Wrote: $dst"
identify -format "  %wx%h  %[channels]  %m  %B bytes\n" "$dst"
