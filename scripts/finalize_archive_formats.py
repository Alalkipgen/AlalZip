#!/usr/bin/env python3
from pathlib import Path
root = Path(__file__).resolve().parents[1]

def edit(path, old, new):
    p = root / path
    text = p.read_text()
    if old not in text: raise SystemExit(f'missing marker: {path}')
    p.write_text(text.replace(old, new, 1))

edit('tools/check_source.py',
'''        self.assertEqual({"MainActivity.kt", "PocketViewModel.kt", "FileRepository.kt", "Safety.kt", "ArchiveEngine.kt",
                          "OperationService.kt"}, {path.name for path in sources})
''',
'''        self.assertEqual({"MainActivity.kt", "PocketViewModel.kt", "FileRepository.kt", "Safety.kt", "ArchiveEngine.kt",
                          "OperationService.kt", "SevenZipSupport.kt", "TarGzSupport.kt"}, {path.name for path in sources})
''')
edit('tools/check_source.py',
'''        self.assertIn("com.github.junrar:junrar:8.1.1", build)
''',
'''        self.assertIn("com.github.junrar:junrar:8.1.1", build)
        self.assertIn("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03", build)
        self.assertIn("org.apache.commons:commons-compress:1.27.1", build)
''')

with (root / 'README.md').open('a') as f:
    f.write('''\n\n## 7z and TAR.GZ support (0.8.0 source)\n\n- Create and extract standard `.7z` archives. Optional passwords use 7z AES encryption and encrypted headers/file names.\n- Create and extract standard `.tar.gz` / `.tgz` archives. TAR.GZ has no standard password-encryption feature, so the UI intentionally directs encrypted archives to 7z.\n- The Create Archive dialog now offers ZIP, 7Z and TAR.GZ. Existing destination staging, free-space checks, zip-bomb limits, cancellation and foreground progress remain active.\n''')

with (root / 'KNOWN_LIMITATIONS.md').open('a') as f:
    f.write('''\n\n## 7z / TAR.GZ\n\n- 7z support uses the Android 7-Zip-JBinding native engine. Password-protected 7z uses AES with encrypted headers. Runtime/device tests should cover every supported CPU ABI.\n- TAR.GZ has no interoperable password-encryption standard. Password creation is therefore unavailable for TAR.GZ; choose 7z when encryption is required.\n- TAR symbolic and hard links are rejected instead of being recreated. 7z and TAR.GZ in-app member browsing is not included yet; full extraction is supported.\n''')
