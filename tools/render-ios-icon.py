#!/usr/bin/env python3
"""Render the iOS app icon from the Android launcher icon's geometry.

The Android icon is an adaptive icon: two 108x108 vector drawables
(androidApp/src/main/res/drawable/ic_launcher_{background,foreground}.xml),
of which a launcher shows roughly the central 72x72 — the outer 18 units
on each side are bleed for the mask. iOS wants one opaque 1024x1024 PNG
and rounds the corners itself, so this draws the same shapes and crops
to that same central 72x72, which is what a phone actually shows.

The coordinates below are copied from those XML files; change them
together. Usage: python3 tools/render-ios-icon.py [out.png]
"""
import sys

from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else \
    "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
SIZE = 1024
SS = 4                        # supersampling factor, for smooth edges
VISIBLE = (18, 90)            # the central 72 of the 108 viewport

BACKGROUND = "#0E2A32"
ACCENT = "#4FC3A1"
NODE = "#E8F4F0"
LINK_WIDTH = 3                # android:strokeWidth, viewport units
NODE_RADIUS = 6
TOP, LEFT, RIGHT = (54, 38), (34, 66), (74, 66)

scale = SIZE * SS / (VISIBLE[1] - VISIBLE[0])


def px(p):
    return ((p[0] - VISIBLE[0]) * scale, (p[1] - VISIBLE[0]) * scale)


def node(draw, centre, colour):
    x, y = px(centre)
    r = NODE_RADIUS * scale
    draw.ellipse((x - r, y - r, x + r, y + r), fill=colour)


img = Image.new("RGB", (SIZE * SS, SIZE * SS), BACKGROUND)
d = ImageDraw.Draw(img)
w = round(LINK_WIDTH * scale)
# The triangle's corners sit under the node discs (radius 6 > half the
# stroke), so plain line caps match Android's mitred path exactly.
for a, b in ((LEFT, TOP), (TOP, RIGHT), (RIGHT, LEFT)):
    d.line((px(a), px(b)), fill=ACCENT, width=w)
node(d, TOP, ACCENT)
node(d, LEFT, NODE)
node(d, RIGHT, NODE)

img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT, optimize=True)
print(OUT)
