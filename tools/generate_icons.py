#!/usr/bin/env python3
"""Archive launcher icon "Glass Zip": a frosted glass document closed by a cyan zipper.

Everything is geometry, so the three adaptive vectors (background, foreground and
the Android 13 monochrome layer) come from the same numbers:

  * background - the indigo -> violet brand gradient used by the app's top bar;
  * foreground - a translucent glass sheet with a folded top-right corner, a cyan
    zipper track with teeth, and a deep-cyan slider with a punched pull tab;
  * monochrome - the same silhouette flattened to one colour for themed icons.

The legacy ``mipmap-*/ic_launcher*.png`` bitmaps are intentionally left alone:
minSdk is 26, so every supported device renders the adaptive vector above and the
PNGs only exist to satisfy the manifest's icon attributes.

Writes app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml.
Pure standard library - no third-party dependency.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"

# ---------------------------------------------------------------- brand colours
GLASS = "#59FFFFFF"        # frosted sheet
GLASS_RIM = "#8CFFFFFF"    # lit edge of the sheet
GLASS_FOLD = "#A6FFFFFF"   # folded corner catches the most light
CYAN = "#FF22D3EE"         # zipper track
CYAN_LIGHT = "#FF67E8F9"   # teeth
CYAN_DEEP = "#FF0E7490"    # slider and pull tab
MONO = "#FFFFFFFF"         # themed-icon layer: the system re-tints it

BG_STOPS = (("0", "#FF4338CA"), ("0.55", "#FF5B2FD4"), ("1", "#FF7C3AED"))

SAFE_CENTER, SAFE_RADIUS = 54.0, 33.0   # Android adaptive-icon safe circle

# ---------------------------------------------------------------- geometry
DOC = (33.0, 27.0, 75.0, 83.0)          # glass sheet bounds
DOC_R, DOC_CUT = 9.5, 15.0              # corner radius, folded corner size
RIM = 1.7                               # thickness of the lit edge

SEAM = (52.4, 29.5, 55.6, 69.0, 1.6)           # the closed seam the teeth bite into
TOOTH_W, TOOTH_H, TOOTH_R = 9.2, 2.9, 1.4
TOOTH_TOP, TOOTH_STEP, TOOTH_COUNT = 30.5, 4.4, 7

SHOULDER = (49.5, 61.5, 58.5, 68.0, 2.4)       # the slider grips the seam here
SLIDER = (46.0, 65.0, 62.0, 79.0, 6.4)         # one compact slider closes the sheet
SLIDER_SLOT = (51.4, 69.0, 56.6, 75.6, 2.2)
SLIDER_LIGHT = (48.8, 66.5, 59.2, 68.2, 0.85)


def number(value):
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def rrect(x0, y0, x1, y1, r):
    """Path data for a rounded rectangle."""
    n = number
    return (
        f"M{n(x0 + r)},{n(y0)}H{n(x1 - r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x1)},{n(y0 + r)}V{n(y1 - r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x1 - r)},{n(y1)}H{n(x0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0)},{n(y1 - r)}V{n(y0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0 + r)},{n(y0)}Z"
    )


def sheet(inset=0.0):
    """Rounded sheet whose top-right corner is folded away instead of rounded."""
    x0, y0, x1, y1 = DOC[0] + inset, DOC[1] + inset, DOC[2] - inset, DOC[3] - inset
    r = max(DOC_R - inset, 1.0)
    cut = DOC_CUT - inset * 0.8
    n = number
    return (
        f"M{n(x0 + r)},{n(y0)}H{n(x1 - cut)}L{n(x1)},{n(y0 + cut)}V{n(y1 - r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x1 - r)},{n(y1)}H{n(x0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0)},{n(y1 - r)}V{n(y0 + r)}"
        f"A{n(r)},{n(r)} 0 0 1 {n(x0 + r)},{n(y0)}Z"
    )


def fold():
    x1, y0 = DOC[2], DOC[1]
    n = number
    return f"M{n(x1 - DOC_CUT)},{n(y0)}L{n(x1)},{n(y0 + DOC_CUT)}H{n(x1 - DOC_CUT)}Z"


def teeth():
    """Interlocking teeth: each bar bites across the seam from alternating sides."""
    bars = []
    for index in range(TOOTH_COUNT):
        y = TOOTH_TOP + index * TOOTH_STEP
        x0 = 54.6 - TOOTH_W if index % 2 == 0 else 53.4
        bars.append(rrect(x0, y, x0 + TOOTH_W, y + TOOTH_H, TOOTH_R))
    return "".join(bars)


def build():
    """(paths, corners): paths are (fillColor, evenOdd, data); corners must stay in the safe circle."""
    paths = [
        (GLASS, False, sheet()),
        (GLASS_RIM, True, sheet() + sheet(RIM)),
        (GLASS_FOLD, False, fold()),
        (CYAN_DEEP, False, rrect(*SEAM)),
        (CYAN, False, teeth()),
        (CYAN, False, rrect(*SHOULDER)),
        (CYAN, True, rrect(*SLIDER) + rrect(*SLIDER_SLOT)),
        (CYAN_LIGHT, False, rrect(*SLIDER_LIGHT)),
    ]
    corners = []
    for x0, y0, x1, y1, r in (SLIDER, SHOULDER, SEAM, DOC + (DOC_R,)):
        # A rounded rectangle never reaches its raw corner: measure the arc centres.
        corners += [(x0 + r, y0 + r), (x1 - r, y0 + r), (x0 + r, y1 - r), (x1 - r, y1 - r)]
    corners += [(DOC[2], DOC[1] + DOC_CUT), (DOC[2] - DOC_CUT, DOC[1]),
                (54.6 - TOOTH_W, TOOTH_TOP), (53.4 + TOOTH_W, TOOTH_TOP + (TOOTH_COUNT - 1) * TOOTH_STEP + TOOTH_H)]
    return paths, corners


def _reach(corners):
    """Largest distance from the safe-circle centre, including each rounded corner radius."""
    return max(((x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2) ** 0.5 for x, y in corners)


PATHS, CORNERS = build()
assert all((x - SAFE_CENTER) ** 2 + (y - SAFE_CENTER) ** 2 < SAFE_RADIUS ** 2 for x, y in CORNERS)

VECTOR_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<vector xmlns:android="http://schemas.android.com/apk/res/android"%s'
               ' android:width="108dp" android:height="108dp"'
               ' android:viewportWidth="108" android:viewportHeight="108">\n')


def vector(paths, extra_ns=""):
    body = "".join(
        f'    <path android:fillColor="{colour}"'
        + (' android:fillType="evenOdd"' if even_odd else "")
        + f' android:pathData="{data}" />\n'
        for colour, even_odd, data in paths
    )
    return (VECTOR_HEAD % extra_ns) + body + "</vector>\n"


def main():
    drawable = RES / "drawable"
    drawable.mkdir(parents=True, exist_ok=True)

    (drawable / "ic_launcher_foreground.xml").write_text(vector(PATHS))

    items = "".join(f'            <item android:offset="{offset}" android:color="{colour}" />\n'
                    for offset, colour in BG_STOPS)
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

    # Themed icons get one flat silhouette: sheet edge, zipper, slider.
    (drawable / "ic_launcher_monochrome.xml").write_text(vector([
        (MONO, True, sheet() + sheet(RIM + 0.6)),
        (MONO, False, rrect(*SEAM) + teeth()),
        (MONO, False, rrect(*SHOULDER)),
        (MONO, True, rrect(*SLIDER) + rrect(*SLIDER_SLOT)),
    ]))
    print(f"Wrote 3 adaptive vectors; safe circle PASS (reach {_reach(CORNERS):.1f} of {SAFE_RADIUS:.0f})")


if __name__ == "__main__":
    main()
