#!/usr/bin/env python3
"""Alal Zip launcher icon: a text-free zipper mark on the violet brand gradient.

The foreground is a single vertical zipper: white teeth run edge to edge, split
open above the slider, and an amber slider with a pull tab sits in the middle.
The slider and tab keep the punched-out holes of a real zipper pull, drawn with
even-odd subpaths so the gradient shows through. Everything is geometry only, so
the adaptive vector and the legacy PNGs come from the same numbers.

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

WHITE, AMBER, AMBER_DARK = "#FFFFFF", "#FFB300", "#FF8F00"
# Adaptive background: the violet brand gradient also used by the app's top bar.
BG_STOPS = (("0", "#2B1B6E"), ("0.55", "#4B3BD1"), ("1", "#7C5CF0"))

SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0   # Android adaptive-icon safe circle
CX = 54.0                               # the zipper runs down the centre
TAPE_LEFT = ((46.0, 38.0), (16.0, -4.0))    # open tape, slider -> top-left edge
TAPE_RIGHT = ((62.0, 38.0), (92.0, -4.0))   # open tape, slider -> top-right edge


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def tape_teeth(start, end, count, thickness, length):
    """Bars sitting across an open zipper tape, leaning with the tape."""
    (ax, ay), (bx, by) = start, end
    span = math.hypot(bx - ax, by - ay)
    ux, uy = (bx - ax) / span, (by - ay) / span
    nx, ny = -uy, ux
    teeth = []
    for index in range(count):
        centre = (index + 0.5) * span / count
        px, py = ax + ux * centre, ay + uy * centre
        hu, hn = thickness / 2, length / 2
        teeth.append([
            (px - ux * hu - nx * hn, py - uy * hu - ny * hn),
            (px + ux * hu - nx * hn, py + uy * hu - ny * hn),
            (px + ux * hu + nx * hn, py + uy * hu + ny * hn),
            (px - ux * hu + nx * hn, py - uy * hu + ny * hn),
        ])
    return teeth


def closed_teeth():
    """Interlocking teeth below the slider, alternating across the seam."""
    teeth, y, left = [], 84.0, True
    while y < 112.0:
        x0 = CX - 10.0 if left else CX - 0.6
        teeth.append(("round", (x0, y, x0 + 10.6, y + 3.4, 1.4)))
        left = not left
        y += 4.7
    return teeth


def build():
    """Returns (shapes, core): shapes are draw commands, core must stay in the safe circle."""
    shapes = []
    for tooth in tape_teeth(*TAPE_LEFT, 6, 3.4, 12.0):
        shapes.append(("polygon", tooth, WHITE))
    for tooth in tape_teeth(*TAPE_RIGHT, 6, 3.4, 12.0):
        shapes.append(("polygon", tooth, WHITE))
    for kind, geometry in closed_teeth():
        shapes.append((kind, geometry, WHITE))

    # Slider: wide shoulders, body with a punched slot, and a ring-shaped pull tab.
    shoulders = ("round", (38.0, 36.0, 70.0, 56.0, 9.5))
    body = [("round", (43.0, 44.0, 65.0, 84.0, 7.0)), ("round", (48.5, 66.0, 59.5, 77.0, 3.4))]
    tab = [("round", (46.5, 22.0, 61.5, 50.0, 7.2)), ("round", (50.3, 26.0, 57.7, 43.0, 3.6))]
    # Order matters: the tab is painted last so its punched hole is never covered.
    shapes.append((shoulders[0], shoulders[1], AMBER))
    shapes.append(("compound", body, AMBER))
    shapes.append(("compound", tab, AMBER_DARK))

    core = []
    for kind, geometry in [shoulders, body[0], tab[0]]:
        x0, y0, x1, y1, _ = geometry
        core += [(x0, y0), (x1, y0), (x0, y1), (x1, y1)]
    # The slider is the mark's anchor, so it must stay inside the 66 dp safe circle.
    for x, y in core:
        assert (x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2, (x, y)
    return shapes, core


CORNERS = build()[1]


def round_data(geometry):
    x0, y0, x1, y1, r = geometry
    return (
        f"M{number(x0 + r)},{number(y0)}H{number(x1 - r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x1)},{number(y0 + r)}V{number(y1 - r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x1 - r)},{number(y1)}H{number(x0 + r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x0)},{number(y1 - r)}V{number(y0 + r)}"
        f"A{number(r)},{number(r)} 0 0 1 {number(x0 + r)},{number(y0)}Z"
    )


def polygon_data(points):
    head = f"M{number(points[0][0])},{number(points[0][1])}"
    return head + "".join(f"L{number(x)},{number(y)}" for x, y in points[1:]) + "Z"


def path_data(kind, geometry):
    if kind == "polygon":
        return polygon_data(geometry)
    if kind == "compound":
        return "".join(polygon_data(g) if k == "polygon" else round_data(g) for k, g in geometry)
    return round_data(geometry)


def rgb(colour):
    return tuple(int(colour[i:i + 2], 16) for i in (1, 3, 5))


def main():
    shapes, _ = build()
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)
    body = "".join(
        f'    <path android:fillColor="{colour}"'
        + (' android:fillType="evenOdd"' if kind == "compound" else "")
        + f' android:pathData="{path_data(kind, geometry)}" />\n'
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

        def paint(target_draw, kind, geometry, fill):
            if kind == "polygon":
                target_draw.polygon([(x * k, y * k) for x, y in geometry], fill=fill)
            else:
                x0, y0, x1, y1, r = geometry
                target_draw.rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), radius=r * k, fill=fill)

        for kind, geometry, colour in shapes:
            fill = rgb(colour) + (255,)
            if kind != "compound":
                paint(draw, kind, geometry, fill)
                continue
            # even-odd: paint the outline, then punch the holes back to the background
            layer = Image.new("L", (big, big), 0)
            layer_draw = ImageDraw.Draw(layer)
            for index, (sub_kind, sub_geometry) in enumerate(geometry):
                paint(layer_draw, sub_kind, sub_geometry, 255 if index == 0 else 0)
            image.paste(Image.new("RGBA", (big, big), fill), (0, 0), layer)
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
