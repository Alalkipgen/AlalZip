# Archive

Native Kotlin + Jetpack Compose Android archive/file manager, updated in place from ArchivePocket. Launcher branding is a text-free zipper mark — white teeth and an amber slider on the violet brand gradient — as adaptive vector layers. Existing file/archive features are preserved. No website, backend, ads, login, AI key or Internet permission; direct file-manager browsing uses Android's all-files access.

Application ID and Kotlin package remain `app.archivepocket`; existing preferences and storage identifiers are unchanged. The project directory remains `/data/user/0/com.vscodroid/files/projects/ArchivePocket`.

Source deliverable: `/data/user/0/com.vscodroid/files/projects/Archive-Source.zip`. Local build is blocked by the missing JDK/SDK; GitHub Actions is prepared but **has not run**. Phone upload/run/download instructions: `/data/user/0/com.vscodroid/files/projects/ArchivePocket/docs/GITHUB_ACTIONS.md`. Icon preview: `/data/user/0/com.vscodroid/files/projects/ArchivePocket/docs/icon-preview.png`.

**Status: first-pass source implementation; not yet compiled or device-tested. No APK is included.** The authoring phone has no JDK, SDK or Gradle installation. Free space was approximately 2.2 GB initially and 5.9 GB at the final inspection. See BUILDING.md and TEST_REPORT.md for actual errors, checks and the alternative build procedure.

## Implemented in source

- Direct phone-storage browsing (file-manager style, like RAR): folder list with dates, checkboxes and a bottom path bar. Every fresh app launch starts at Internal storage, matching RAR-style behavior. Needs "All files access" (Android 11+) or the storage permission (Android 8-10).
- Copy, cut/paste, rename, delete confirmation, create folders, current-folder search, sort by name/size/date/type, show/hide hidden files, details with copy-path.
- List and grid views (toolbar toggle), quick-access menu (Internal storage, Download, DCIM, Pictures, Movies, Music, Documents), storage-usage bar with folder/file counts, clickable breadcrumb path bar, selection toolbar with total selected size.
- Share one or many files to other apps through the same non-exported `FileProvider` used for Open with.
- Cached asynchronous thumbnails for images, video frames (Android 10+) and APK icons, plus distinct page-style badges for audio, PDF, Word, spreadsheet, presentation, text/code and other files; colourful stacked-books glyph for archives.
- Secure `FileProvider` + Android Open-with support for images, videos, audio, APK, PDF, DOCX and other installed-viewer file types.
- Full-screen ZIP/RAR folder browser with internal path navigation and search. Opening a member extracts only that selected file into private cache (512 MiB limit), including optional archive passwords.
- Streaming copy with destination SHA-256 readback; move rechecks source against destination before deleting each source file.
- Collision confirmation preserves old data as `AP-backup-…`; never opens an existing document for output.
- Create ZIP, optionally AES-256 encrypted with user-supplied password. Extract ZIP/ZipCrypto/AES through Zip4j 2.11.6.
- RAR-style password flow: Extract / Extract here / open-member always run without a password first. Plain archives finish immediately; encrypted ones stop before writing anything and show a "Password required" prompt. A rejected password re-prompts with "Wrong password". Nothing is ever extracted with a missing or wrong password.
- RAR4/RAR5 extraction, including library-supported content/header encryption through Junrar 8.1.1. Library support verified in tagged source; app/runtime compatibility remains untested.
- Choose archive destination, byte progress, cooperative cancellation, errors, light/dark/system themes.
- Staging happens on the destination volume (`.alalzip-tmp-…`), so finished results are renamed into place instead of copied a second time: no doubled free-space need and no extra read passes.
- No fixed archive-size cap. The expansion limit is the destination volume's own free space minus a 64 MiB reserve, while the zip-bomb guards stay: 10,000 entries, depth 32, 1000:1 ratio and a 64 MiB RAR dictionary.
- Long extract/zip/copy runs continue in the background through a `dataSync` foreground service with a progress notification and a Cancel action.
- Copies above 512 MiB skip the SHA-256 readback by default (size and timestamp are still checked); “Verify large copies” in the overflow menu turns full hashing back on.

## Usage after building

Allow storage access on first start. Tap a folder to browse; tap an ordinary file to open it with an installed viewer; tap ZIP/RAR to browse it like a folder without full extraction. Inside an archive, tap a member to open it or use its menu for a password. Tap the path bar to jump up; use checkboxes (or long-press) to select. Copy/Cut → browse to destination → Paste. Create ZIP writes into the current folder; Extract creates a sub-folder in the current folder; the password field can stay empty because encrypted archives ask for their password only when needed. Selecting a folder for deletion deletes its contents too; confirmation is mandatory. Password entry is masked and is not persisted.

မြန်မာ: ဖိုင်အစားထိုးလျှင် အဟောင်းကို `AP-backup-…` အဖြစ်ထားပါမယ်။ ဖိုင်ကြီးများ extract/zip လုပ်နေချိန် app ကို ပိတ်ထားလည် foreground service + notification နဲ့ ဆက်လုပ်ပါတယ်။ ပထမဆုံး disposable files နဲ့ စမ်းပါ။

## Source layout

- `app/src/main/java/app/archivepocket/`: Compose activity, ViewModel, file repository, pure-JVM archive/safety core.
- `app/src/test/`: JUnit tests and six tiny upstream RAR fixtures with provenance hashes.
- `licenses/`, `THIRD_PARTY_NOTICES.md`: third-party terms.
- `tools/check_source.py`: offline structural checks (not a Kotlin compiler).
- `tools/package_source.py`: allowlisted source ZIP generation and byte-for-byte verification.
- `docs/MANUAL_TESTS.md`: disposable-device test checklist.

On the authoring device, project root is `/data/user/0/com.vscodroid/files/projects/ArchivePocket`.

```sh
python3 /data/user/0/com.vscodroid/files/projects/ArchivePocket/tools/check_source.py
```

Read KNOWN_LIMITATIONS.md before using real data. This first pass is not a production-ready replacement for a mature archive manager.

## 7z and TAR.GZ support (0.8.0 source)

- Create and extract standard `.7z` archives. Optional passwords use 7z AES encryption and encrypted headers/file names.
- Create and extract standard `.tar.gz` / `.tgz` archives. TAR.GZ has no standard password-encryption feature, so the UI intentionally directs encrypted archives to 7z.
- The Create Archive dialog now offers ZIP, 7Z and TAR.GZ. Existing destination staging, free-space checks, zip-bomb limits, cancellation and foreground progress remain active.
