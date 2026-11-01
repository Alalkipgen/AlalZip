package app.archivepocket.data

import app.archivepocket.core.PocketError
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Write-ahead journal of one operation's changes to shared storage, kept in app-private storage.
 * Every step is appended and synced before the change it announces is made, so after the process is
 * killed the next start knows exactly which staging folders to delete and which AP-backup renames to
 * undo, without ever searching storage for those names.
 *
 * One tab-separated line per record: `operation id label destination` first, then `stage folder`,
 * `backup original backup`, `target file` (about to be renamed into place) and `placed file`.
 */
internal class OperationJournal private constructor(
    val file: File,
    val id: String,
    val label: String,
    val destination: File,
) {
    sealed class Record {
        data class Stage(val folder: File) : Record()
        data class Backup(val original: File, val backup: File) : Record()
        data class Target(val target: File) : Record()
        data class Placed(val target: File) : Record()
    }

    private val entries = ArrayList<Record>()
    private var written = false
    val records: List<Record> get() = entries

    fun stage(folder: File) = append(Record.Stage(folder))
    fun backup(original: File, backup: File) = append(Record.Backup(original, backup))
    fun target(target: File) = append(Record.Target(target))
    fun placed(target: File) = append(Record.Placed(target))

    private fun append(record: Record) {
        val text = StringBuilder()
        // The file only appears once an operation is about to change shared storage.
        if (!written) text.append(line(HEADER, id, label, destination.path))
        text.append(
            when (record) {
                is Record.Stage -> line(STAGE, record.folder.path)
                is Record.Backup -> line(BACKUP, record.original.path, record.backup.path)
                is Record.Target -> line(TARGET, record.target.path)
                is Record.Placed -> line(PLACED, record.target.path)
            }
        )
        try {
            val folder = file.parentFile
            if (folder != null && !folder.isDirectory && !folder.mkdirs()) throw IOException("journal folder")
            FileOutputStream(file, true).use { out ->
                out.write(text.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (_: IOException) {
            throw PocketError("Cannot record the operation in private storage; nothing was changed.")
        }
        written = true
        entries += record
    }

    /** The operation is over and nothing is left to undo. */
    fun close() {
        if (written) file.delete()
    }

    companion object {
        const val SUFFIX = ".journal"
        private const val HEADER = "operation"
        private const val STAGE = "stage"
        private const val BACKUP = "backup"
        private const val TARGET = "target"
        private const val PLACED = "placed"

        fun create(directory: File, label: String, destination: File): OperationJournal {
            val id = UUID.randomUUID().toString()
            return OperationJournal(File(directory, id + SUFFIX), id, label, destination)
        }

        /** Journals left behind by earlier processes, oldest first; unreadable ones are deleted. */
        fun leftovers(directory: File): List<OperationJournal> =
            (directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) } ?: emptyArray())
                .sortedBy { it.lastModified() }
                .mapNotNull { file -> read(file) ?: run { file.delete(); null } }

        /** A torn last line (the process died while writing it) and unknown lines are ignored. */
        fun read(file: File): OperationJournal? {
            val text = try { file.readText(Charsets.UTF_8) } catch (_: IOException) { return null }
            val lines = text.split('\n').dropLast(1).map { line -> line.split('\t').map(::decode) }
            val header = lines.firstOrNull() ?: return null
            if (header.size != 4 || header[0] != HEADER) return null
            val journal = OperationJournal(file, header[1], header[2], File(header[3]))
            journal.written = true
            for (fields in lines.drop(1)) {
                journal.entries += when {
                    fields.size == 2 && fields[0] == STAGE -> Record.Stage(File(fields[1]))
                    fields.size == 3 && fields[0] == BACKUP -> Record.Backup(File(fields[1]), File(fields[2]))
                    fields.size == 2 && fields[0] == TARGET -> Record.Target(File(fields[1]))
                    fields.size == 2 && fields[0] == PLACED -> Record.Placed(File(fields[1]))
                    else -> continue
                }
            }
            return journal
        }

        private fun line(vararg fields: String) = fields.joinToString("\t", postfix = "\n", transform = ::encode)

        private fun encode(value: String) = buildString {
            for (c in value) when (c) {
                '%' -> append("%25")
                '\t' -> append("%09")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                else -> append(c)
            }
        }

        private fun decode(value: String): String {
            if ('%' !in value) return value
            val out = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val code = if (value[i] == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (code != null) { out.append(code.toChar()); i += 3 } else { out.append(value[i]); i++ }
            }
            return out.toString()
        }
    }
}
