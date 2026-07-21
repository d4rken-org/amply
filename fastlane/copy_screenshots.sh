#!/usr/bin/env bash
set -euo pipefail

# Sorts rendered screenshot PNGs into the fastlane metadata tree, normalizing each to a Play-compliant
# opaque 24-bit RGB PNG and validating its dimensions. Everything is staged and fully validated first;
# the metadata tree is only touched once every image passes, and any mismatch exits non-zero with the
# tree unchanged (generated store assets must never be half-updated).
#
# Usage: ./fastlane/copy_screenshots.sh   (run generate_screenshots.sh first)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REF_DIR="$PROJECT_DIR/app/src/screenshotTestGplayDebug/reference/eu/darken/amply/screenshots/PlayStoreScreenshotsKt"
META_DIR="$SCRIPT_DIR/metadata/android"

EXPECT_W=1080
EXPECT_H=1920

# Composable function name → ordered store filename. The leading number sets Play Store display order.
# A case (not a bash-4 associative array) keeps this runnable on older/macOS bash 3.2.
screen_num() {
    case "$1" in
        DashboardReady) echo 1 ;;
        FullChargeActive) echo 2 ;;
        SamsungMultiMode) echo 3 ;;
        SetupGuide) echo 4 ;;
        Settings) echo 5 ;;
        ReconnectGesture) echo 6 ;;
        *) return 1 ;;
    esac
}
EXPECTED_PER_LOCALE=6

# ImageMagick flattens the alpha channel (Play rejects transparent PNGs) and reports dimensions.
if command -v magick >/dev/null 2>&1; then
    CONVERT=(magick)
    IDENTIFY=(magick identify)
elif command -v convert >/dev/null 2>&1; then
    CONVERT=(convert)
    IDENTIFY=(identify)
else
    echo "ERROR: ImageMagick is required (magick or convert) to flatten alpha and validate." >&2
    exit 1
fi

if [ ! -d "$REF_DIR" ]; then
    echo "ERROR: reference dir not found: $REF_DIR" >&2
    echo "Run ./fastlane/generate_screenshots.sh first." >&2
    exit 1
fi

shopt -s nullglob
pngs=("$REF_DIR"/*.png)
if [ ${#pngs[@]} -eq 0 ]; then
    echo "ERROR: no PNGs in $REF_DIR." >&2
    exit 1
fi

STAGE="$(mktemp -d)"
CURRENT_NEW=""
cleanup() {
    rm -rf "$STAGE"
    [ -n "$CURRENT_NEW" ] && rm -rf "$CURRENT_NEW"
    # Never let the trap's last status leak as the script's exit code.
    return 0
}
trap cleanup EXIT

for png in "${pngs[@]}"; do
    stem="$(basename "$png" .png)"
    stem="${stem%_[0-9]*}"     # drop trailing _<index>
    stem="${stem%_[a-f0-9]*}"  # drop trailing _<hash>
    func="${stem%%_*}"         # composable name (no underscores)
    locale="${stem#*_}"        # fastlane locale dir (the @Preview name)
    if ! num="$(screen_num "$func")"; then
        echo "ERROR: unknown composable '$func' (from $(basename "$png")). Update screen_num()." >&2
        exit 1
    fi
    mkdir -p "$STAGE/$locale"
    "${CONVERT[@]}" "$png" -background white -alpha remove -alpha off -strip "$STAGE/$locale/${num}.png"
done

# Validate every staged locale before touching the metadata tree.
status=0
for locale_dir in "$STAGE"/*/; do
    locale="$(basename "$locale_dir")"
    count=$(find "$locale_dir" -name '*.png' | wc -l | tr -d '[:space:]')
    if [ "$count" -ne "$EXPECTED_PER_LOCALE" ]; then
        echo "ERROR: $locale has $count/$EXPECTED_PER_LOCALE screenshots." >&2
        status=1
    fi
    for n in $(seq 1 "$EXPECTED_PER_LOCALE"); do
        f="$locale_dir/$n.png"
        if [ ! -f "$f" ]; then
            echo "ERROR: $locale is missing $n.png." >&2
            status=1
            continue
        fi
        # The trailing newline matters: without it `read` hits EOF and returns non-zero, which
        # `set -e` would treat as a fatal error.
        read -r w h a < <("${IDENTIFY[@]}" -format '%w %h %A\n' "$f")
        if [ "$w" != "$EXPECT_W" ] || [ "$h" != "$EXPECT_H" ]; then
            echo "ERROR: $locale/$n.png is ${w}x${h}, expected ${EXPECT_W}x${EXPECT_H}." >&2
            status=1
        fi
        if [ "$a" = "True" ]; then
            echo "ERROR: $locale/$n.png still carries an alpha channel." >&2
            status=1
        fi
    done
done
if [ "$status" -ne 0 ]; then
    echo "Validation failed; metadata tree left unchanged." >&2
    exit 1
fi

# Replace each locale's screenshots atomically: build the new set beside the target, then swap it in
# with a rename (atomic on one filesystem) so an interrupt can never leave a half-written directory.
# The old set is kept as a .bak until the rename succeeds, and restored if it fails.
locales=0
for locale_dir in "$STAGE"/*/; do
    locale="$(basename "$locale_dir")"
    target="$META_DIR/$locale/images/phoneScreenshots"
    new="${target}.new.$$"
    bak="${target}.bak.$$"
    mkdir -p "$(dirname "$target")"
    rm -rf "$new" "$bak"
    mkdir -p "$new"
    CURRENT_NEW="$new"
    cp "$locale_dir"/*.png "$new/"
    if [ -d "$target" ]; then
        mv "$target" "$bak"
    fi
    if mv "$new" "$target"; then
        CURRENT_NEW=""
        rm -rf "$bak"
    else
        [ -d "$bak" ] && mv "$bak" "$target"
        echo "ERROR: failed to install screenshots for $locale." >&2
        exit 1
    fi
    locales=$((locales + 1))
done

echo "Copied $EXPECTED_PER_LOCALE screenshots for $locales locale(s)."
