# Validation report — 2026-09-10

## Archive targeted update

- Updated existing project in place: launcher label, title, About, empty-state/error branding and README. Application ID/package `app.archivepocket`, archive logic, preferences and folder path are preserved.
- Real adaptive vector foreground + solid black background and ten legacy PNGs (five densities, normal/round) generated. Square/circle/rounded previews visually inspected. Geometry checks keep the artwork within the adaptive safe circle; PNG dimensions, CRC and decompression checks passed. Device launcher rendering is NOT tested.
- Updated offline structural suite: **10/10 passed**, including icon/branding, workflow markers and portable build configuration. Official Gradle wrapper remains complete and checksum-valid. Shell syntax check passed.
- Local `assembleDebug` retried: **failed, exit 1**, Java missing. No JDK/SDK/Gradle/AAPT2/adb found in PATH or inspected locations; native build tools could not be executed. This failure happened before project compilation, so it reveals no Kotlin compile result. No large downloads or system changes made.
- GitHub workflow prepared with manual triggering, read-only contents permission, Ubuntu 24.04, JDK 17, SDK/build tools 35, build/test/lint, APK signing check and artifact upload. Exact action release tags verified upstream. **No repository/upload/remote run performed; no GitHub build success claimed.**
- Original project and original source ZIP retained. Updated source ZIP is separate and contains no APK. Packaging checks compare all archive entries byte-for-byte with source and verify required workflow/resources.

Historical first-pass checks below remain applicable; the newer structural test count above supersedes the old count.

## Status

Source-only first pass. **No successful Kotlin compilation, APK, JVM test run, lint run or Android runtime test.** Do not use with irreplaceable files before desktop compilation and disposable-device acceptance testing.

## Executed checks

- Python structural suite: **8 checks passed** after adding documentation and byte-identical bundled-notice verification. Checks cover XML parsing, no manifest permissions/backup, official wrapper checksum and JAR integrity, six RAR fixture hashes/signatures, expected source files, safety-hook presence and pinned archive dependencies. An initial notice-length assertion incorrectly rejected Zip4j's legitimate 55-byte copyright notice; the check was corrected for NOTICE files, assets copied and the full suite rerun successfully. These are structural assertions, NOT functional validation.
- Both Python tools parsed successfully with Python's AST parser.
- `bash -n` on the Gradle wrapper: passed (shell syntax only).
- Zip4j 2.11.6 and Junrar 8.1.1 published JAR ZIP integrity: passed; class major version 52 (Java 8) in both. This is not proof of Android runtime compatibility.
- Reviewed core, SAF repository, ViewModel, Compose UI, build configuration and test sources. Replaced nullable primitive-array `isNullOrEmpty()` usage with explicit null/empty checks and added volatile visibility for cross-thread cancellation/confirmation references. These edits remain uncompiled.
- Published DocumentFile 1.0.1 source confirms `fromTreeUri` preserves a child document ID when the supplied URI is a document URI. Published Junrar 8.1.1 source confirms `hasBrokenHeaders()`; tagged upstream source was reviewed for archive options and encrypted RAR support. This source review is not a runtime test.
- Full app compilation and tests attempted again using Bash and tasks `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon`; process exited **1** before Gradle startup:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

- No toolchain installation, large downloads, SDK license acceptance, external upload, paid service or shared-storage export was performed.

## Supplied but NOT RUN

- 14 JUnit methods in two classes: ZIP plain/AES/Unicode round trips, wrong/missing ZIP passwords, empty ZIP, corruption, traversal, case collisions, limits and cancellation; RAR4/RAR5 plain, encrypted data/headers with correct/wrong passwords, truncated RAR5.
- SAF behavior, provider collisions/readback, moves/deletes, permission loss, low-space cleanup, cancellation timing, process death, Android crypto/desugaring, accessibility and UI layout: all untested.
- Device checklist is in the project's docs directory. It must be executed with disposable data.

## Source delivery validation

The packaging script requires documentation, build configuration, source, tests, fixtures and licenses; excludes build caches, APKs, machine-local configuration and signing keys; checks ZIP CRC integrity and byte-for-byte equality with every packaged file. The delivery response records the actual final packaging result and SHA-256 (kept outside this document to avoid a self-referential hash).

## Next gate

Use desktop JDK 17 and Android SDK 35, resolve dependencies, run JUnit + lint + assembleDebug, fix real failures, then perform device tests. An APK should only be delivered after successful compilation. Consult the project's BUILDING and KNOWN_LIMITATIONS documents before proceeding.