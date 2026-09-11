#!/usr/bin/env python3
"""Offline structural checks only: NOT Kotlin compilation or Android/runtime tests."""
from pathlib import Path
import hashlib
import json
import re
import unittest
import xml.etree.ElementTree as ET
import zipfile
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]


class SourceChecks(unittest.TestCase):
    def test_xml_resources(self):
        for path in (ROOT / "app/src/main").rglob("*.xml"):
            ET.parse(path)

    def test_offline_and_no_backup(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = {p.get(android + "name") for p in manifest.findall("uses-permission")}
        # Storage-only permissions for direct file-manager browsing; never network.
        self.assertEqual({"android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
                          "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.REQUEST_INSTALL_PACKAGES"}, permissions)
        self.assertNotIn("android.permission.INTERNET", permissions)
        self.assertEqual("false", manifest.find("application").get("{http://schemas.android.com/apk/res/android}allowBackup"))

    def test_official_wrapper(self):
        jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
        self.assertEqual("2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046", hashlib.sha256(jar.read_bytes()).hexdigest())
        with zipfile.ZipFile(jar) as archive:
            self.assertIsNone(archive.testzip())
            self.assertIn("org/gradle/wrapper/GradleWrapperMain.class", archive.namelist())
        self.assertIn("distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26", (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text())

    def test_fixture_provenance_and_signatures(self):
        directory = ROOT / "app/src/test/resources/rar"
        fixtures = json.loads((directory / "provenance.json").read_text())
        self.assertEqual(6, len(fixtures))
        for fixture in fixtures:
            data = (directory / fixture["file"]).read_bytes()
            self.assertEqual(fixture["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(fixture["size"], len(data))
            self.assertTrue(data.startswith(b"Rar!\x1a\x07\x01\x00" if fixture["file"].startswith("rar5") else b"Rar!\x1a\x07\x00"))

    def test_no_fake_or_password_logging(self):
        sources = list((ROOT / "app/src/main/java").rglob("*.kt"))
        self.assertEqual({"MainActivity.kt", "PocketViewModel.kt", "FileRepository.kt", "Safety.kt", "ArchiveEngine.kt"}, {path.name for path in sources})
        for path in sources:
            text = path.read_text()
            self.assertNotRegex(text, r"TODO\(|NotImplementedError|Thread\.sleep|android\.util\.Log|println\(")
            self.assertNotIn("File(uri.path", text)
        ui = (ROOT / "app/src/main/java/app/archivepocket/MainActivity.kt").read_text()
        self.assertNotRegex(ui, r"password by rememberSaveable")
        self.assertIn("PasswordVisualTransformation", ui)

    def test_safety_hooks_present(self):
        # Presence assertions guard accidental removal, not behavioral correctness.
        core = (ROOT / "app/src/main/java/app/archivepocket/core/ArchiveEngine.kt").read_text()
        saf = (ROOT / "app/src/main/java/app/archivepocket/data/FileRepository.kt").read_text()
        for marker in ("hasBrokenHeaders()", "maxDictionarySize", "Safety.target", "ExpansionBudget", "KEY_STRENGTH_256", "isSplitArchive"):
            self.assertIn(marker, core)
        for marker in ("deleteVerifiedSource", "sourceHash.contentEquals(destinationHash)", "AP-backup-", "isSymbolicLink", "inside(source.file, destination)", "take(150)"):
            self.assertIn(marker, saf)

    def test_dependencies_pinned(self):
        build = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn("net.lingala.zip4j:zip4j:2.11.6", build)
        self.assertIn("com.github.junrar:junrar:8.1.1", build)
        self.assertNotRegex(build, r':[+]"|SNAPSHOT')

    def test_documentation_and_bundled_notices(self):
        for name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "TEST_REPORT.md", "docs/MANUAL_TESTS.md"):
            self.assertGreater((ROOT / name).stat().st_size, 100)
        assets = ROOT / "app/src/main/assets"
        for source in (ROOT / "licenses").glob("*.txt"):
            text = source.read_text()
            self.assertGreater(len(text), 20 if source.name.endswith("-NOTICE.txt") else 100)
            self.assertNotIn("404: Not Found", text)
            self.assertFalse(text.lstrip().lower().startswith("<!doctype html"))
            self.assertEqual(source.read_bytes(), (assets / "licenses" / source.name).read_bytes())
        for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):
            self.assertEqual((ROOT / name).read_bytes(), (assets / name).read_bytes())

    def test_alal_zip_branding_and_icons(self):
        res = ROOT / "app/src/main/res"
        self.assertEqual("Alal Zip", ET.parse(res / "values/strings.xml").find("string[@name='app_name']").text)
        app = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").find("application")
        android = "{http://schemas.android.com/apk/res/android}"
        self.assertEqual("@mipmap/ic_launcher", app.get(android + "icon"))
        self.assertEqual("@mipmap/ic_launcher_round", app.get(android + "roundIcon"))
        self.assertIn('applicationId = "app.archivepocket"', (ROOT / "app/build.gradle.kts").read_text())
        for name in ("ic_launcher", "ic_launcher_round"):
            icon = ET.parse(res / "mipmap-anydpi-v26" / (name + ".xml")).getroot()
            self.assertEqual("adaptive-icon", icon.tag)
            for layer in ("background", "foreground"):
                self.assertEqual("@drawable/ic_launcher_" + layer, icon.find(layer).get(android + "drawable"))
        background = ET.parse(res / "drawable/ic_launcher_background.xml")
        self.assertEqual("#000000", background.find("solid").get(android + "color"))
        foreground = ET.parse(res / "drawable/ic_launcher_foreground.xml")
        self.assertEqual("#FFFFFF", foreground.find("path").get(android + "fillColor"))
        try:
            from generate_icons import CORNERS  # needs fonttools + pillow; skipped when unavailable
        except ImportError:
            CORNERS = []
        self.assertTrue(all((x-54)**2 + (y-54)**2 < 33**2 for x, y in CORNERS))
        for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
            for name in ("ic_launcher", "ic_launcher_round"):
                data = (res / f"mipmap-{density}" / f"{name}.png").read_bytes()
                self.assertEqual(b"\x89PNG\r\n\x1a\n", data[:8])
                self.assertEqual((size,size), struct.unpack(">II", data[16:24]))
                offset, compressed = 8, bytearray()
                while offset < len(data):
                    length = struct.unpack(">I", data[offset:offset+4])[0]
                    kind = data[offset+4:offset+8]
                    body = data[offset+8:offset+8+length]
                    crc = struct.unpack(">I", data[offset+8+length:offset+12+length])[0]
                    self.assertEqual(crc, zlib.crc32(kind+body))
                    if kind == b"IDAT": compressed.extend(body)
                    offset += length+12
                raw = zlib.decompress(compressed)
                self.assertEqual(size*(1+size*4), len(raw))
                pixels = b"".join(raw[y*(1+size*4)+1:(y+1)*(1+size*4)] for y in range(size))
                self.assertIn(b"\xff\xff\xff\xff", pixels)
                self.assertEqual(b"\x00\x00\x00\xff", pixels[:4])

    def test_ci_and_portable_build_configuration(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        for marker in ("workflow_dispatch:", "contents: read", "ubuntu-24.04", "java-version: '17'",
                       "./gradlew assembleDebug", "if-no-files-found: error", "Alal-Zip-debug.apk"):
            self.assertIn(marker, workflow)
        for name in ("gradle.properties", "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts"):
            text = (ROOT / name).read_text()
            for forbidden in ("/data/user/", "/data/data/", "aapt2FromMavenOverride", "org.gradle.java.home", "sdk.dir"):
                self.assertNotIn(forbidden, text)


if __name__ == "__main__":
    unittest.main(verbosity=2)