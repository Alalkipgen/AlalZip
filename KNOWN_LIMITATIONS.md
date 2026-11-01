# Known limitations / unfinished work

## Validation and lifecycle

- No Kotlin compilation, Android lint, JVM JUnit execution or device tests were possible here. No APK exists. Structural Python checks are not application behavior tests.
- One operation runs at a time, owned by the app process rather than a screen, and a `dataSync` foreground service keeps it going in the background. Back, Recents and closing a screen do not cancel it, but nothing is resumed after the process dies or Android stops the service (Android 15+ limits `dataSync` time per day). Cancellation is cooperative at stream boundaries, not immediate while a provider/library parses, derives a key or blocks in I/O.
- Extract, copy, move and archive creation build results in a hidden `.alalzip-tmp-…` folder inside the destination and rename them into place only when complete; each step is first recorded in a journal in app-private storage. After a killed process, the next start deletes the journaled staging folders, gives AP-backup items whose replacement never completed their name back and says what was interrupted. Nothing is resumed: items finished before the interruption stay, and an interrupted move may leave both copies of items whose source was not deleted yet. Leftovers that no journal recorded (for example from older versions) are never searched for or removed.
- Search is current-folder/archive-folder name search only, not recursive. File viewing depends on a compatible installed Android app. No built-in media/PDF/office renderer, favorites, tabs, share action, split-volume selection, configurable limits, timestamps/permissions preservation, recycle bin or undo.
- ZIP/RAR browsing is metadata-only until a file is tapped; then only that member is copied into private cache, with a 512 MiB per-file viewing limit. Split or linked members are refused.
- UI strings are English; Burmese guidance is in the delivery/docs. Screen-reader/device layout testing is outstanding. Theme selection survives activity recreation but is not a persistent preference across fresh processes.

## File safety / SAF

- SAF providers have no general atomic no-clobber create/rename/delete transaction. The app checks collisions and never intentionally opens existing files for output, but cannot protect against a malicious/non-conforming provider or external changes between check and operation.
- Replacements require confirmation and retain old data under an AP-backup name, given only once the new item is complete. A backup gets its old name back only when its replacement never completed; backups are never deleted automatically. Provider refusal aborts replacement.
- Move verifies destination bytes, rehashes source and destination before source deletion, and checks source metadata. A simultaneous external edit in the final check/delete window cannot be excluded by SAF; avoid concurrent editors. Unreadable destination providers cannot be used for verified moves.
- Folder moves remove verified files but deliberately leave empty source directory structures. Recursive source-folder deletion would risk deleting newly added, unverified files. Clean those empty folders manually after inspection.
- Multi-item operations are not all-or-nothing: previously completed copies/moves/deletions remain after later failure or cancellation. Delete itself can be non-cancellable while a provider recursively deletes a folder.
- Some providers do not implement child checks or metadata consistently. Operations may fail conservatively. Only tree-picked content URIs are browsed, never converted to filesystem paths.
- Directory listing errors can be hidden by AndroidX DocumentFile/provider behavior. No universal free-space API exists for arbitrary SAF destinations; private free space is checked and destination write failures are handled.

## Archive formats

- ZIP: Store/Deflate, ZipCrypto read, AES read/write via Zip4j; other compression/encryption methods may fail. AES ZIP does not hide filenames. No split ZIP, self-extracting executables or nested archive recursion.
- RAR: Junrar 8.1.1 tagged documentation and implementation include RAR4/RAR5, data and header encryption. Java 8 bytecode/API surface checked; Android API 26+ selected and core-library desugaring enabled. **Not device-tested and not certified universally Android-compatible.** RAR7/newer algorithm variants are not promised even where upstream accepts them.
- No RAR creation, multi-volume RAR support, links/redirections, recovery/repair. RAR4 link payloads/ZIP Unix symlink payloads become ordinary files, never real links; RAR5 explicit redirections are rejected.
- Junrar exposes broken-header recovery; ArchivePocket refuses `hasBrokenHeaders()` rather than reporting incomplete extraction as success. RAR archives with no checksum cannot provide an archive-integrity guarantee; copied output still receives SHA-256 readback verification.

## Limits / temporary data

- Extraction limit: 2 GiB total output; compressed staging also capped at 2 GiB; 10,000 entries; 32 path components; 240 characters per component; ratio over 1000:1 rejected after 16 MiB; RAR dictionary budget 64 MiB. Legitimate large/highly compressible archives may be refused.
- These are output/resource safeguards, not a sandbox against every malicious-parser CPU/memory attack. Libraries parse headers before some application entry-count checks. RAR solid archives can use CPU between output callbacks.
- Input archive is staged locally; extraction is fully staged and checked before export to a new destination folder. ZIP creation uses a temporary compressed output, not a full copy of input files. Streaming buffers are 64 KiB.
- Private workspace maintains 64 MiB reserve, checks space during writes and deletes operation-owned temporary files in finally. Cache files are not securely erased on flash; encrypted storage at the OS level is recommended.
- Passwords are never logged, saved in preferences, backed up or sent over a network. UI String memory and library defensive copies cannot be reliably wiped; owned CharArrays are cleared at operation completion. The public test-fixture passwords in tests are not personal secrets.

## UI / viewer features (0.4.0)

- Video thumbnails need Android 10+ (`ThumbnailUtils.createVideoThumbnail(File, Size, ...)`); older devices show the purple VID badge instead.
- Thumbnails are cached in memory only (96 entries) and are regenerated after the process restarts.
- Share and Open with hand the file to other apps through `FileProvider`; whether the receiving app can handle the type is outside this app's control.
- Quick-access folders are only offered when they exist under Internal storage; SD cards and USB drives are not browsed.
- Grid/list, sort, hidden-files and theme choices persist only while the app process lives (no saved preferences by design).

- Password detection (0.4.1): ZIP uses Zip4j header flags and WRONG_PASSWORD/CHECKSUM_MISMATCH errors, which are reliable. RAR relies on Junrar exception classes (UnsupportedRarEncrypted*, CrcError*, InitDecipherer*); encrypted-header RAR with a wrong password reports "Wrong password or damaged RAR archive" instead of re-prompting, and encrypted RAR5 is not supported by Junrar.

## Next priorities

Compile on a supported desktop; run JUnit + lint; fix actual failures; exercise local/external SAF providers; add foreground-service journaling and process-death recovery; instrument provider collision/cancellation tests; validate RAR crypto and solid variants on Android; then expand advanced features.

## 7z / TAR.GZ

- 7z support uses the Android 7-Zip-JBinding native engine. Password-protected 7z uses AES with encrypted headers. Runtime/device tests should cover every supported CPU ABI.
- TAR.GZ has no interoperable password-encryption standard. Password creation is therefore unavailable for TAR.GZ; choose 7z when encryption is required.
- TAR symbolic and hard links are rejected instead of being recreated. 7z and TAR.GZ in-app member browsing is not included yet; full extraction is supported.
