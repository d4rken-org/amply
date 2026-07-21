#!/usr/bin/env bash
set -euo pipefail

# Renders the Play Store screenshots (app/src/screenshotTest) to PNGs via the Compose Preview
# Screenshot Testing plugin — on the JVM, no device or emulator. Output lands in the reference dir;
# run copy_screenshots.sh afterwards to normalize and sort them into the fastlane metadata tree.
#
# Usage: ./fastlane/generate_screenshots.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REF_DIR="$PROJECT_DIR/app/src/screenshotTestGplayDebug/reference"

# One capture per (composable × locale). 6 composables, en-US only (light + dark are encoded in the
# @PlayStoreLocales annotations) → 6 PNGs. Bump this when adding composables or locales.
EXPECTED=6

echo "Cleaning reference directory…"
rm -rf "$REF_DIR"

echo "Rendering screenshots (:app:updateGplayDebugScreenshotTest)…"
cd "$PROJECT_DIR"
# --rerun-tasks: the task caches UP-TO-DATE across runs (its inputs rarely change), which would leave
# the just-deleted reference dir empty. Force a re-render every time so this stays a reliable tool.
./gradlew :app:updateGplayDebugScreenshotTest --rerun-tasks

COUNT=$(find "$REF_DIR" -name '*.png' 2>/dev/null | wc -l | tr -d '[:space:]')
echo "Rendered $COUNT PNG(s) (expected $EXPECTED)."
if [ "$COUNT" -ne "$EXPECTED" ]; then
    echo "ERROR: expected $EXPECTED screenshots but found $COUNT. Aborting." >&2
    exit 1
fi

echo "Done. Next: ./fastlane/copy_screenshots.sh"
