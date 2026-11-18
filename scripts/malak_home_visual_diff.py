#!/usr/bin/env python3
"""Malak home: deterministic screenshot comparison against the user's exact locked PNG.

Run locally with Pillow installed:
  python scripts/malak_home_visual_diff.py --reference /path/to/image\(4\).png \
      --capture /path/to/locked-home-393dp.png --output /tmp/malak-visual-qa

This is a human-review aid, never an automatic pass/fail declaration of pixel parity.
"""
import argparse
import hashlib
from pathlib import Path

APPROVED_SHA256 = "7cb5ea83bc0831d0d16ac6d0a219bd650c3ab668cbff19bc48e318b857d7bb47"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--reference", required=True, type=Path)
    p.add_argument("--capture", required=True, type=Path)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    digest = hashlib.sha256(args.reference.read_bytes()).hexdigest()
    if digest != APPROVED_SHA256:
        raise SystemExit("REF_MISMATCH: only the original user-approved screenshot is valid")
    from PIL import Image, ImageChops, ImageEnhance, ImageOps
    ref = Image.open(args.reference).convert("RGB")
    actual = Image.open(args.capture).convert("RGB")
    if ref.size != (543,1176):
        raise SystemExit(f"REF_DIMENSIONS_CHANGED: {ref.size}")
    # A UI reference contains its own system bar. Use a letterboxed whole-screen
    # comparison instead of cropping any important content.
    dst = (543,1176)
    captured = ImageOps.pad(actual,dst,method=Image.Resampling.LANCZOS,
                            color="#eeeeee",centering=(0.5,0.5))
    diff = ImageChops.difference(ref,captured)
    heatmap = ImageEnhance.Contrast(diff).enhance(2.3)
    out = args.output
    out.mkdir(parents=True,exist_ok=True)
    ref.save(out/"canonical.png")
    captured.save(out/"android-aligned.png")
    heatmap.save(out/"difference.png")
    board = Image.new("RGB",(543*3,1176),"white")
    for x,img in enumerate((ref,captured,heatmap)):
        board.paste(img,(x*543,0))
    board.save(out/"side-by-side.png")
    (out/"README.txt").write_text(
        "Left: exact user-approved PNG | Center: Android screenshot aligned | Right: boosted pixel difference.\n"
        "Manual review required. Differences in maps/images, font sizes, geometry or system bars are not silently accepted.\n"
        "Verified source SHA-256: "+digest+"\n",
        encoding="utf-8"
    )
    print(f"Visual QA board: {out/'side-by-side.png'}")
    print("This tool does not assert that the screenshot matches.")

if __name__ == "__main__":
    main()
