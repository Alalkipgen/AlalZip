#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[1]

def edit(path, old, new):
    p = root / path
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing marker in {path}: {old[:80]!r}')
    p.write_text(text.replace(old, new, 1))

edit('settings.gradle.kts',
'''    repositories { google(); mavenCentral() }
''',
'''    repositories { google(); mavenCentral(); maven { url = uri("https://jitpack.io") } }
''')

edit('app/build.gradle.kts',
'''    implementation("com.github.junrar:junrar:8.1.1")
''',
'''    implementation("com.github.junrar:junrar:8.1.1")
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    implementation("org.apache.commons:commons-compress:1.27.1")
''')

engine = 'app/src/main/java/app/archivepocket/core/ArchiveEngine.kt'
edit(engine,
'''data class ArchiveSource(val path: String, val directory: Boolean, val open: () -> InputStream)
''',
'''data class ArchiveSource(val path: String, val directory: Boolean, val size: Long = 0, val open: () -> InputStream)
''')
edit(engine,
'''    fun createZip(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
''',
'''    fun create7z(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) =
        SevenZipSupport.create(sources, output, password, control)

    fun createTarGz(sources: List<ArchiveSource>, output: File, control: OperationControl) =
        TarGzSupport.create(sources, output, control)

    fun createZip(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
''')
edit(engine,
'''            signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> extractZip(input, destination, password, control)
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
''',
'''            signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> extractZip(input, destination, password, control)
            count >= 6 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) ->
                SevenZipSupport.extract(input, destination, password, control)
            signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte() -> {
                if (hasPassword(password)) throw PocketError("TAR.GZ does not support passwords. Use 7z for encrypted archives.")
                TarGzSupport.extract(input, destination, control)
            }
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
''')
edit(engine,
'''            else -> throw PocketError("Not a supported ZIP or RAR4/RAR5 archive (or damaged header).")
''',
'''            else -> throw PocketError("Not a supported ZIP, RAR4/RAR5, 7z or TAR.GZ archive (or damaged header).")
''')

repo = 'app/src/main/java/app/archivepocket/data/FileRepository.kt'
edit(repo,
'''        val sources = planned.map { p -> ArchiveSource(p.path, p.entry.directory) { open(p.entry.file) } }
        val output = File(work, "created.zip")
        ArchiveEngine.createZip(sources, output, password, control)
''',
'''        val sources = planned.map { p -> ArchiveSource(p.path, p.entry.directory, p.entry.size) { open(p.entry.file) } }
        val lower = name.lowercase(Locale.ROOT)
        val output = File(work, when { lower.endsWith(".7z") -> "created.7z"; lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "created.tar.gz"; else -> "created.zip" })
        when {
            lower.endsWith(".7z") -> ArchiveEngine.create7z(sources, output, password, control)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                if (password != null && password.isNotEmpty()) throw PocketError("TAR.GZ does not support passwords. Use 7z for encryption.")
                ArchiveEngine.createTarGz(sources, output, control)
            }
            else -> ArchiveEngine.createZip(sources, output, password, control)
        }
''')

vm = 'app/src/main/java/app/archivepocket/PocketViewModel.kt'
edit(vm,
'''        execute("Create ZIP", password, noticeTarget = destination, expectedBytes = { entries.sumOf { entry -> if (entry.directory) 0L else entry.size } }) {
''',
'''        val kind = when { name.endsWith(".7z", true) -> "7z"; name.endsWith(".tar.gz", true) || name.endsWith(".tgz", true) -> "TAR.GZ"; else -> "ZIP" }
        execute("Create $kind", password, noticeTarget = destination, expectedBytes = { entries.sumOf { entry -> if (entry.directory) 0L else entry.size } }) {
''')

ui = 'app/src/main/java/app/archivepocket/MainActivity.kt'
edit(ui,
'''    var name by remember { mutableStateOf(initialName) }
    // Deliberately not rememberSaveable: passwords must never enter saved instance state.
''',
'''    var name by remember { mutableStateOf(initialName) }
    var format by remember { mutableStateOf("ZIP") }
    // Deliberately not rememberSaveable: passwords must never enter saved instance state.
''')
edit(ui,
'''    val mismatch = create && repeat.isNotEmpty() && password != repeat
    val valid = name.isNotBlank() && (!create || password == repeat)
''',
'''    val encryptedFormat = format != "TAR.GZ"
    val mismatch = create && encryptedFormat && repeat.isNotEmpty() && password != repeat
    val valid = name.isNotBlank() && (!create || !encryptedFormat || password == repeat)
''')
edit(ui,
'''            val chars = if (create) password.takeIf { it.isNotEmpty() }?.toCharArray() else null
            password = ""; repeat = ""
            submit(if (create && !name.endsWith(".zip", true)) "$name.zip" else name, chars, destination)
''',
'''            val chars = if (create && encryptedFormat) password.takeIf { it.isNotEmpty() }?.toCharArray() else null
            val suffix = when (format) { "7Z" -> ".7z"; "TAR.GZ" -> ".tar.gz"; else -> ".zip" }
            val finalName = if (!create || name.endsWith(suffix, true) || (format == "TAR.GZ" && name.endsWith(".tgz", true))) name else name.substringBeforeLast('.', name) + suffix
            password = ""; repeat = ""
            submit(finalName, chars, destination)
''')
edit(ui,
'''        DestinationRow(destination, root) { picking = true }
        OutlinedTextField(
''',
'''        DestinationRow(destination, root) { picking = true }
        if (create) {
            Text("Archive format", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ZIP", "7Z", "TAR.GZ").forEach { option ->
                    FilterChip(selected = format == option, onClick = {
                        format = option
                        if (option == "TAR.GZ") { password = ""; repeat = "" }
                    }, label = { Text(option) })
                }
            }
        }
        OutlinedTextField(
''')
edit(ui,
'''            label = { Text(if (create) "ZIP file name" else "Output folder name") },
''',
'''            label = { Text(if (create) "$format file name" else "Output folder name") },
''')
edit(ui,
'''        if (create) {
            PasswordField(password, { password = it }, "Password (optional)")
            PasswordField(repeat, { repeat = it }, "Repeat password", isError = mismatch,
                supporting = if (mismatch) "Passwords do not match." else null)
        }
''',
'''        if (create && encryptedFormat) {
            PasswordField(password, { password = it }, "Password (optional)")
            PasswordField(repeat, { repeat = it }, "Repeat password", isError = mismatch,
                supporting = if (mismatch) "Passwords do not match." else null)
        } else if (create) {
            Text("TAR.GZ has no standard password encryption. Choose 7Z for password protection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
''')
edit(ui,
'''            summary = if (create) "The ZIP is created in the destination folder."
''',
'''            summary = if (create) "The $format archive is created in the destination folder."
''')
edit(ui,
'''            details = if (create) "A non-empty password enables AES-256. File names are not hidden, and forgotten passwords cannot be recovered."
''',
'''            details = if (create && encryptedFormat) "A non-empty password enables AES-256. 7z also encrypts file names; forgotten passwords cannot be recovered."
                else if (create) "TAR.GZ is a standard unencrypted tar archive compressed with gzip."
''')

proguard = root / 'app/proguard-rules.pro'
with proguard.open('a') as f:
    f.write('\n# 7-Zip-JBinding uses JNI and reflection to connect callback interfaces.\n-keep class net.sf.sevenzipjbinding.** { *; }\n')
