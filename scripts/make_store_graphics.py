#!/usr/bin/env python3
"""Store icon (512x512) and feature graphic (1024x500) from the launcher icon's shapes.

Usage: python3 scripts/make_store_graphics.py docs/store
Needs Pillow built with libraqm (for Arabic shaping) and the Noto fonts.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
GREEN = (0x1B, 0x5E, 0x20)
SS = 4  # supersampling for smooth edges

def draw_mark(d, cx, cy, unit, color=(255, 255, 255)):
    """The launcher foreground in its own 108-unit space, centred on (54,54)."""
    p = lambda x, y: (cx + (x - 54) * unit, cy + (y - 54) * unit)
    d.ellipse([*p(24, 24), *p(84, 84)], fill=color)
    d.ellipse([*p(36, 36), *p(72, 72)], fill=GREEN)
    d.rectangle([*p(44, 42), *p(64, 48)], fill=color)
    d.rectangle([*p(44, 54), *p(58, 60)], fill=color)

# Store icon: the visible 72-unit centre of the adaptive icon, full bleed.
# Play rounds the corners itself, so the square stays square.
size = 512 * SS
icon = Image.new("RGB", (size, size), GREEN)
draw_mark(ImageDraw.Draw(icon), size / 2, size / 2, size / 72)
icon.resize((512, 512), Image.LANCZOS).save(f"{OUT}/icon-512.png", optimize=True)

# Feature graphic: gradient green, the mark on the left, name and tagline right.
W, H = 1024 * SS, 500 * SS
fg = Image.new("RGB", (W, H))
top, bottom = (0x2E, 0x7D, 0x32), (0x14, 0x47, 0x18)
px = fg.load()
for y in range(H):
    t = y / (H - 1)
    row = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    for x in range(W):
        px[x, y] = row
d = ImageDraw.Draw(fg)
# The mark sits on its own darker disc so the ring reads against the gradient.
cx, cy, r = 250 * SS, 250 * SS, 165 * SS
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GREEN)
draw_mark(d, cx, cy, r / 36)

latin = "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"
latin_regular = "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"
arabic = "/usr/share/fonts/truetype/noto/NotoNaskhArabic-Bold.ttf"
x0 = 470 * SS
d.text((x0, 95 * SS), "Tahsin", font=ImageFont.truetype(latin, 104 * SS), fill="white")
d.text((x0, 228 * SS), "تحسين التلاوة", font=ImageFont.truetype(arabic, 54 * SS),
       fill=(0xC8, 0xE6, 0xC9), direction="rtl", language="ar")
small = ImageFont.truetype(latin_regular, 30 * SS)
d.text((x0, 330 * SS), "Recite the Quran, get word-by-word", font=small, fill="white")
d.text((x0, 372 * SS), "feedback. Fully offline.", font=small, fill="white")
fg.resize((1024, 500), Image.LANCZOS).save(f"{OUT}/feature-graphic-1024x500.png", optimize=True)
