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
import java.nio.file.LinkOption
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
 *
 * New results are built in a staging folder inside the destination and renamed into place only once
 * complete. Each change to shared storage is first recorded in an [OperationJournal], so whatever an
 * operation leaves unfinished when the process is killed is rolled back by [recoverInterrupted].
 */
class FileRepository(
    private val cacheDir: File,
    val root: File,
    filesDir: File,
    /** Same-volume move of one item; replaceable so tests can simulate storage that refuses renames. */
    private val renameMove: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
    constructor(context: Context) : this(context.cacheDir, Environment.getExternalStorageDirectory(), context.filesDir)

    private val tempRoot = File(cacheDir, "archivepocket-operations")
    private val shareRoot = File(cacheDir, "shared-preview")
    /** Private copies of archives that other apps opened in Archive through content:// URIs. */
    private val incomingRoot = File(cacheDir, "incoming-archives")
    private val journals = File(filesDir, "operation-journals")

    /**
     * When false, copies larger than [Safety.VERIFY_LIMIT] are not re-read for a SHA-256 comparison;
     * size and modification time still have to match. Re-reading doubles the time for huge files.
     */
    var verifyLargeFiles: Boolean = false

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
    /** True for anything at [file], including a symbolic link whose target is gone. */
    private fun present(file: File): Boolean = try { Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) } catch (_: Exception) { file.exists() }
    private fun same(a: File, b: File): Boolean = a.parentFile?.path == b.parentFile?.path && key(a.name) == key(b.name)
    /** Folder path as the user sees it, relative to Internal storage. */
    private fun shown(folder: File): String {
        val base = root.path.trimEnd('/')
        return when {
            folder.path == base -> "Internal storage"
            folder.path.startsWith("$base/") -> folder.path.removePrefix("$base/")
            else -> folder.path
        }
    }
    private fun describe(names: List<String>): String = names.singleOrNull()?.let { "\u201c$it\u201d" } ?: "${names.size} items"

    fun entry(file: File): Entry = Entry(file, file.name, file.isDirectory, if (file.isDirectory) 0 else file.length(), file.lastModified())

    fun list(dir: File): List<Entry> {
        if (!dir.isDirectory) throw PocketError("Folder is not available.")
        val children = dir.listFiles() ?: throw PocketError("Cannot read this folder. Check that storage access (All files access) is allowed.")
        // Staging folders of a running operation are an implementation detail, never a browsable entry.
        return children.filterNot { it.name.startsWith(STAGING_PREFIX) }.map(::entry)
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
    /** Private caches of earlier processes; called once per process by OperationManager, before its first operation. */
    fun cleanAbandoned() {
        // Workspace of the older cache-staged operations; nothing uses it any more.
        if (tempRoot.exists() && !tempRoot.deleteRecursively()) {
            throw PocketError("Cannot clean previous private temporary files. Clear app cache from Android Settings.")
        }
        shareRoot.listFiles()?.forEach { it.deleteRecursively() }
        clearIncoming()
    }

    /**
     * Copies an archive that another app shared through a content:// URI into private cache, where it can
     * be previewed and extracted like any file. [declaredSize] is the size the other app reported, or 0
     * when unknown; an archive that cannot fit is refused before anything is copied. Only the newest copy
     * is kept, and a copy that fails or is cancelled is deleted at once.
     */
    fun importIncoming(name: String, declaredSize: Long, control: OperationControl, open: () -> InputStream?): Entry {
        // Copies that no screen previews any more go first, so they never count against the new one.
        clearIncoming()
        if (declaredSize > 0) Safety.space(cacheDir, declaredSize)
        val session = File(incomingRoot, UUID.randomUUID().toString())
        if (!session.mkdirs()) throw PocketError("Cannot create private archive cache.")
        try {
            val target = File(session, incomingName(name))
            val input = open() ?: throw PocketError("Android could not open the selected archive.")
            input.use { source ->
                target.outputStream().use { output -> Safety.copyStream(source, output, control, target.name, spaceRoot = cacheDir) }
            }
            control.check()
            if (target.length() == 0L) throw PocketError("The selected archive is empty or could not be copied.")
            return entry(target)
        } catch (error: Exception) {
            session.deleteRecursively()
            throw error
        }
    }

    /** Deletes [entry] when it is a private copy made by [importIncoming]; any other file is left alone. */
    fun discardIncoming(entry: Entry) {
        val session = entry.file.parentFile ?: return
        if (session.parentFile?.let(::canonical) == canonical(incomingRoot)) session.deleteRecursively()
    }

    private fun clearIncoming() { incomingRoot.listFiles()?.forEach { it.deleteRecursively() } }

    /** A single file name for a private copy: no path parts or control characters, short enough for any file system. */
    private fun incomingName(name: String): String {
        var clean = name.map { if (it == '/' || it == '\\' || it == ':' || it.code < 32) '_' else it }.joinToString("").trim()
        // Dropped from the front, so the extension that tells the archive type survives.
        while (clean.toByteArray().size > 200) clean = clean.substring(clean.offsetByCodePoints(0, 1))
        return if (clean.isBlank() || clean == "." || clean == "..") "Archive.zip" else clean
    }

    /**
     * Called once per process by OperationManager, before its first operation: rolls back what operations
     * of an earlier process left unfinished, using their journals only. Storage is never searched for
     * staging or backup names. Returns a summary for the user, or null when nothing was interrupted.
     */
    fun recoverInterrupted(): String? {
        val lines = ArrayList<String>()
        for (journal in OperationJournal.leftovers(journals)) {
            // A destination that cannot be read now (no storage access yet, card removed) is retried on a later start.
            if (journal.destination.listFiles() == null) continue
            val result = rollBack(journal)
            journal.file.delete()
            val notes = (result.done + result.problems).ifEmpty { listOf("nothing needed to be undone") }
            lines += "\u2022 ${journal.label}: ${notes.joinToString("; ")}."
        }
        if (lines.isEmpty()) return null
        return "These operations did not finish the last time Archive ran. Nothing was resumed; items that were already finished stay in place.\n\n" +
            lines.joinToString("\n")
    }

    /** What [rollBack] did, as phrases for the user; [problems] lists what is left for the user to do. */
    private class Rollback(val done: List<String>, val problems: List<String>)

    /**
     * Undoes the unfinished part of a journaled operation: deletes its staging folders and gives each backup
     * whose replacement never completed its old name back. Only journaled paths are touched, and only while
     * they still carry the names Archive gave them.
     */
    private fun rollBack(journal: OperationJournal): Rollback {
        val records = journal.records
        val done = ArrayList<String>()
        val problems = ArrayList<String>()
        for (record in records) {
            val folder = (record as? OperationJournal.Record.Stage)?.folder ?: continue
            if (!folder.name.startsWith(STAGING_PREFIX) || !present(folder)) continue
            if (runCatching { deleteTree(folder, OperationControl()) }.isSuccess) done += "unfinished files were removed"
            else problems += "remove \u201c${folder.name}\u201d from \u201c${shown(folder.parentFile ?: folder)}\u201d yourself"
        }
        for (index in records.indices.reversed()) {
            val record = records[index] as? OperationJournal.Record.Backup ?: continue
            val original = record.original
            val backup = record.backup
            val parent = original.parentFile ?: continue
            val replaced = records.subList(index + 1, records.size).any { it is OperationJournal.Record.Placed && same(it.target, original) }
            if (replaced || !present(backup) || backup.parentFile?.path != parent.path || !backup.name.startsWith(BACKUP_PREFIX)) continue
            // The new item reached its place after all, or something else took the name: the backup stays.
            if (find(parent, original.name) != null) continue
            if (backup.renameTo(original)) done += "\u201c${original.name}\u201d was restored from its backup"
            else problems += "rename \u201c${backup.name}\u201d back to \u201c${original.name}\u201d yourself"
        }
        return Rollback(done.distinct(), problems)
    }

    /**
     * One operation's staging area: a hidden folder inside the destination, so finished results are moved
     * into place with an atomic rename instead of a second full copy. Large archives therefore need free
     * space once, not twice, and never touch the app's private cache. The folder is created on first use,
     * and every change to shared storage is journaled before it is made.
     */
    private inner class Staging(val destination: File, val journal: OperationJournal) {
        private var folder: File? = null
        private var slots = 0

        /** A new empty folder to build one result in. */
        fun slot(): File {
            val work = folder ?: File(destination, "$STAGING_PREFIX${UUID.randomUUID().toString().take(8)}").also {
                if (present(it)) throw PocketError("Cannot create a temporary folder in the destination.")
                journal.stage(it)
                if (!it.mkdir()) throw PocketError("Cannot create a temporary folder in the destination.")
                folder = it
            }
            val slot = File(work, (slots++).toString())
            if (!slot.mkdir()) throw PocketError("Cannot create a temporary folder in the destination.")
            return slot
        }

        /** Renames the finished [output] to [name] in the destination; nothing existing is ever replaced here. */
        fun publish(output: File, name: String): File {
            val target = File(destination, Safety.name(name))
            if (find(destination, name) != null) throw PocketError("Destination collision; nothing overwritten.")
            journal.target(target)
            if (!output.renameTo(target)) throw PocketError("Cannot move the finished result into place.")
            journal.placed(target)
            return target
        }

        /** Removes the staging folder and undoes unfinished replacements; the journal goes once nothing is left. */
        fun finish() {
            val result = rollBack(journal)
            if (result.problems.isNotEmpty()) throw PocketError("Temporary cleanup failed: ${result.problems.joinToString("; ")}.")
            journal.close()
        }
    }

    /** Runs [block] with a [Staging] area in [destination]; [label] tells the user what was interrupted, should it come to that. */
    private fun <T> staged(destination: File, label: String, reserve: Boolean = true, block: (Staging) -> T): T {
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        if (reserve) Safety.space(destination)
        val staging = Staging(destination, OperationJournal.create(journals, label, destination))
        try { return block(staging) } finally { staging.finish() }
    }

    private fun deleteVerifiedSource(source: Entry, copied: File, control: OperationControl) {
        control.check()
        val current = source.file
        if (current.length() != source.size || current.lastModified() != source.modified) {
            throw PocketError("Source changed after copying; source retained.")
        }
        if (copied.length() != source.size) throw PocketError("Destination size differs from the source; source retained.")
        // Hashing a multi-gigabyte pair costs two more full reads, so honour the verification setting.
        if (!verifyLargeFiles && source.size > Safety.VERIFY_LIMIT) {
            control.check()
            if (!current.delete()) throw PocketError("Copy completed, but source deletion failed. Both copies remain.")
            return
        }
        val sourceHash = open(current).use { Safety.digest(it, control) }
        val destinationHash = open(copied).use { Safety.digest(it, control) }
        if (!sourceHash.contentEquals(destinationHash)) throw PocketError("Source changed or destination verification failed; source retained.")
        control.check()
        if (!current.delete()) throw PocketError("Copy verified, but source deletion failed. Both copies remain.")
    }

    /** New files only, always inside a staging slot. Existing user files are never opened for writing. */
    private fun writeVerified(parent: File, name: String, control: OperationControl, source: () -> InputStream): File {
        Safety.name(name)
        if (find(parent, name) != null) throw PocketError("Destination collision; nothing overwritten.")
        val created = File(parent, name)
        try {
            val expected = source().use { input ->
                created.outputStream().use { out -> Safety.transfer(input, out, control, name, spaceRoot = parent) }
            }
            if (verifyLargeFiles || created.length() <= Safety.VERIFY_LIMIT) {
                val actual = open(created).use { Safety.digest(it, control) }
                if (!expected.contentEquals(actual)) throw PocketError("Copy verification failed; source retained.")
            }
            control.check()
            return created
        } catch (error: Exception) {
            if (created.exists() && !created.delete()) throw PocketError("Write failed and partial destination could not be removed. Inspect destination. Source retained.")
            throw error
        }
    }

    /** Asks about an existing [name] before any long work starts; the item itself is left alone for now. */
    private fun approveReplace(parent: File, name: String, confirm: (String) -> Boolean): (String) -> Boolean {
        if (find(parent, name) == null) return confirm
        if (!confirm(name)) throw PocketError("Collision cancelled. Existing data was not replaced.")
        return { true }
    }

    /**
     * Replacement is opt-in per collision. Called right before the finished result is renamed into place:
     * the old data is kept under a backup name, journaled before the rename so it can be given back.
     */
    private fun makeRoom(parent: File, name: String, confirm: (String) -> Boolean, journal: OperationJournal): Pair<File, File>? {
        val existing = find(parent, name) ?: return null
        if (!confirm(name)) throw PocketError("Collision cancelled. Existing data was not replaced.")
        val backup = File(parent, "$BACKUP_PREFIX${UUID.randomUUID().toString().take(8)}-${name.take(150)}")
        if (find(parent, backup.name) != null) throw PocketError("Cannot preserve existing item as backup; replacement aborted.")
        journal.backup(existing, backup)
        if (!existing.renameTo(backup)) throw PocketError("Cannot preserve existing item as backup; replacement aborted.")
        return existing to backup
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

    fun copy(entries: List<Entry>, destination: File, move: Boolean, control: OperationControl, confirm: (String) -> Boolean) =
        staged(destination, "${if (move) "Moving" else "Copying"} ${describe(entries.map(Entry::name))} into \u201c${shown(destination)}\u201d", reserve = false) { staging ->
            copyInto(entries, destination, move, control, confirm, staging)
        }

    private fun copyInto(entries: List<Entry>, destination: File, move: Boolean, control: OperationControl, confirm: (String) -> Boolean, staging: Staging) {
        for (source in entries) {
            control.check()
            if (source.directory && inside(source.file, destination)) throw PocketError("Destination is inside the source folder.")
            if (canonical(source.file.parentFile ?: source.file) == canonical(destination)) throw PocketError("Source and destination are the same folder.")
            // Snapshot before destination creation, preventing traversal of newly created output.
            val planned = plan(listOf(source), control)
            val replace = approveReplace(destination, source.name, confirm)
            if (move && moveByRename(source, destination, replace, staging.journal)) continue // same volume: atomic rename
            val slot = staging.slot()
            if (source.directory) copyTree(planned, slot, control) else writeVerified(slot, source.name, control) { open(source.file) }
            makeRoom(destination, source.name, replace, staging.journal)
            staging.publish(File(slot, source.name), source.name)
            if (move) {
                // Delete only individually verified files. Never recursively delete the source
                // folder: another application might have added new/unverified children.
                for (item in planned.filter { !it.entry.directory }) {
                    control.check()
                    deleteVerifiedSource(item.entry, File(destination, item.path), control)
                }
                // Remove now-empty source folders, deepest first; folders with new content remain.
                planned.filter { it.entry.directory }.asReversed().forEach { it.entry.file.delete() }
            }
        }
    }

    /**
     * Same-volume move as one atomic rename, journaled like a publish. When the rename is refused (for
     * example across volumes), a backup made for it gets its name back and false sends the caller down
     * the copy, verify, then delete route.
     */
    private fun moveByRename(source: Entry, destination: File, confirm: (String) -> Boolean, journal: OperationJournal): Boolean {
        val target = File(destination, source.name)
        val backup = makeRoom(destination, source.name, confirm, journal)
        journal.target(target)
        if (renameMove(source.file, target)) { journal.placed(target); return true }
        if (backup != null && !backup.second.renameTo(backup.first)) {
            throw PocketError("Cannot rename \u201c${backup.second.name}\u201d back to \u201c${backup.first.name}\u201d. Sources retained.")
        }
        return false
    }

    /** Recreates a planned folder inside [parent], a staging slot, verifying every file. */
    private fun copyTree(planned: List<Planned>, parent: File, control: OperationControl) {
        val top = planned.first()
        val target = File(parent, top.entry.name)
        if (!target.mkdir()) throw PocketError("Cannot create destination folder.")
        val directories = hashMapOf(top.path to target)
        for (item in planned.drop(1)) {
            control.check()
            val itemParent = directories[item.path.substringBeforeLast('/')] ?: throw PocketError("Invalid folder plan.")
            if (item.entry.directory) {
                val dir = File(itemParent, item.entry.name)
                if (!dir.mkdir()) throw PocketError("Cannot create child folder.")
                directories[item.path] = dir
            } else writeVerified(itemParent, item.entry.name, control) { open(item.entry.file) }
        }
    }

    fun zip(entries: List<Entry>, destination: File, name: String, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) {
        Safety.name(name)
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        // Asked before compressing; an existing item is renamed to its backup name only once the new archive is complete.
        val replace = approveReplace(destination, name, confirm)
        staged(destination, "Creating \u201c$name\u201d in \u201c${shown(destination)}\u201d") { staging ->
            val planned = plan(entries, control)
            val sources = planned.map { p -> ArchiveSource(p.path, p.entry.directory, p.entry.size) { open(p.entry.file) } }
            val lower = name.lowercase(Locale.ROOT)
            val output = File(staging.slot(), when { lower.endsWith(".7z") -> "created.7z"; lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "created.tar.gz"; else -> "created.zip" })
            when {
                lower.endsWith(".7z") -> ArchiveEngine.create7z(sources, output, password, control)
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                    if (password != null && password.isNotEmpty()) throw PocketError("TAR.GZ does not support passwords. Use 7z for encryption.")
                    ArchiveEngine.createTarGz(sources, output, control)
                }
                else -> ArchiveEngine.createZip(sources, output, password, control)
            }
            makeRoom(destination, name, replace, staging.journal)
            // Same volume: the finished archive is renamed into place, so it is never copied twice.
            staging.publish(output, name)
        }
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

    /** "Extract here": unpacks into a staging folder on the destination volume, then renames the results into [destination] with collision checks. */
    fun extractHere(source: Entry, destination: File, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) =
        staged(destination, "Extracting \u201c${source.name}\u201d into \u201c${shown(destination)}\u201d") { staging ->
            if (source.directory) throw PocketError("Select an archive file, not a folder.")
            val out = staging.slot()
            ArchiveEngine.extract(source.file, out, password, control)
            control.check()
            val produced = list(out)
            if (produced.isEmpty()) throw PocketError("The archive is empty.")
            copyInto(produced, destination, move = true, control = control, confirm = confirm, staging = staging)
        }

    fun extract(source: Entry, destination: File, folderName: String, password: CharArray?, control: OperationControl, confirm: (String) -> Boolean) {
        Safety.name(folderName)
        if (source.directory) throw PocketError("Select an archive file, not a folder.")
        if (!destination.isDirectory) throw PocketError("Destination folder is not available.")
        // Asked before the long extraction; an existing item is renamed to its backup name only once the new folder is complete.
        val replace = approveReplace(destination, folderName, confirm)
        staged(destination, "Extracting \u201c${source.name}\u201d into \u201c${shown(destination)}\u201d") { staging ->
            val out = staging.slot()
            ArchiveEngine.extract(source.file, out, password, control)
            control.check()
            makeRoom(destination, folderName, replace, staging.journal)
            staging.publish(out, folderName)
        }
    }

    private companion object {
        /** Prefix of the destination-volume staging folders created by [staged]. */
        const val STAGING_PREFIX = ".alalzip-tmp-"
        /** Prefix of the names that replaced items keep (see [makeRoom]). */
        const val BACKUP_PREFIX = "AP-backup-"
    }
}
