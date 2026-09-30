# Archive launcher artwork

Text-free “Glass Zip” icon: a frosted glass document closed by a cyan zipper on an indigo-to-violet gradient. The artwork uses generated vector geometry with no wordmark, downloaded image, or third-party asset.

Adaptive foreground: `app/src/main/res/drawable/ic_launcher_foreground.xml` (108dp vector).  
Gradient background: `app/src/main/res/drawable/ic_launcher_background.xml`.  
Android 13 monochrome layer: `app/src/main/res/drawable/ic_launcher_monochrome.xml`.

Adaptive launcher references are in `app/src/main/res/mipmap-anydpi-v26`; themed-icon references are in `mipmap-anydpi-v33`. Legacy density PNGs remain for manifest compatibility.

Previews: `docs/icon-preview.png`, `docs/icon-preview-circle.png`, `docs/icon-preview-rounded.png`.

Regenerate the vectors with:

```sh
python3 tools/generate_icons.py
```

The old unused ArchivePocket drawable is retained as historical artwork but is not referenced by the manifest.
