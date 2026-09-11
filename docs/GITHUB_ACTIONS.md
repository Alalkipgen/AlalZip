# Build Alal Zip with GitHub Actions from an Android phone

## Current status

Workflow prepared, **not uploaded or run**. No repository was created. Local build attempted on Android ARM64/Bionic and exited before Gradle started because Java is missing. No SDK/AAPT2 was found, so native Android build-tool execution cannot be verified locally. Ordinary project errors cannot be ruled out without compilation; GitHub does not automatically fix them.

The existing project uses AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, JDK 17 and Android SDK/build tools 35/35.0.0. CI uses Ubuntu 24.04 x64, checks AAPT2 execution and the official wrapper checksum, builds, runs JVM tests/lint, verifies APK signing and uploads only the actual APK. Maintained action releases were checked against upstream release APIs on 2026-09-10: checkout 7.0.1, setup-java 6.0.1, setup-android 4.0.1, upload-artifact 7.0.1.

## Upload (only when you choose to authorize it)

1. Export `/data/user/0/com.vscodroid/files/projects/Alal-Zip-Source.zip` using VSCodroid's available Share/Export/Save As feature to Android Downloads. Do not assume other apps can read VSCodroid private storage.
2. Extract the ZIP. Its top folder is still `ArchivePocket` intentionally. Upload the **contents** of that folder, not the ZIP and not an extra enclosing directory. Repository root must contain the Gradle wrapper, settings and app directory.
3. Sign in to github.com in your phone browser (Desktop site mode helps). Create your own repository only if you agree to upload the code; choose visibility deliberately. Do not add credentials, signing keys or personal files. GitHub does not automatically unpack uploaded ZIPs.
4. Use Add file → Upload files to upload the extracted files while retaining subdirectories. Android browser pickers sometimes flatten folders or omit dotfiles. If folder-preserving upload is unavailable, use VSCodroid's Git/Source Control support or a trusted Git client to commit/push the extracted tree after connecting your own account. Never put a token in source, a command URL or a screenshot.
5. Ensure the repository includes `.github/workflows/android.yml`, `.gitignore`, both wrapper scripts, wrapper JAR/properties, all source/resources (including icons), licenses, tests and tools. If the UI omitted the hidden workflow directory, use Add file → Create new file with repository-relative name `.github/workflows/android.yml`, then paste the supplied workflow exactly. The JAR must be uploaded as a binary, not pasted as text.
6. Commit to the default branch. A manual workflow must exist on the default branch to expose the Run workflow button. No push/pull-request trigger is configured.

## Run and download

1. Before running, review GitHub Actions usage/billing for your account and Android SDK license terms. The workflow downloads dependencies and accepts SDK licenses **on the hosted runner when you manually run it**. No paid service was activated by this project; account quotas/charges depend on your settings.
2. Repository → Actions → Alal Zip APK (debug + optimized release) → Run workflow → choose default branch → Run workflow. This is your explicit remote-build action.
3. Open the run. A red result requires inspecting the failing step's actual logs and correcting the error. There is no promised successful build yet.
4. After a successful run, scroll to Artifacts → Alal-Zip-debug. While signed in, download the artifact ZIP, normally to `/storage/emulated/0/Download/Alal-Zip-debug.zip`; browser naming may vary. Extract it to obtain `/storage/emulated/0/Download/Alal-Zip-debug.apk` (or your chosen extraction folder). Artifacts expire after 14 days.
5. This is a debug APK, not a production-signed release. A new ephemeral runner may generate a different debug signing key between builds, preventing an in-place update of a prior installation. Do not uninstall an existing app with valuable data merely to bypass signature errors; plan signing/backup first.
6. Installation and on-device functionality remain untested. Run the disposable manual checklist before real data. No APK is inside the source ZIP.

## Local development remains supported

Use Android Studio/JDK 17 on a compatible desktop. Configure the SDK with untracked `local.properties` or `ANDROID_HOME`. Any phone-specific `android.aapt2FromMavenOverride` belongs in your untracked user Gradle properties, not committed project configuration. No local tool paths or overrides are needed by CI. The application ID remains `app.archivepocket`.
## Optimized release APK

The same run also uploads `Alal-Zip-release` (`Alal-Zip-release.apk`): built with `assembleRelease`, R8 minified and resource-shrunk, so Compose scrolling and animations are far smoother than the debug APK. Install this one for daily use.

Signing: without secrets the release APK is signed with the runner's debug key (a new key per run, like the debug APK). To keep one stable key so later builds can update in place, add repository secrets `ALAL_KEYSTORE_BASE64` (base64 of a `.jks` made with `keytool -genkeypair -v -keystore alal.jks -alias alal -keyalg RSA -keysize 2048 -validity 10000`), `ALAL_KEYSTORE_PASSWORD`, `ALAL_KEY_ALIAS` and `ALAL_KEY_PASSWORD`. When switching from the debug APK to the release APK (or between keys) Android refuses the install until the previous installation is uninstalled; Alal Zip keeps no data outside phone storage, so nothing is lost.
