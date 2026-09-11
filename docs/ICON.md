# Alal Zip launcher artwork

Typographic icon: white “Alal” (Liberation Serif Bold Italic) above a white interlocking zipper-teeth row with an amber (#FFB300 / #FF8F00) slider and pull tab, then small tracked “ZIP” (Liberation Sans Bold), centered on pure black. Liberation fonts are SIL Open Font License, so their glyph outlines may be embedded as vector paths. All shapes are generated rectangles and glyph outlines: no gradients, pictures or downloaded artwork.

Adaptive foreground: `app/src/main/res/drawable/ic_launcher_foreground.xml` (108dp vector, glyph outlines converted to `pathData`).
Background: `app/src/main/res/drawable/ic_launcher_background.xml` (#000000).
Both adaptive launcher references are in `app/src/main/res/mipmap-anydpi-v26`.

Legacy normal/round resources are real 48/72/96/144/192px PNGs for mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi (square black artwork; launcher masks choose their shape). All outline corners fit inside the centered 66dp-diameter adaptive safe circle.

Previews (square, circle and rounded square): `docs/icon-preview.png`, `docs/icon-preview-circle.png`, `docs/icon-preview-rounded.png`.

Regenerate (needs `python3 -m pip install fonttools pillow` and the Liberation fonts installed):

```sh
python3 tools/generate_icons.py
```

The old unused ArchivePocket drawable is retained as historical artwork, but no longer referenced by the manifest.
