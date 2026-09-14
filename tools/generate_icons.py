#!/usr/bin/env python3
"""Alal Zip launcher icon: white typographic lettering, a zipper motif and an amber slider on black.

Generates the adaptive-icon foreground vector (glyph outlines converted to
VectorDrawable paths) plus legacy mipmap PNGs. Uses the SIL-OFL licensed
Liberation fonts (Serif Bold Italic for "Alal", Sans Bold for "ZIP"), so the
outlines may be shipped inside the app.

Requires: python3 -m pip install fonttools pillow
"""
from pathlib import Path
import struct
import sys
import zlib

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
FONT_DIRS = [Path("/usr/share/fonts"), Path.home() / ".fonts", Path("/Library/Fonts"), Path("C:/Windows/Fonts")]

# (font file, text, cap height dp, baseline y dp, tracking dp, colour)
WORDS = [
    ("LiberationSerif-BoldItalic.ttf", "Alal", 18.0, 51.0, 0.0, "#FFFFFF"),
    ("LiberationSans-Bold.ttf", "ZIP", 8.6, 79.5, 5.0, "#FFC64D"),
]
WHITE, AMBER, AMBER_DARK = "#FFFFFF", "#FFB300", "#FF8F00"
# Adaptive background: the violet brand gradient also used by the app's top bar.
BG_STOPS = (("0", "#2B1B6E"), ("0.55", "#4B3BD1"), ("1", "#7C5CF0"))


def zipper():
    """Zipper tape, interlocking teeth and an amber slider between the words: (x0, y0, x1, y1, colour)."""
    shapes = []
    left, right = 29.0, 64.4
    shapes.append((left, 54.4, right, 55.4, WHITE))             # upper tape
    shapes.append((left, 62.8, right, 63.8, WHITE))             # lower tape
    x = left + 0.6
    while x + 1.6 <= right:
        shapes.append((x, 55.4, x + 1.6, 58.5, WHITE))          # upper tooth
        shapes.append((x + 1.8, 59.7, x + 3.4, 62.8, WHITE))    # lower tooth, offset half a pitch
        x += 3.6
    shapes.append((64.4, 57.2, 66.6, 61.0, AMBER))              # slider neck
    shapes.append((66.6, 54.0, 74.2, 64.2, AMBER))              # slider body
    shapes.append((68.8, 64.2, 71.4, 69.8, AMBER_DARK))         # pull tab
    return shapes


RECTS = zipper()
SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0  # Android adaptive icon safe circle


def find_font(name):
    for directory in FONT_DIRS:
        for path in directory.rglob(name):
            return path
    sys.exit("font not found: " + name)


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def layout(font_file, text, cap_dp, baseline, tracking):
    path = find_font(font_file)
    font = TTFont(path)
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    cap = font["OS/2"].sCapHeight or int(0.7 * font["head"].unitsPerEm)
    scale = cap_dp / cap
    names = [cmap[ord(c)] for c in text]
    width = sum(hmtx[n][0] for n in names) * scale + tracking * (len(text) - 1)
    x = (108 - width) / 2
    commands, corners = [], []
    for name in names:
        transform = (scale, 0, 0, -scale, x, baseline)
        pen = SVGPathPen(glyphs, ntos=number)
        glyphs[name].draw(TransformPen(pen, transform))
        bounds = BoundsPen(glyphs)
        glyphs[name].draw(TransformPen(bounds, transform))
        if bounds.bounds:
            a, b, c, d = bounds.bounds
            corners += [(a, b), (c, b), (a, d), (c, d)]
        commands.append(pen.getCommands())
        x += hmtx[name][0] * scale + tracking
    em_dp = font["head"].unitsPerEm * scale
    return " ".join(commands), corners, path, em_dp, (108 - width) / 2


def build():
    shapes, corners, raster = [], [], []
    for font_file, text, cap_dp, baseline, tracking, colour in WORDS:
        data, word_corners, font_path, em_dp, left = layout(font_file, text, cap_dp, baseline, tracking)
        shapes.append((data, colour))
        corners += word_corners
        raster.append((font_path, text, em_dp, left, baseline, tracking, colour))
    for a, b, c, d, colour in RECTS:
        shapes.append((f"M{number(a)},{number(b)}H{number(c)}V{number(d)}H{number(a)}Z", colour))
        corners += [(a, b), (c, b), (a, d), (c, d)]
    # Every outline corner must stay inside the centered 66 dp safe circle.
    for x, y in corners:
        assert (x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2, (x, y)
    return shapes, corners, raster


CORNERS = build()[1]


def main():
    shapes, corners, raster = build()
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    body = "".join(f'    <path android:fillColor="{colour}" android:pathData="{p}" />\n' for p, colour in shapes)
    (drawable / "ic_launcher_foreground.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp"'
        ' android:viewportWidth="108" android:viewportHeight="108">\n' + body + "</vector>\n")
    items = "".join(f'            <item android:offset="{offset}" android:color="{colour}" />\n' for offset, colour in BG_STOPS)
    (drawable / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
        ' android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">\n'
        + items +
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n</vector>\n')

    # Legacy PNGs (only used below API 26; kept consistent with the vector). Written with
    # filter-0 rows so they stay trivially verifiable by tools/check_source.py.
    def png(target, size, mask=None):
        big = size * 4
        image = Image.new("RGBA", (big, big), (0, 0, 0, 255))
        draw = ImageDraw.Draw(image)
        k = big / 108
        stops = [(float(offset), tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))) for offset, colour in BG_STOPS]
        for diagonal in range(2 * big - 1):
            t = diagonal / (2 * big - 2)
            lower = max(i for i in range(len(stops)) if stops[i][0] <= t or i == 0)
            upper = min(len(stops) - 1, lower + 1)
            span = (stops[upper][0] - stops[lower][0]) or 1.0
            ratio = min(max((t - stops[lower][0]) / span, 0.0), 1.0)
            colour = tuple(int(round(stops[lower][1][c] + (stops[upper][1][c] - stops[lower][1][c]) * ratio)) for c in range(3))
            draw.line((0, diagonal, diagonal, 0), fill=colour + (255,))
        for font_path, text, em_dp, left, baseline, tracking, colour in raster:
            rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
            font = ImageFont.truetype(str(font_path), int(round(em_dp * k)))
            x = left * k
            for char in text:
                draw.text((x, baseline * k), char, font=font, fill=rgb + (255,), anchor="ls")
                x += font.getlength(char) + tracking * k
        for a, b, c, d, colour in RECTS:
            rgb = tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))
            draw.rectangle((a * k, b * k, c * k, d * k), fill=rgb + (255,))
        if mask:
            alpha = Image.new("L", (big, big), 0)
            if mask == "circle":
                ImageDraw.Draw(alpha).ellipse((0, 0, big - 1, big - 1), fill=255)
            else:
                ImageDraw.Draw(alpha).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.22, fill=255)
            image.putalpha(alpha)
        image = image.resize((size, size), Image.LANCZOS)
        rows = bytearray()
        data = image.tobytes()
        for y in range(size):
            rows.append(0)
            rows.extend(data[y * size * 4:(y + 1) * size * 4])
        def chunk(kind, body):
            return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
        out = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
        out += chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(out)

    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name in ("ic_launcher", "ic_launcher_round"):
            png(RES / f"mipmap-{density}" / f"{name}.png", size)
    for suffix, mask in (("", None), ("-circle", "circle"), ("-rounded", "rounded")):
        png(ROOT / "docs" / f"icon-preview{suffix}.png", 432, mask)
    print("Generated adaptive vectors, 10 legacy PNGs and 3 mask previews; safe circle PASS")


if __name__ == "__main__":
    main()
