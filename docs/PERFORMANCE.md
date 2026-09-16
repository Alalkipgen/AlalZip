# Archive streaming performance changes

## Scope

Source changes against main at `3fe6095a1a4bce52778289655642dc1ec301c39d`.
These optimize known-password archive operations, not password guessing or recovery.
No speed multiplier is claimed: no Android benchmark was available in the editing environment.

- ZIP creation no longer computes a SHA-256 digest that the caller discards. Verified file copies still hash and verify as before. ZIP CRC/AES authentication remain the codec's responsibility and are not disabled.
- ZIP reading uses a 128 KiB Zip4j buffer. ZIP/TAR transfer buffers are reused across entries, rather than allocated for every member. Output buffers are 128 KiB, bounded per active stream.
- ZIP/RAR member viewing uses a throttled free-space watcher, not a filesystem query for every chunk. The bounded sink alone reports progress; ZIP viewing no longer counts each byte twice.
- ZIP/RAR extraction shares a free-space watcher across entries, avoiding repeated filesystem queries for every tiny member. Space accounting now charges the first chunk correctly, checks chunks larger than the polling window, and allows small writes near the reserve without demanding another whole 32 MiB.
- New ZIP archives use FAST compression. New 7z archives use level 3 and non-solid mode. New TAR.GZ archives use gzip level 1. This intentionally trades some compression ratio for creation speed; output may be larger. Existing archives, including solid 7z archives, remain readable through the existing engines. New non-solid 7z archives do not force unrelated files into one solid compression block.
- AES-256 ZIP, 7z password/header encryption, KDF settings, CRC/MAC verification, path checks, entry/depth limits, ratio guards, RAR dictionary limits, and cancellation are retained. Password-derived keys are not cached or logged. Existing strong KDFs and solid archive layout still impose unavoidable decryption/decompression costs.
- Native 7z callbacks retain cancellation/storage exceptions, close streams on error, preflight metadata for encryption before writing, and leave root cleanup to FileRepository. Previously the engine deleted the root and the repository tried to delete it again, potentially masking the original error. A damaged unencrypted 7z no longer automatically becomes a password prompt.
- TAR creation checks duplicate paths and changing source sizes; extraction rejects links and special files. Extraction drains the bounded gzip tail to force trailer CRC/size verification.

## Validation actually performed

- Inspected archive engines, repository cleanup, password retry flow, build workflow, and existing tests.
- Ran 15 targeted source assertions locally: passed. These are structural assertions, NOT a Kotlin compiler, JVM test run, Android lint, APK build, or device benchmark.
- Added `StreamingRegressionTest` with 16 JVM tests: copy/hash equivalence, limits, cancellation, zero-length reads, space-query accounting, AES-256 ZIP round-trip/member viewing, many small ZIP entries, TAR round-trips, collisions, source truncation, trailer corruption, links/special files/traversal.
- Existing ZIP/RAR tests are retained unchanged. New JVM tests do not execute Android-native 7z.
- Local build was blocked: sandbox could not resolve github.com for cloning/dependency downloads, and no Kotlin compiler, Gradle distribution, or Android SDK was present. Files were edited locally from GitHub source and pushed through the authenticated GitHub connection.
- The existing `.github/workflows/android.yml` is configured to run on non-documentation pushes to main: debug APK, JVM tests, lint, optimized release APK. A successful push does not prove the workflow ran or passed. Check the specific commit's run before installing.

## Build and device checklist

With JDK 17 and Android SDK 35 installed:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug assembleRelease --no-daemon --stacktrace
```

On an Android test device with disposable files:

1. Use the optimized release APK for comparable timing. Compare old and new on the same phone, volume, power/thermal state, and archive corpus; record elapsed time, output bytes, and hashes. Do not compare debug to release.
2. ZIP: plain, ZipCrypto, AES-256; correct/missing/wrong passwords; single large incompressible file and thousands of tiny files. Verify byte equality and no doubled progress during member viewing.
3. RAR4/RAR5: plain/data-encrypted/header-encrypted fixtures supported by Junrar; solid archives; wrong passwords; cancellation; unsupported dictionary/split/link cases must still fail safely. This patch does not expand Junrar's encryption support.
4. 7z on Android (JNI cannot be validated by these JVM tests): plain/data-encrypted/header-encrypted, existing solid archives, new non-solid archives, correct/missing/wrong passwords, mixed encrypted/plain members, corrupt headers/CRC, cancellation during open/read/write, and low storage. Check there is no descriptor leak or secondary root-cleanup error. Ambiguous encrypted-data/header failures report password-or-corruption instead of asserting a password is definitely wrong.
5. TAR.GZ: directories, empty/Unicode files, large data, truncated input/trailer, corrupted gzip CRC, links/special files, and traversal. TAR.GZ has no standard password encryption; use ZIP/7z instead.
6. Confirm no partial results are presented as successful; repository cleanup removes failed output and retains the original archive. Verify existing destination backups/collision prompts are unchanged.
7. Cancellation during a native codec/KDF step can only be observed at the next callback; this patch does not promise instantaneous cancellation or device-independent speedups.

CI APK artifacts expire according to the existing workflow retention policy. Its release signing falls back to a runner-generated debug key when release secrets are absent; use an established signing key for upgrades. Do not uninstall an existing app just to work around a signing mismatch without backing up required app data.
