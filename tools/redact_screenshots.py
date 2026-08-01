#!/usr/bin/env python3
"""Redact personal details from README screenshots.

Uses heavy pixelation (downscale → upscale with nearest-neighbour)
rather than a Gaussian blur: blurred text can sometimes be recovered,
pixelation at this block size cannot. Re-runnable — it always works
from `raw/` originals, so re-redacting never double-processes.

Usage:  python3 tools/redact_screenshots.py
"""
import os
import shutil
import sys

from PIL import Image

SHOTS = os.path.join(os.path.dirname(__file__), "..", "docs", "screenshots")
RAW = os.path.join(SHOTS, "raw")
BLOCK = 14  # pixel-block size; larger = coarser = less recoverable

# Regions are (x1, y1, x2, y2) on the 720x1600 device screenshots.
# The status bar and nav bar carry nothing identifying and are left alone.
NODE_NAME = (560, 92, 630, 132)  # "Blue" — this node's own name, top bar

REGIONS = {
    # Conversation titles + message previews (contacts, channels, content).
    "01-chats.png": [NODE_NAME, (120, 195, 520, 630)],
    # Contact names and public keys.
    "02-nodes.png": [NODE_NAME, (120, 420, 560, 1280)],
    # Repeater public keys (the repeater NAMES are public infrastructure).
    "03-repeaters.png": [NODE_NAME, (120, 455, 400, 1290)],
    # Map: only this node's name; the view shows public repeaters.
    "04-map.png": [NODE_NAME],
    # Connected-node name and the saved node's BLE MAC address.
    "05-settings.png": [NODE_NAME, (28, 258, 400, 300), (28, 488, 400, 552)],
    # Background list keys + the sheet's full public key and coordinates.
    "07-contact-sheet.png": [
        NODE_NAME,
        # Full-width here: the list is partly occluded by the sheet, and
        # half-redacted names read as sloppy rather than deliberate.
        (120, 420, 560, 800),
        (40, 1030, 690, 1095),   # public key (two lines)
        (40, 1140, 400, 1180),   # lat/lon
    ],
}


def pixelate(img: Image.Image, box) -> None:
    x1, y1, x2, y2 = box
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(img.width, x2), min(img.height, y2)
    if x2 <= x1 or y2 <= y1:
        return
    region = img.crop((x1, y1, x2, y2))
    small = region.resize(
        (max(1, region.width // BLOCK), max(1, region.height // BLOCK)),
        Image.BILINEAR,
    )
    img.paste(small.resize(region.size, Image.NEAREST), (x1, y1))


def main() -> int:
    os.makedirs(RAW, exist_ok=True)
    for name, boxes in REGIONS.items():
        current = os.path.join(SHOTS, name)
        original = os.path.join(RAW, name)
        # First run for a file: stash the untouched capture.
        if not os.path.exists(original):
            if not os.path.exists(current):
                print(f"skip {name} (missing)")
                continue
            shutil.copy2(current, original)
        img = Image.open(original).convert("RGB")
        for box in boxes:
            pixelate(img, box)
        img.save(current, optimize=True)
        print(f"redacted {name} ({len(boxes)} regions)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
