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

# (font file, text, cap height dp, baseline y dp, tracking dp)
WORDS = [
    ("LiberationSerif-BoldItalic.ttf", "Alal", 16.5, 51.0, 0.0),
    ("LiberationSans-Bold.ttf", "ZIP", 7.5, 77.0, 3.5),
]
WHITE, AMBER, AMBER_DARK = "#FFFFFF", "#FFB300", "#FF8F00"


def zipper():
    """Interlocking zipper teeth between the words plus an amber slider: (x0, y0, x1, y1, colour)."""
    shapes = []
    x = 33.0
    while x + 2.4 <= 66.0:
        shapes.append((x, 56.6, x + 2.4, 59.6, WHITE))          # upper tooth
        shapes.append((x + 2.0, 58.8, x + 4.4, 61.8, WHITE))    # lower tooth, offset half a pitch
        x += 4.0
    shapes.append((66.4, 55.4, 74.4, 63.0, AMBER))              # slider body
    shapes.append((69.4, 63.0, 71.4, 67.4, AMBER_DARK))         # pull tab
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
    paths, corners, raster = [], [], []
    for font_file, text, cap_dp, baseline, tracking in WORDS:
        data, word_corners, font_path, em_dp, left = layout(font_file, text, cap_dp, baseline, tracking)
        paths.append(data)
        corners += word_corners
        raster.append((font_path, text, em_dp, left, baseline, tracking))
    shapes = [(path, WHITE) for path in paths]
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
    (drawable / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n'
        '    <solid android:color="#000000" />\n</shape>\n')

    # Legacy PNGs (only used below API 26; kept consistent with the vector). Written with
    # filter-0 rows so they stay trivially verifiable by tools/check_source.py.
    def png(target, size, mask=None):
        big = size * 4
        image = Image.new("RGBA", (big, big), (0, 0, 0, 255))
        draw = ImageDraw.Draw(image)
        k = big / 108
        for font_path, text, em_dp, left, baseline, tracking in raster:
            font = ImageFont.truetype(str(font_path), int(round(em_dp * k)))
            x = left * k
            for char in text:
                draw.text((x, baseline * k), char, font=font, fill=(255, 255, 255, 255), anchor="ls")
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
