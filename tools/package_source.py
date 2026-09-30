#!/usr/bin/env python3
"""Allowlist packaging, integrity verification, no external uploads."""
from pathlib import Path
import hashlib
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT.parent / "Archive-Source.zip"
TOP = {"settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore",
       "README.md", "BUILDING.md", "KNOWN_LIMITATIONS.md", "THIRD_PARTY_NOTICES.md", "TEST_REPORT.md", "LICENSE"}
TREES = {"app/src", "licenses", "tools", "docs"}
SPECIAL = {"app/build.gradle.kts", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties", ".github/workflows/android.yml"}
EXCLUDED = {"build", ".gradle", ".kotlin", ".git", "__pycache__", ".idea"}


def allowed(path):
    relative = path.relative_to(ROOT)
    name = relative.as_posix()
    return (not path.is_symlink() and not EXCLUDED.intersection(relative.parts)
            and relative.name not in {"local.properties", "key.properties", "keystore.properties", "secrets.properties"}
            and not any(part.startswith((".env", "credentials")) or part == "secrets" for part in relative.parts)
            and path.suffix.lower() not in {".apk", ".aab", ".jks", ".keystore", ".pyc", ".pem", ".key", ".p12", ".pfx", ".log"}
            and (name in TOP or name in SPECIAL or any(name.startswith(tree + "/") for tree in TREES)))


def main():
    if OUTPUT.exists() and "--replace-generated" not in sys.argv:
        raise SystemExit(f"Refusing to overwrite {OUTPUT}; use --replace-generated only for this generated deliverable.")
    files = sorted(path for path in ROOT.rglob("*") if path.is_file() and allowed(path))
    required = TOP | SPECIAL | {"app/src/main/AndroidManifest.xml", "app/src/main/java/app/archivepocket/MainActivity.kt",
        "app/src/test/java/app/archivepocket/core/ArchiveEngineTest.kt", "app/src/test/java/app/archivepocket/core/RarEngineTest.kt",
        "licenses/junrar-LICENSE.txt", "licenses/zip4j-LICENSE.txt", "app/src/test/resources/rar/provenance.json",
        "docs/MANUAL_TESTS.md", "docs/GITHUB_ACTIONS.md", "docs/icon-preview.png",
        "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", "app/src/main/res/drawable/ic_launcher_foreground.xml",
        "app/src/main/assets/THIRD_PARTY_NOTICES.md", "app/src/main/assets/licenses/junrar-LICENSE.txt"}
    required |= {f"app/src/main/res/mipmap-{density}/{name}.png"
                 for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
                 for name in ("ic_launcher", "ic_launcher_round")}
    required |= {"app/src/main/res/drawable/ic_launcher_background.xml",
                 "app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", "tools/generate_icons.py"}
    included = {path.relative_to(ROOT).as_posix() for path in files}
    assert required <= included, f"Missing: {required - included}"
    with zipfile.ZipFile(OUTPUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            archive.write(path, "ArchivePocket/" + path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(OUTPUT) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == len(files)
        for path in files:
            name = "ArchivePocket/" + path.relative_to(ROOT).as_posix()
            assert archive.read(name) == path.read_bytes(), name
    print(f"Verified {len(files)} files; {OUTPUT.stat().st_size} bytes")
    print(OUTPUT)
    print("SHA256", hashlib.sha256(OUTPUT.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()