# Alal Zip — build and export

The existing ArchivePocket project was renamed in place; application ID remains `app.archivepocket`. No archive/file feature was removed. Updated source: `/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip`. The old source archive is retained separately.

## GitHub alternative (prepared, not run)

Manual-only workflow: `/data/user/0/com.vscodroid/files/projects/ArchivePocket/.github/workflows/android.yml`.
Phone instructions: `/data/user/0/com.vscodroid/files/projects/ArchivePocket/docs/GITHUB_ACTIONS.md`.
No code has been uploaded and no remote build has been triggered. CI checks AAPT2, assembles debug, runs tests/lint, checks the APK signature and uploads `Alal-Zip-debug` only if those steps succeed. SDK licenses are accepted on the hosted runner only when you choose to run it.

Latest local recheck: about 5.8 GB free; Java, javac, Gradle, sdkmanager, AAPT2 and adb absent from PATH and inspected locations. `bash /data/user/0/com.vscodroid/files/projects/ArchivePocket/gradlew assembleDebug --no-daemon` failed with the same missing-Java error below. Native Android build tools could not be executed because none were found. No toolchain or system configuration change was made.

## Actual local environment (2026-09-10)

- Android API 36, Linux Android/Bionic, aarch64/arm64-v8a.
- JDK `java` and `javac`: command not found; JAVA_HOME unset.
- SDK, sdkmanager, aapt2, adb, installed Gradle/cache: not found in inspected locations.
- Approximately 2.2 GB free disk initially; 5.9 GB free (95% used) at final inspection. Initially 2.5 GiB available RAM. More free space does not resolve the missing toolchain.
- Python 3.14.6, ZIP/zlib, Git, Bash available.
- Official Gradle wrapper 8.10.2 scripts/JAR downloaded (small files only). Wrapper SHA-256 verified against Gradle's published checksum. Distribution ZIP was NOT downloaded.

Command executed from `/data/user/0/com.vscodroid/files/projects/ArchivePocket`:

```sh
bash /data/user/0/com.vscodroid/files/projects/ArchivePocket/gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Actual exit code: **1**. Actual output:

```text
ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation.
```

Direct execution of the upstream wrapper first produced `/bin/sh: bad interpreter: Permission denied`; invoking it with Bash bypasses that Android shebang issue but cannot supply the missing JDK.

**APK building is blocked on this phone as configured.** Even installing Java is not enough: ordinary Linux x86-64 AAPT2 cannot execute on Android ARM64/Bionic. A supported phone toolchain would need compatible JDK, SDK and AAPT2/other native tools, sufficient storage, and validation. No large downloads, remote builds, uploads or SDK license acceptances were performed.

## Practical alternative: local desktop Android Studio

1. Transfer `/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip` yourself to a desktop. No upload service is necessary. The ZIP's top directory remains ArchivePocket.
2. Extract to an absolute directory, for example `/home/developer/ArchivePocket` (macOS: `/Users/developer/ArchivePocket`, Windows: `C:\Projects\ArchivePocket`). These are example destinations, not existing files on the phone.
3. Install/use Android Studio with **JDK 17**, Android SDK Platform **35** and Build Tools **35.0.0**. Accept SDK licenses yourself. Allow several GB for SDK/dependencies; first sync needs internet to download dependencies. The resulting app runs offline.
4. Open that extracted project, select JDK 17 for Gradle, and let Studio create the machine-specific `local.properties`. Do not include that file in source delivery.
5. On Linux, from the extracted project root:

```sh
cd /home/developer/ArchivePocket
bash /home/developer/ArchivePocket/gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Windows PowerShell:

```powershell
Set-Location C:\Projects\ArchivePocket
& C:\Projects\ArchivePocket\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

6. Fix any actual compile/lint/runtime failures before trusting the app; this source has not been compiled here. Expected Linux build output **only after success**: `/home/developer/ArchivePocket/app/build/outputs/apk/debug/app-debug.apk`. It is debug-signed locally with the developer machine's generated debug key. Do not distribute the key.
7. Install using Android Studio or manually open the APK on the phone and allow installation from your chosen source. Run the disposable checks in MANUAL_TESTS.md. Minimum Android: API 26 (Android 8).

Versions are pinned: AGP 8.7.3, Gradle 8.10.2, Kotlin/Compose plugin 2.0.21, Compose BOM 2024.12.01. No native archive JNI ABI dependency is required by the app; Junrar's Java crypto/runtime must still be tested on devices.

## Export from VSCodroid to Downloads

Source output: `/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip`.

App-private files are not directly readable by ordinary file-manager apps. In VSCodroid Explorer, locate `/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip` and use **Share/Export/Save As** if your installed version exposes such an action; choose Android's Files/Save to device and **Downloads**. Exact menu availability depends on VSCodroid version. If no export/share action is available, use VSCodroid's documented project/file export feature; do not assume another file manager can open its private directory.

If VSCodroid already has shared-storage access, this optional Python command copies without overwriting an existing Downloads file:

```sh
python3 - <<'PY'
from pathlib import Path
import shutil
source = Path('/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip')
destination = Path('/storage/emulated/0/Download/Alal-Zip-Source.zip')
with source.open('rb') as input_file, destination.open('xb') as output_file:
    shutil.copyfileobj(input_file, output_file)
print(destination)
PY
```

This export command was **not run**. `PermissionError` means direct shared-storage writing is not permitted; use the Android picker/export flow instead. `FileExistsError` means a file already exists: choose another name, do not delete it automatically. Conventional Downloads path: `/storage/emulated/0/Download/Alal-Zip-Source.zip`.