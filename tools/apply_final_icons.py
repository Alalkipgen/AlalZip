#!/usr/bin/env python3
"""One-time patch: apply the final Alal Zip app/file icons and fix their source check."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app/src/main/res/drawable"

background = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="5" android:startY="3" android:endX="104" android:endY="108">
                <item android:offset="0" android:color="#FF18A6DF" />
                <item android:offset="0.28" android:color="#FF1766C4" />
                <item android:offset="0.62" android:color="#FF17358E" />
                <item android:offset="1" android:color="#FF101447" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

foreground = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- The artwork stays within the adaptive-icon safe zone. -->
    <group android:pivotX="54" android:pivotY="54" android:scaleX="0.90" android:scaleY="0.90">
        <!-- archive lid -->
        <path android:pathData="M31,31h46a6,6 0 0 1 6,6v5a6,6 0 0 1 -6,6h-46a6,6 0 0 1 -6,-6v-5a6,6 0 0 1 6,-6z">
            <aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="54" android:startY="31" android:endX="54" android:endY="48"><item android:offset="0" android:color="#FFFFFFFF"/><item android:offset="1" android:color="#FFE5F1FB"/></gradient></aapt:attr>
        </path>
        <path android:fillColor="#FFC5D9EC" android:pathData="M33,34h42a1.4,1.4 0 0 1 0,2.8h-42a1.4,1.4 0 0 1 0,-2.8z"/>
        <!-- archive body -->
        <path android:pathData="M29,45h50a6,6 0 0 1 6,6v25a8,8 0 0 1 -8,8h-46a8,8 0 0 1 -8,-8v-25a6,6 0 0 1 6,-6z">
            <aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="54" android:startY="45" android:endX="54" android:endY="84"><item android:offset="0" android:color="#FFFFFFFF"/><item android:offset="1" android:color="#FFDCEBFA"/></gradient></aapt:attr>
        </path>
        <path android:fillColor="#FFC6DAED" android:pathData="M28,48h52a0.7,0.7 0 0 1 0,1.4h-52a0.7,0.7 0 0 1 0,-1.4z"/>
        <!-- zipper channel and teeth -->
        <path android:fillColor="#FF173783" android:pathData="M50,45h8v27h-8z"/>
        <path android:fillColor="#FFFFFFFF" android:pathData="M47,47h6v2.5h-6zM55,50.5h6v2.5h-6zM47,54h6v2.5h-6zM55,57.5h6v2.5h-6zM47,61h6v2.5h-6zM55,64.5h6v2.5h-6z"/>
        <!-- blue zipper pull -->
        <path android:pathData="M48,67h12a3.5,3.5 0 0 1 3.5,3.5v3a5,5 0 0 1 -3,4.5l-1.5,0.8v6.7a5,5 0 0 1 -10,0v-6.7l-1.5,-0.8a5,5 0 0 1 -3,-4.5v-3a3.5,3.5 0 0 1 3.5,-3.5z">
            <aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="49" android:startY="67" android:endX="60" android:endY="91"><item android:offset="0" android:color="#FF59D7FF"/><item android:offset="1" android:color="#FF1B82DF"/></gradient></aapt:attr>
        </path>
        <path android:fillColor="#FFEAF8FF" android:pathData="M51,70h6a1.5,1.5 0 0 1 0,3h-6a1.5,1.5 0 0 1 0,-3zM54,77a1.4,1.4 0 0 1 1.4,1.4v3h2l-3.4,3.4l-3.4,-3.4h2v-3A1.4,1.4 0 0 1 54,77z"/>
        <!-- subtle archive handle -->
        <path android:fillColor="#FFB7CCE1" android:pathData="M67,73h11a2,2 0 0 1 0,4h-11a2,2 0 0 1 0,-4z"/>
    </group>
</vector>
'''

# Compact branded tile for any direct drawable usage.
archive_tile = background.replace(
    'android:width="108dp" android:height="108dp"',
    'android:width="48dp" android:height="48dp"',
).replace('</vector>\n', foreground.split('<group', 1)[1].rsplit('</vector>', 1)[0].join(['    <group', '</vector>\n']))

(DRAWABLE / "ic_launcher_background.xml").write_text(background)
(DRAWABLE / "ic_launcher_foreground.xml").write_text(foreground)
# Build this explicitly instead of relying on the string splice above.
(DRAWABLE / "ic_archivepocket.xml").write_text('''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="48dp" android:height="48dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M20,4h68a16,16 0 0 1 16,16v68a16,16 0 0 1 -16,16h-68a16,16 0 0 1 -16,-16v-68a16,16 0 0 1 16,-16z">
        <aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="5" android:startY="3" android:endX="104" android:endY="108"><item android:offset="0" android:color="#FF18A6DF"/><item android:offset="0.28" android:color="#FF1766C4"/><item android:offset="0.62" android:color="#FF17358E"/><item android:offset="1" android:color="#FF101447"/></gradient></aapt:attr>
    </path>
    <path android:fillColor="#FFFFFFFF" android:pathData="M31,31h46a6,6 0 0 1 6,6v5a6,6 0 0 1 -6,6h-46a6,6 0 0 1 -6,-6v-5a6,6 0 0 1 6,-6zM29,45h50a6,6 0 0 1 6,6v25a8,8 0 0 1 -8,8h-46a8,8 0 0 1 -8,-8v-25a6,6 0 0 1 6,-6z"/>
    <path android:fillColor="#FF173783" android:pathData="M50,45h8v27h-8z"/>
    <path android:fillColor="#FFFFFFFF" android:pathData="M47,47h6v2.5h-6zM55,50.5h6v2.5h-6zM47,54h6v2.5h-6zM55,57.5h6v2.5h-6zM47,61h6v2.5h-6zM55,64.5h6v2.5h-6z"/>
    <path android:fillColor="#FF2A9FE9" android:pathData="M48,67h12a3.5,3.5 0 0 1 3.5,3.5v3a5,5 0 0 1 -3,4.5l-1.5,0.8v6.7a5,5 0 0 1 -10,0v-6.7l-1.5,-0.8a5,5 0 0 1 -3,-4.5v-3a3.5,3.5 0 0 1 3.5,-3.5z"/>
    <path android:fillColor="#FFFFFFFF" android:pathData="M54,77a1.4,1.4 0 0 1 1.4,1.4v3h2l-3.4,3.4l-3.4,-3.4h2v-3A1.4,1.4 0 0 1 54,77z"/>
</vector>
''')

main = ROOT / "app/src/main/java/app/archivepocket/MainActivity.kt"
src = main.read_text()
src = src.replace(
    'private val NavyBright = Color(0xFF2A55C8)   // launcher icon: top-left highlight\nprivate val NavyDeep = Color(0xFF0B1E62)     // launcher icon: body navy\nprivate val NavyInk = Color(0xFF050A2C)      // launcher icon: bottom-right shadow\nprivate val PaperBlue = Color(0xFFD8E6FF)    // launcher icon: folder paper shading\n',
    'private val NavyBright = Color(0xFF24B2E8)   // final archive-file highlight\nprivate val NavyMid = Color(0xFF1767C6)      // final archive-file mid blue\nprivate val NavyDeep = Color(0xFF17378E)     // final archive-file zipper blue\nprivate val NavyInk = Color(0xFF11194F)      // final archive-file shadow\nprivate val ZipAccent = Color(0xFF69DEFF)    // final zipper-pull highlight\n',
    1,
)
start = src.index('/** Archive glyph that mirrors the launcher icon:')
end = src.index('/** A rounded colour tile carrying the file-type label', start)
new_glyph = '''/** Final archive-file glyph: folded blue document, white zipper, cyan extraction pull. */
@Composable
private fun ArchiveGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun x(v: Float) = w * v
        fun y(v: Float) = h * v
        val file = Path().apply {
            moveTo(x(0.14f), y(0.04f)); lineTo(x(0.66f), y(0.04f)); lineTo(x(0.92f), y(0.30f))
            lineTo(x(0.92f), y(0.88f)); quadraticTo(x(0.92f), y(0.96f), x(0.84f), y(0.96f))
            lineTo(x(0.14f), y(0.96f)); quadraticTo(x(0.06f), y(0.96f), x(0.06f), y(0.88f))
            lineTo(x(0.06f), y(0.14f)); quadraticTo(x(0.06f), y(0.04f), x(0.14f), y(0.04f)); close()
        }
        drawPath(file, Brush.linearGradient(listOf(NavyBright, NavyMid, NavyDeep, NavyInk)))
        val fold = Path().apply {
            moveTo(x(0.66f), y(0.04f)); lineTo(x(0.66f), y(0.22f)); quadraticTo(x(0.66f), y(0.30f), x(0.74f), y(0.30f))
            lineTo(x(0.92f), y(0.30f)); close()
        }
        drawPath(fold, Brush.linearGradient(listOf(Color(0xFFA7E9FF), Color(0xFF4CB7ED))))
        drawRoundRect(NavyInk, Offset(x(0.445f), y(0.04f)), Size(x(0.11f), y(0.61f)), CornerRadius(w * 0.02f))
        var toothY = 0.10f
        var left = true
        repeat(8) {
            val toothX = if (left) 0.395f else 0.50f
            drawRoundRect(Color.White, Offset(x(toothX), y(toothY)), Size(x(0.105f), y(0.037f)), CornerRadius(w * 0.012f))
            toothY += 0.067f; left = !left
        }
        drawRoundRect(
            Brush.linearGradient(listOf(ZipAccent, Color(0xFF2A91E4))),
            Offset(x(0.365f), y(0.58f)), Size(x(0.27f), y(0.22f)), CornerRadius(w * 0.07f)
        )
        drawRoundRect(Color(0xFFEFFBFF), Offset(x(0.445f), y(0.625f)), Size(x(0.11f), y(0.05f)), CornerRadius(w * 0.02f))
        val arrow = Path().apply {
            moveTo(x(0.47f), y(0.72f)); lineTo(x(0.53f), y(0.72f)); lineTo(x(0.53f), y(0.79f))
            lineTo(x(0.60f), y(0.79f)); lineTo(x(0.50f), y(0.89f)); lineTo(x(0.40f), y(0.79f))
            lineTo(x(0.47f), y(0.79f)); close()
        }
        drawPath(arrow, Color(0xFFEFFBFF))
    }
}

'''
src = src[:start] + new_glyph + src[end:]
main.write_text(src)

checks = ROOT / "tools/check_source.py"
test = checks.read_text()
old = '''        # Background is the violet brand gradient; foreground is the white zipper plus the amber slider.
        background = ET.parse(res / "drawable/ic_launcher_background.xml").getroot()
        self.assertEqual("vector", background.tag)
        gradient = next(background.iter("gradient"), None)
        self.assertIsNotNone(gradient)
        self.assertEqual(["#2B1B6E", "#4B3BD1", "#7C5CF0"], [item.get(android + "color") for item in gradient.findall("item")])
        foreground = ET.parse(res / "drawable/ic_launcher_foreground.xml")
        self.assertEqual("#FFFFFF", foreground.find("path").get(android + "fillColor"))
        fills = {path.get(android + "fillColor") for path in foreground.findall("path")}
        self.assertIn("#FFB300", fills)
        self.assertIn("#FF8F00", fills)
        self.assertNotIn("#FFC64D", fills)
'''
new = '''        # Final brand: four-stop blue tile, white archive box, navy zipper and cyan extraction pull.
        background = ET.parse(res / "drawable/ic_launcher_background.xml").getroot()
        self.assertEqual("vector", background.tag)
        gradient = next(background.iter("gradient"), None)
        self.assertIsNotNone(gradient)
        self.assertEqual(["#FF18A6DF", "#FF1766C4", "#FF17358E", "#FF101447"],
                         [item.get(android + "color") for item in gradient.findall("item")])
        foreground = ET.parse(res / "drawable/ic_launcher_foreground.xml").getroot()
        paths = list(foreground.iter("path"))
        fills = {path.get(android + "fillColor") for path in paths}
        gradient_colours = {item.get(android + "color") for item in foreground.iter("item")}
        self.assertGreaterEqual(len(paths), 9)
        self.assertIn("#FF173783", fills)
        self.assertIn("#FFFFFFFF", fills)
        self.assertIn("#FF59D7FF", gradient_colours)
        self.assertIn("#FF1B82DF", gradient_colours)
'''
assert old in test, "branding test anchor missing"
checks.write_text(test.replace(old, new, 1))
print("Applied final launcher icon, archive-file glyph and source-check fix")
