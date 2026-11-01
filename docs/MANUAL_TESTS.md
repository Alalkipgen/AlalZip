# Disposable device acceptance checklist — NOT RUN

Run only after a successful build and JVM test run on a supported toolchain. Use a newly created scratch folder, never personal originals. Record Android version, provider, app version, expected/actual result for each row.

1. Create scratch Source/Destination folders with Android Files. Add small ASCII text, Burmese/Japanese/emoji names, an empty file, empty folder, nested folders and a disposable 20–100 MiB file. Record original SHA-256 independently on a desktop if available.
2. Choose Source using SAF. Check listing, navigation Up, multi-select, current-folder search and name/size/date sort. Rotate and verify browsing state. Reopen app to verify persisted grant. Revoke grant and verify a clear recovery error.
3. Copy into Destination; compare file bytes/SHA-256, names, empty directories. Cut a disposable file and paste; destination must verify before original disappears. Cut a folder; verify files moved and empty source folders remain by design.
4. Attempt copy into itself/descendant and same-item paste under a differently granted tree. Expect refusal, not recursive growth or original deletion.
5. Create a collision. Cancel confirmation: old bytes unchanged. Confirm replacement: old bytes retained under AP-backup name, new bytes correct. Test case-only and NFC/NFD-equivalent names. Do not assume provider atomicity.
6. Rename to a fresh name; collision must fail. Create folder; duplicate must fail. Delete Cancel retains data; confirm deletes selected disposable items only. Folder delete includes children.
7. ZIP plain round trip: create from mixed files/folders, extract into a new folder, compare bytes and Unicode names. Test an empty ZIP separately using JVM fixtures.
8. AES-256 ZIP: create with a disposable password; correct password succeeds, wrong and blank passwords fail without exporting staged plaintext. Independently open using a trusted AES-capable archiver. Check AES-256 metadata in supplied JVM test.
9. Import six bundled RAR fixtures into scratch storage. Test RAR4 and RAR5 plain (file1.txt/file2.txt); then content encryption and header encryption (file1.txt, public password `junrar`). Test wrong/blank passwords. Mark every variant separately.
10. Corrupt/truncate copies of ZIP/RAR on a desktop. Reject damaged archive; do not report success for skipped RAR headers. Attempt archive paths `../escape`, absolute paths, duplicate paths, file/directory collisions; nothing outside staging/destination should appear.
11. Cancel during staging, ZIP creation, extraction, export, copy and move verification. Source archives remain. Only fully verified moved source files may have been removed; earlier completed items remain. Inspect for partial output and AP-backup entries.
12. Test low private space using an emulator quota or small controlled test volume—do not fill the user's phone. Expect reserve error and private cleanup. Test read-only destination, permission revocation, disconnected removable drive and providers refusing rename or destination readback.
13. Test a safely generated over-limit archive on an isolated test device: entry count, depth, declared/actual expansion, ratio and RAR dictionary limits. Never use personal storage for decompression-bomb experiments.
14. Light/dark/system UI, large font, small screen, TalkBack, keyboard, rotation during password input, screen capture suppression. Ensure password is not restored from saved state.
15. Airplane mode with a local SAF provider: all local file/archive functions should work. No Internet permission, account, key or ads should appear. Cloud-backed third-party document providers may independently require networking and are outside the app's offline guarantee.
16. Background/force-stop mid-operation to document the known limitation: no operation resume, private staging cleaned next start, partial new destination may remain. This is not a passing production-recovery feature.
17. Operation ownership, on Android 11 and on Android 14 or 15: start a long extraction and press Back; open a ZIP from a file manager, start extracting it and press Back to return to the file manager; swipe Archive away from Recents. Each time the notification must keep progressing and the result must appear when Archive is opened again. Starting a second operation from another Archive window must be refused. A name collision while Archive is in the background must show “Needs your decision”, and tapping the notification must open the question. On Android 15 run `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 60000`, start a long operation and leave Archive: expect a “Stopped by Android” notification, a cancelled operation and no crash or ANR.

## JVM tests to run

From desktop project root `/home/developer/ArchivePocket` (example):

```sh
bash /home/developer/ArchivePocket/gradlew :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Two JUnit classes contain 14 test methods covering ZIP Unicode/AES round trips, wrong/missing passwords, corruption, traversal, duplicate case collisions, cancellation, size/ratio/name limits, empty ZIP, plain/encrypted RAR4/RAR5 and RAR corruption. Several methods cover multiple variants. These are not SAF/instrumentation tests.