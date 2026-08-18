#!/usr/bin/env python3
"""Rasterize the launcher icon layers from the SVG sources in this directory.

The four ic_launcher_*.svg files are the source of truth for the app icon (the
battery-bugdroid mascot). This script renders them to the per-density webp
drawables plus the 512px fastlane icon. Run it after editing any of the SVGs.

Requires: python3 with cairosvg (pip install cairosvg) and ImageMagick
(`convert`, for the webp encode).

    tools/marketing/icon/gen_icon.py
"""
import pathlib
import subprocess

import cairosvg

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
RES = REPO / "app/src/main/res"
FASTLANE_ICON = REPO / "fastlane/metadata/android/en-US/images/icon.png"

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LAYERS = ["ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome", "ic_launcher_full"]

for name in LAYERS:
    src = HERE / f"{name}.svg"
    for dens, px in DENSITIES.items():
        tmp_png = HERE / f".{name}_{dens}.png"
        cairosvg.svg2png(url=str(src), write_to=str(tmp_png), output_width=px, output_height=px)
        dest = RES / f"drawable-{dens}" / f"{name}.webp"
        subprocess.run(["convert", str(tmp_png), "-define", "webp:lossless=true", str(dest)], check=True)
        tmp_png.unlink()
        print(f"wrote {dest.relative_to(REPO)}")

cairosvg.svg2png(url=str(HERE / "ic_launcher_full.svg"), write_to=str(FASTLANE_ICON), output_width=512, output_height=512)
print(f"wrote {FASTLANE_ICON.relative_to(REPO)}")
