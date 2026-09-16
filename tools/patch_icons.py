"""One-time patch: restyle the in-app archive glyph to match the new launcher icon."""
from pathlib import Path

main = Path("app/src/main/java/app/archivepocket/MainActivity.kt")
src = main.read_text()

# 1. Navy / paper palette used by the new zip-folder artwork.
anchor = 'private val FolderBlueDark = Color(0xFF1C7BE0)\n'
assert anchor in src, "palette anchor missing"
assert "NavyDeep" not in src, "palette already patched"
src = src.replace(
    anchor,
    anchor
    + 'private val NavyBright = Color(0xFF2A55C8)   // launcher icon: top-left highlight\n'
    + 'private val NavyDeep = Color(0xFF0B1E62)     // launcher icon: body navy\n'
    + 'private val NavyInk = Color(0xFF050A2C)      // launcher icon: bottom-right shadow\n'
    + 'private val PaperBlue = Color(0xFFD8E6FF)    // launcher icon: folder paper shading\n',
    1,
)

# 2. Replace the whole ArchiveGlyph composable with the zip-folder artwork.
start_marker = "/** Archive glyph that mirrors the launcher icon"
end_marker = "/** A rounded colour tile carrying the file-type label"
start = src.index(start_marker)
end = src.index(end_marker)

new_glyph = '''/** Archive glyph that mirrors the launcher icon: navy tile, white zip folder, extract arrow. */
@Composable
private fun ArchiveGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun px(v: Float) = w * v
        fun py(v: Float) = h * v
        drawRoundRect(
            Brush.linearGradient(listOf(NavyBright, NavyDeep, NavyInk)),
            Offset(0f, 0f), Size(w, h), CornerRadius(w * 0.26f)
        )
        val paper = Brush.verticalGradient(listOf(Color.White, PaperBlue))
        // Folder tab, then the body: same silhouette as the launcher mark.
        drawRoundRect(paper, Offset(px(0.15f), py(0.155f)), Size(px(0.30f), py(0.20f)), CornerRadius(w * 0.045f))
        drawRoundRect(paper, Offset(px(0.13f), py(0.26f)), Size(px(0.74f), py(0.50f)), CornerRadius(w * 0.065f))
        // Zipper channel splitting the folder, with alternating white teeth.
        drawRect(NavyDeep, Offset(px(0.455f), py(0.155f)), Size(px(0.09f), py(0.605f)))
        var toothY = 0.185f
        var left = true
        while (toothY < 0.55f) {
            val toothX = if (left) 0.395f else 0.50f
            drawRoundRect(Color.White, Offset(px(toothX), py(toothY)), Size(px(0.105f), py(0.036f)), CornerRadius(w * 0.018f))
            left = !left
            toothY += 0.055f
        }
        // Slider head plus the pull tab with its punched-out hole.
        drawRoundRect(Color.White, Offset(px(0.35f), py(0.555f)), Size(px(0.30f), py(0.135f)), CornerRadius(w * 0.05f))
        val pull = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(RoundRect(px(0.38f), py(0.675f), px(0.62f), py(0.90f), CornerRadius(w * 0.04f)))
            addRoundRect(RoundRect(px(0.445f), py(0.735f), px(0.555f), py(0.835f), CornerRadius(w * 0.02f)))
        }
        drawPath(pull, Color.White)
        // Extract arrow.
        val arrow = Path().apply {
            moveTo(px(0.60f), py(0.695f))
            lineTo(px(0.78f), py(0.695f))
            lineTo(px(0.78f), py(0.615f))
            lineTo(px(0.96f), py(0.755f))
            lineTo(px(0.78f), py(0.895f))
            lineTo(px(0.78f), py(0.815f))
            lineTo(px(0.60f), py(0.815f))
            close()
        }
        drawPath(arrow, Color.White)
    }
}

'''

src = src[:start] + new_glyph + src[end:]

# 3. hypot was only used by the old zipper tape helper.
if "hypot(" not in src:
    src = src.replace("import kotlin.math.hypot\n", "", 1)

main.write_text(src)
print("patched", main)
