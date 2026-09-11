package app.archivepocket.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import app.archivepocket.core.ArchiveEngine
import app.archivepocket.core.ArchiveSource
import app.archivepocket.core.ArchivePreview
import app.archivepocket.core.OperationControl
import app.archivepocket.core.PocketError
import app.archivepocket.core.Safety
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class Entry(val file: File, val name: String, val directory: Boolean, val size: Long, val modified: Long) {
    val path: String get() = file.path
}

/**
 * Direct shared-storage access (java.io.File), like a classic file manager.
 * Requires "All files access" on Android 11+ or the legacy storage permission on Android 8-10.
 * All methods that touch storage must run on Dispatchers.IO.
 */
class FileRepository(context: Context) {
    private val tempRoot = File(context.cacheDir, "archivepocket-operations")
    private val shareRoot = File(context.cacheDir, "shared-preview")
    val root: File = Environment.getExternalStorageDirectory()

    /** Free and total bytes of the shared storage volume. */
    fun storage(): Pair<Long, Long> = try {
        val stats = StatFs(root.path)
        stats.availableBytes to stats.totalBytes
    } catch (_: Exception) { 0L to 0L }

    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    private fun find(parent: File, name: String): File? = parent.listFiles()?.firstOrNull { key(it.name) == key(name) }
    private fun canonical(file: File): String = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
    private fun inside(parent: File, child: File): Boolean {
        val p = canonical(parent); val c = canonical(child)
        return c == p || c.startsWith(p + File.separator)
    }
    private fun isLink(file: File): Boolean = try { Files.isSymbolicLink(file.toPath()) } catch (_: Exception) { false }

    fun entry(file: File): Entry = Entry(file, file.name, file.isDirectory, if (file.isDirectory) 0 else file.length(), file.lastModified())

    fun list(dir: File): List<Entry> {
        if (!dir.isDirectory) throw PocketError("Folder is not available.")
        val children = dir.listFiles() ?: throw PocketError("Cannot read this folder. Check that storage access (All files access) is allowed.")
        return children.map(::entry)
    }
    fun createFolder(parent: File, name: String) {
        Safety.name(name)
        if (find(parent, name) != null) throw PocketError("That name already exists.")
        if (!File(parent, name).mkdir()) throw PocketError("Cannot create a folder here.")
    }
    fun rename(entry: Entry, parent: File, name: String) {
        Safety.name(name)
        if (entry.name == name) return
        if (find(parent, name) != null) throw PocketError("That name already exists; rename will not overwrite.")
        if (!entry.file.renameTo(File(parent, name))) throw PocketError("Rename failed.")
    }
    fun delete(entries: List<Entry>, control: OperationControl) {
        for (entry in entries) { control.check(); deleteTree(entry.file, control) }
    }
    private fun deleteTree(file: File, control: OperationControl) {
        control.check()
        // Never follow symbolic links while deleting recursively.
        if (file.isDirectory && !isLink(file)) file.listFiles()?.forEach { deleteTree(it, control) }
        if (!file.delete()) throw PocketError("Cannot delete \u201c${file.name}\u201d. Earlier selected items may already be deleted.")
    }

    private fun open(file: File): InputStream = file.inputStream()
    private fun workspace(): File {
        if (!tempRoot.isDirectory && !tempRoot.mkdirs()) throw PocketError("Cannot create private temporary storage.")
        Safety.space(tempRoot)
        return File(tempRoot, "op-${UUID.randomUUID()}").also { if (!it.mkdir()) throw PocketError("Cannot create workspace.") }
    }
    fun cleanAbandoned() {
        // Called only when the ViewModel is constructed, before operations can start.
        tempRoot.listFiles()?.filter { it.name.startsWith("op-") }?.forEach {
            if (!it.deleteRecursively()) throw PocketError("Cannot clean previous private temporary files. Clear app cache from Android Settings.")
        }
        shareRoot.listFiles()?.forEach { it.deleteRecursively() }
    }
    private fun <T> temporary(block: (File) -> T): T {
        val work = workspace()
        try { return block(work) } finally {
            if (!work.deleteRecursively()) throw PocketError("Temporary cleanup failed. Clear Alal Zip cache in Android Settings; completed destination files may remain.")
        }
    }

    private fun deleteVerifiedSource(source: Entry, copied: File, control: OperationControl) {
        control.check()
        val current = source.file
        if (current.length() != source.size || current.lastModified() != source.modified) {
            throw PocketError("Source changed after copying; source retained.")
        }
        val sourceHash = open(current).use { Safety.digest(it, control) }
        val destinationHash = open(copied).use { Safety.digest(it, control) }
        if (!sourceHash.contentEquals(destinationHash)) throw PocketError("Source changed or destination verification failed; source retained.")
        control.check()
        if (!current.delete()) throw PocketError("Copy verified, but source deletion failed. Both copies remain.")
    }

    /** New files only. Existing user files are never opened for writing. */
    private fun writeVerified(parent: File, name: String, control: OperationControl, source: () -> InputStream): File {
        Safety.name(name)
        if (find(parent, name) != null) throw PocketError("Destination collision; nothing overwritten.")
        val created = File(parent, name)
        try {
            val expected = source().use { input ->
                created.outputStream().use { out -> Safety.transfer(input, out, control, name, spaceRoot = parent) }
            }
            val actual = open(created).use { Safety.digest(it, control) }
            if (!expected.contentEquals(actual)) throw PocketError("Copy verification failed; source retained.")
            control.check()
            return created
        } catch (error: Exception) {
            if (created.exists() && !created.delete()) throw PocketError("Write failed and partial destination could not be removed. Inspect destination. Source retained.")
            throw error
        }
    }

    /** Replacement is opt-in per collision. Old data is retained with a backup name. */
    private fun makeRoom(parent: File, name: String, confirm: (String) -> Boolean) {
        val existing = find(parent, name) ?: return
        if (!confirm(name)) throw PocketError("Collision cancelled. Existing data was not replaced.")
        val backup = "AP-backup-${UUID.randomUUID().toString().take(8)}-${name.take(150)}"
        if (find(parent, backup) != null || !existing.renameTo(File(parent, backup))) throw PocketError("Cannot preserve existing item as backup; replacement aborted.")
    }

    private data class Planned(val entry: Entry, val path: String)
    private fun plan(entries: List<Entry>, control: OperationControl): List<Planned> {
        val result = ArrayList<Planned>()
        val visited = HashSet<String>()
        fun walk(entry: Entry, path: String, depth: Int) {
            control.check(); Safety.relative(path)
            if (depth > Safety.MAX_DEPTH || result.size >= Safety.MAX_ENTRIES || !visited.add(canonical(entry.file))) throw PocketError("Folder is too deep, too large, or cyclic.")
            result.add(Planned(entry, path))
            if (entry.directory && !isLink(entry.file)) list(entry.file).forEach { walk(it, "$path/${it.name}", depth + 1) }
        }
        entries.forEach { walk(it, it.name, 1) }
        return result
    }

    fun copy(entries: List<Entry>, destination: File, move: Boolean, control: OperationControl, confirm: (String) -> Boolean) {
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        for (source in entries) {
            control.check()
            if (source.directory && inside(source.file, destination)) throw PocketError("Destination is inside the source folder.")
            if (canonical(source.file.parentFile ?: source.file) == canonical(destination)) throw PocketError("Source and destination are the same folder.")
            // Snapshot before destination creation, preventing traversal of newly created output.
            val planned = plan(listOf(source), control)
            makeRoom(destination, source.name, confirm)
            val target = File(destination, source.name)
            if (move && !target.exists() && source.file.renameTo(target)) continue // same volume: atomic rename
            if (!source.directory) {
                val copied = writeVerified(destination, source.name, control) { open(source.file) }
                if (move) deleteVerifiedSource(source, copied, control)
            } else {
                if (!target.mkdir()) throw PocketError("Cannot create destination folder.")
                val directories = hashMapOf(source.name to target)
                val copiedFiles = hashMapOf<String, File>()
                try {
                    for (item in planned.drop(1)) {
                        control.check()
                        val itemParent = directories[item.path.substringBeforeLast('/')] ?: throw PocketError("Invalid folder plan.")
                        if (item.entry.directory) {
                            val dir = File(itemParent, item.entry.name)
                            if (!dir.mkdir()) throw PocketError("Cannot create child folder.")
                            directories[item.path] = dir
                        } else copiedFiles[item.entry.path] = writeVerified(itemParent, item.entry.name, control) { open(item.entry.file) }
                    }
                } catch (error: Exception) {
                    if (runCatching { deleteTree(target, OperationControl()) }.isFailure) throw PocketError("Copy failed; partial destination folder remains. Sources retained.")
                    throw error
                }
                if (move) {
                    // Delete only individually verified files. Never recursively delete the source
                    // folder: another application might have added new/unverified children.
                    for (item in planned.filter { !it.entry.directory }) {
                        control.check()
                        deleteVerifiedSource(item.entry, copiedFiles.getValue(item.entry.path), control)
                    }
                    // Remove now-empty source folders, deepest first; folders with new content remain.
                    planned.filter { it.entry.directory }.asReversed().forEach { it.entry.file.delete() }
                }
            }
        }
    }

    fun zip(entries: List<Entry>, destination: File, name: String, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) = temporary { work ->
        Safety.name(name)
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        val planned = plan(entries, control)
        val sources = planned.map { p -> ArchiveSource(p.path, p.entry.directory) { open(p.entry.file) } }
        val output = File(work, "created.zip")
        ArchiveEngine.createZip(sources, output, password, control)
        makeRoom(destination, name, confirm)
        writeVerified(destination, name, control) { output.inputStream() }
        Unit
    }

    /** Metadata-only archive listing for in-app archive browsing; does not extract files. */
    fun preview(source: Entry): ArchivePreview {
        if (source.directory) throw PocketError("Select an archive file, not a folder.")
        return ArchiveEngine.preview(source.file)
    }

    /** Opens just one archive member through a private FileProvider cache; the full archive is never unpacked. */
    fun openArchiveItem(source: Entry, itemPath: String, password: CharArray?, control: OperationControl): File {
        if (source.directory) throw PocketError("Archive source is not a file.")
        if (!shareRoot.isDirectory && !shareRoot.mkdirs()) throw PocketError("Cannot create private viewing cache.")
        shareRoot.listFiles()?.forEach { it.deleteRecursively() }
        val session = File(shareRoot, UUID.randomUUID().toString()).also {
            if (!it.mkdir()) throw PocketError("Cannot create private viewing session.")
        }
        val name = Safety.name(itemPath.replace('\\', '/').substringAfterLast('/'))
        val output = File(session, name)
        try {
            ArchiveEngine.openItem(source.file, itemPath, output, password, control)
            return output
        } catch (error: Exception) {
            session.deleteRecursively()
            throw error
        }
    }

    /** "Extract here": unpacks into a private workspace first, then moves the results into [destination] with collision checks. */
    fun extractHere(source: Entry, destination: File, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) = temporary { work ->
        if (source.directory) throw PocketError("Select an archive file, not a folder.")
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        val out = File(work, "extract")
        if (!out.mkdir()) throw PocketError("Cannot create temporary extraction folder.")
        ArchiveEngine.extract(source.file, out, password, control)
        control.check()
        val produced = list(out)
        if (produced.isEmpty()) throw PocketError("The archive is empty.")
        copy(produced, destination, move = true, control = control, confirm = confirm)
    }

    fun extract(source: Entry, destination: File, folderName: String, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) {
        Safety.name(folderName)
        if (source.directory) throw PocketError("Select an archive file, not a folder.")
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        makeRoom(destination, folderName, confirm)
        val root = File(destination, folderName)
        if (!root.mkdir()) throw PocketError("Cannot create extraction destination.")
        try {
            ArchiveEngine.extract(source.file, root, password, control)
            control.check()
        } catch (error: Exception) {
            if (runCatching { deleteTree(root, OperationControl()) }.isFailure) throw PocketError("Extraction failed; partial destination remains. Original archive retained.")
            throw error
        }
    }
}
