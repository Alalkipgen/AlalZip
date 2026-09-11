# Third-party notices

Verified 2026-09-10 against tagged upstream source, published Maven POMs and license texts. Libraries are resolved by Gradle; their runtime JARs are not vendored in this source package. Original ArchivePocket code is MIT licensed separately.

## Archive libraries

### Zip4j 2.11.6 — Apache License 2.0

- Coordinates: `net.lingala.zip4j:zip4j:2.11.6`.
- Copyright Srikanth Reddy Lingala; retain `licenses/zip4j-NOTICE.txt` and `licenses/zip4j-LICENSE.txt`.
- https://github.com/srikanth-lingala/zip4j/tree/v2.11.6
- Release API returned v2.11.6, published 2026-02-12. Published artifact exists on Maven Central; actual JAR inspected: 213,881 bytes, Java class version 52 (Java 8), ZIP integrity valid.
- Upstream advertises Android build testing, streaming ZIP, Unicode, ZipCrypto and AES encryption. Application uses AES-256 creation and per-entry input streams for verified extraction. Source APIs were inspected, not guessed.

### Junrar 8.1.1 — UnRAR license (not Apache/MIT)

- Coordinates: `com.github.junrar:junrar:8.1.1`.
- https://github.com/junrar/junrar/tree/v8.1.1
- Release API returned v8.1.1, published 2026-08-31. Changelog includes recent header-bounds fixes. Published Maven artifact exists; actual JAR inspected: 246,811 bytes, Java class version 52 (Java 8), ZIP integrity valid.
- Complete terms: `licenses/junrar-LICENSE.txt`. RAR/UnRAR copyrights belong to Alexander Roshal. Redistribution/extraction are permitted under those terms, but **this code may not be used to develop a RAR (WinRAR) compatible archiver or re-create the proprietary RAR compression algorithm**. ArchivePocket uses it only for extraction; ZIP creation uses Zip4j.
- The restriction means this dependency is not an unrestricted permissive open-source license. Review the full terms before redistribution or modification.
- Six tiny upstream RAR fixtures are included under upstream terms; URLs, sizes and SHA-256 are in the fixture provenance JSON. They contain disposable public test data. The public fixture password is `junrar`, not a user secret.

### Explicit format verification

| Format | Upstream 8.1.1 evidence | ArchivePocket validation |
|---|---|---|
| RAR4 / legacy RAR3-format container | `Archive`, legacy unpack implementation, RAR4 test fixtures | Extraction code + tests supplied; not executed |
| RAR5 container | `readHeadersRar5`, `extractRar5`, `FileHeader.isRar5Container`, plain RAR5 fixtures | Extraction code + tests supplied; not executed |
| Encrypted RAR4 content and headers | `ArchiveOptions` char-array password API; upstream encrypted fixtures/tests | Correct/wrong-password tests supplied; not executed |
| Encrypted RAR5 content and headers | `Rar5Crypt`, encrypted header handling and `ArchiveRar5CryptoTest` | Correct/wrong-password tests supplied; not executed |
| Multipart | Upstream supports volumes | Intentionally rejected by app |
| RAR creation | Not implemented or licensed for this use | Not provided |

Junrar's new version supports more formats than historical Junrar 7.x. Do not infer RAR5 support from old releases. Android compatibility is assessed from Java 8 bytecode and API imports (java.io/nio/time/util, javax.crypto), API 26 minimum and desugaring; actual Android runtime compatibility remains **unverified**.

## Android / Kotlin runtime dependencies

- AndroidX Compose UI/Foundation/Material3 (BOM 2024.12.01), Activity 1.9.3, Lifecycle 2.8.7, DocumentFile 1.0.1 and transitive AndroidX components: Apache-2.0, copyright The Android Open Source Project. https://android.googlesource.com/platform/frameworks/support/
- Kotlin standard library/plugin 2.0.21: Apache-2.0, JetBrains and contributors. Full text: `licenses/kotlin-LICENSE.txt`.
- kotlinx.coroutines 1.9.0: Apache-2.0; full upstream text `licenses/coroutines-LICENSE.txt`.
- SLF4J API and no-operation provider 2.0.17: MIT; `licenses/slf4j-LICENSE.txt`. No-op provider intentionally avoids archive-library logging.
- desugar_jdk_libs 2.1.4: Google/OpenJDK components with GPLv2 + Classpath Exception and additional component notices. The included `licenses/desugar-LICENSE.txt` was retrieved from upstream master on 2026-09-10 because the attempted `2.1.4` Git tag URL returned 404; it is not a verified release-specific notice inventory. See https://github.com/google/desugar_jdk_libs and audit the resolved artifact's notices before APK redistribution. This does not relicense original app code under GPL.

## Build/test tooling

- Gradle wrapper 8.10.2: Apache-2.0 with bundled notices; `licenses/gradle-LICENSE.txt`. Official wrapper SHA-256: `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.
- Android Gradle plugin 8.7.3: Apache-2.0. Kotlin Compose plugin 2.0.21: Apache-2.0.
- JUnit 4.13.2: EPL-1.0; full text `licenses/junit-LICENSE.txt`. Test-only Hamcrest 1.3: BSD-3-Clause, included notice `licenses/hamcrest-LICENSE.txt`.
- Generic Apache-2.0 text is also available in `licenses/zip4j-LICENSE.txt`; component-specific notices are retained separately.

Full notices are copied into app assets for redistribution. This first pass has not resolved the complete Android transitive build graph; review the resolved dependency/license inventory before a production release. No paid service or external upload was used.