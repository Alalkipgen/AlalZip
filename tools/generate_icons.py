#!/usr/bin/env python3
"""Alal Zip launcher icon: a text-free "unzipping" mark on the violet brand gradient.

The foreground is a white rounded archive tile whose zipper is open at the top: the
opening shows the gradient through a violet wedge with white teeth along both tapes,
the closed teeth continue below, and an amber slider with a pull tab sits at the
junction. Everything is geometry only, so the adaptive vector and the legacy PNGs
are drawn from the same numbers and no font files are needed.

Generates app/src/main/res/drawable/ic_launcher_{foreground,background}.xml,
the legacy mipmap PNGs and three mask previews in docs/.

Requires: python3 -m pip install pillow
"""
from pathlib import Path
import math
import struct
import zlib

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

WHITE, AMBER, AMBER_DARK, VIOLET = "#FFFFFF", "#FFB300", "#FF8F00", "#4B3BD1"
# Adaptive background: the violet brand gradient also used by the app's top bar.
BG_STOPS = (("0", "#2B1B6E"), ("0.55", "#4B3BD1"), ("1", "#7C5CF0"))

SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0   # Android adaptive-icon safe circle
TILE = (31.0, 31.0, 77.0, 77.0)         # white archive tile
TILE_RADIUS = 13.0
VERTEX = (54.0, 55.0)                   # where the zipper closes: the slider sits here
OPEN_LEFT, OPEN_RIGHT = (44.5, 31.0), (63.5, 31.0)
SEAM_HALF = 2.2                         # half width of the closed zipper seam


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def rectangle(x0, y0, x1, y1):
    return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]


def edge_teeth(start, end, count, width, depth, inward):
    """Small quads marching along an open zipper tape, leaning with the tape."""
    (ax, ay), (bx, by) = start, end
    length = math.hypot(bx - ax, by - ay)
    ux, uy = (bx - ax) / length, (by - ay) / length
    nx, ny = -uy * inward, ux * inward
    teeth = []
    for index in range(count):
        centre = (index + 0.6) * length / (count + 0.2)
        px, py = ax + ux * centre, ay + uy * centre
        hw = width / 2
        teeth.append([
            (px - ux * hw, py - uy * hw),
            (px + ux * hw, py + uy * hw),
            (px + ux * hw + nx * depth, py + uy * hw + ny * depth),
            (px - ux * hw + nx * depth, py - uy * hw + ny * depth),
        ])
    return teeth


def closed_seam():
    """The closed zipper below the slider: a seam with teeth alternating sideways."""
    cx = VERTEX[0]
    top, bottom = VERTEX[1] + 3.0, TILE[3] - 5.0
    shapes = [rectangle(cx - SEAM_HALF, top, cx + SEAM_HALF, bottom)]
    y = top + 1.6
    side = -1
    while y + 2.2 <= bottom:
        if side < 0:
            shapes.append(rectangle(cx - SEAM_HALF - 3.2, y, cx - SEAM_HALF, y + 2.2))
        else:
            shapes.append(rectangle(cx + SEAM_HALF, y, cx + SEAM_HALF + 3.2, y + 2.2))
        side = -side
        y += 3.0
    return shapes


def build():
    """Returns (shapes, corners): shapes are (kind, geometry, colour) draw commands."""
    x0, y0, x1, y1 = TILE
    shapes = [("tile", (x0, y0, x1, y1, TILE_RADIUS), WHITE)]
    corners = [(x0, y0), (x1, y0), (x0, y1), (x1, y1)]

    shapes.append(("polygon", [VERTEX, OPEN_LEFT, OPEN_RIGHT], VIOLET))
    # Violet teeth bite into the white tile along both open tapes.
    for tooth in edge_teeth(OPEN_LEFT, VERTEX, 4, 2.4, 3.0, 1):
        shapes.append(("polygon", tooth, VIOLET))
    for tooth in edge_teeth(OPEN_RIGHT, VERTEX, 4, 2.4, 3.0, -1):
        shapes.append(("polygon", tooth, VIOLET))
    for tooth in closed_seam():
        shapes.append(("polygon", tooth, VIOLET))
    shapes.append(("round", (VERTEX[0] - 6.0, VERTEX[1] - 4.4, VERTEX[0] + 6.0, VERTEX[1] + 4.4, 2.8), AMBER))
    shapes.append(("round", (VERTEX[0] - 2.0, VERTEX[1] + 4.0, VERTEX[0] + 2.0, VERTEX[1] + 14.0, 1.8), AMBER_DARK))

    for kind, geometry, _ in shapes[1:]:
        points = geometry if kind == "polygon" else [
            (geometry[0], geometry[1]), (geometry[2], geometry[1]), (geometry[0], geometry[3]), (geometry[2], geometry[3])
        ]
        corners += list(points)
    # Every drawn corner must stay inside the centered 66 dp safe circle.
    for x, y in corners:
        assert (x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2, (x, y)
    return shapes, corners


CORNERS = build()[1]


def path_data(kind, geometry):
    if kind == "polygon":
        head = f"M{number(geometry[0][0])},{number(geometry[0][1])}"
        return head + "".join(f"L{number(x)},{number(y)}" for x, y in geometry[1:]) + "Z"
    x0, y0, x1, y1, r = geometry
    return (
        f"M{number(x0 + r)},{number(y0)}H{number(x1 - r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x1)},{number(y0 + r)}V{number(y1 - r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x1 - r)},{number(y1)}H{number(x0 + r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x0)},{number(y1 - r)}V{number(y0 + r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x0 + r)},{number(y0)}Z"
    )


def rgb(colour):
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))


def main():
    shapes, _ = build()
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    body = "".join(
        f'    <path android:fillColor="{colour}" android:pathData="{path_data(kind, geometry)}" />\n'
        for kind, geometry, colour in shapes
    )
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

    # Legacy PNGs (unused from API 26 on) plus docs previews, written with filter-0
    # rows so they stay trivially verifiable by tools/check_source.py.
    def png(target, size, mask=None):
        big = size * 4
        image = Image.new("RGBA", (big, big), (0, 0, 0, 255))
        draw = ImageDraw.Draw(image)
        k = big / 108
        stops = [(float(offset), rgb(colour)) for offset, colour in BG_STOPS]
        for diagonal in range(2 * big - 1):
            t = diagonal / (2 * big - 2)
            lower = max(i for i in range(len(stops)) if stops[i][0] <= t or i == 0)
            upper = min(len(stops) - 1, lower + 1)
            span = (stops[upper][0] - stops[lower][0]) or 1.0
            ratio = min(max((t - stops[lower][0]) / span, 0.0), 1.0)
            colour = tuple(int(round(stops[lower][1][c] + (stops[upper][1][c] - stops[lower][1][c]) * ratio)) for c in range(3))
            draw.line((0, diagonal, diagonal, 0), fill=colour + (255,))
        for kind, geometry, colour in shapes:
            fill = rgb(colour) + (255,)
            if kind == "polygon":
                draw.polygon([(x * k, y * k) for x, y in geometry], fill=fill)
            else:
                x0, y0, x1, y1, r = geometry
                draw.rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), radius=r * k, fill=fill)
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
        def chunk(kind, payload):
            return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
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
