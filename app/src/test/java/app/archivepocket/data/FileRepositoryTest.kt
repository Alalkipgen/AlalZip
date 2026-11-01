package app.archivepocket.data

import app.archivepocket.core.ArchiveEngine
import app.archivepocket.core.ArchiveSource
import app.archivepocket.core.OperationControl
import app.archivepocket.core.PocketError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class FileRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: File
    private lateinit var cache: File
    private lateinit var files: File
    private val renames = AtomicInteger()
    private val never: (String) -> Boolean = { throw AssertionError("Unexpected question about $it") }

    @Before fun setUp() {
        storage = temporary.newFolder("storage")
        cache = temporary.newFolder("cache")
        files = temporary.newFolder("files")
    }

    private fun repository(rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }) =
        FileRepository(cache, storage, files) { from, to -> renames.incrementAndGet(); rename(from, to) }

    private fun folder(path: String) = File(storage, path).also { it.mkdirs() }
    private fun write(file: File, text: String) = file.also { it.parentFile!!.mkdirs(); it.writeText(text) }
    private fun journalFolder() = File(files, "operation-journals")
    private fun journals() = journalFolder().listFiles()?.toList().orEmpty()
    private fun staging(dir: File) = dir.listFiles()!!.filter { it.name.startsWith(".alalzip-tmp-") }
    private fun backups(dir: File) = dir.listFiles()!!.filter { it.name.startsWith("AP-backup-") }
    private fun zip(file: File, vararg items: Pair<String, String>) = file.also {
        val sources = items.map { (path, text) -> ArchiveSource(path, false, text.length.toLong()) { text.byteInputStream() } }
        ArchiveEngine.createZip(sources, it, null, OperationControl())
    }

    private inline fun failure(block: () -> Unit): PocketError {
        try { block() } catch (error: PocketError) { return error }
        throw AssertionError("Expected PocketError")
    }

    private fun assertNoLeftovers(dir: File) {
        assertEquals(emptyList<File>(), staging(dir))
        assertEquals(emptyList<File>(), journals())
    }

    // ---------------------------------------------------------------- copy and move

    @Test fun copyFileKeepsSourceAndLeavesNoStaging() {
        val source = write(File(folder("A"), "notes.txt"), "hello")
        val destination = folder("B")
        val repository = repository()
        repository.copy(listOf(repository.entry(source)), destination, move = false, control = OperationControl(), confirm = never)
        assertEquals("hello", File(destination, "notes.txt").readText())
        assertTrue(source.isFile)
        assertNoLeftovers(destination)
    }

    @Test fun copyFolderRecreatesNestedTree() {
        val source = folder("A/Photos")
        write(File(source, "one.txt"), "1")
        write(File(source, "trip/two.txt"), "2")
        File(source, "empty").mkdirs()
        val destination = folder("B")
        val repository = repository()
        repository.copy(listOf(repository.entry(source)), destination, move = false, control = OperationControl(), confirm = never)
        assertEquals("1", File(destination, "Photos/one.txt").readText())
        assertEquals("2", File(destination, "Photos/trip/two.txt").readText())
        assertTrue(File(destination, "Photos/empty").isDirectory)
        assertTrue(File(source, "trip/two.txt").isFile)
        assertNoLeftovers(destination)
    }

    @Test fun moveOnSameVolumeIsOneRename() {
        val source = folder("A/Photos")
        write(File(source, "trip/two.txt"), "2")
        val destination = folder("B")
        val repository = repository()
        repository.copy(listOf(repository.entry(source)), destination, move = true, control = OperationControl(), confirm = never)
        assertEquals(1, renames.get())
        assertFalse(source.exists())
        assertEquals("2", File(destination, "Photos/trip/two.txt").readText())
        assertNoLeftovers(destination)
    }

    @Test fun moveFallsBackToVerifiedCopyWhenRenameIsRefused() {
        val source = folder("A/Photos")
        write(File(source, "one.txt"), "1")
        write(File(source, "trip/two.txt"), "2")
        val destination = folder("B")
        val repository = repository { _, _ -> false }
        repository.copy(listOf(repository.entry(source)), destination, move = true, control = OperationControl(), confirm = never)
        assertEquals(1, renames.get())
        assertEquals("1", File(destination, "Photos/one.txt").readText())
        assertEquals("2", File(destination, "Photos/trip/two.txt").readText())
        // Verified files are deleted one by one, then the folders that became empty.
        assertFalse(source.exists())
        assertNoLeftovers(destination)
    }

    @Test fun replacingKeepsTheOldItemAsBackup() {
        val source = write(File(folder("A"), "notes.txt"), "new")
        val destination = folder("B")
        write(File(destination, "notes.txt"), "old")
        val asked = ArrayList<String>()
        val repository = repository()
        repository.copy(listOf(repository.entry(source)), destination, move = false, control = OperationControl()) { asked += it; true }
        assertEquals(listOf("notes.txt"), asked)
        assertEquals("new", File(destination, "notes.txt").readText())
        val backup = backups(destination).single()
        assertTrue(backup.name.endsWith("-notes.txt"))
        assertEquals("old", backup.readText())
        assertNoLeftovers(destination)
    }

    @Test fun declinedReplacementChangesNothing() {
        val source = write(File(folder("A"), "notes.txt"), "new")
        val destination = folder("B")
        write(File(destination, "notes.txt"), "old")
        val repository = repository()
        failure { repository.copy(listOf(repository.entry(source)), destination, move = true, control = OperationControl()) { false } }
        assertEquals("old", File(destination, "notes.txt").readText())
        assertEquals("new", source.readText())
        assertEquals(emptyList<File>(), backups(destination))
        assertEquals(0, renames.get())
        assertNoLeftovers(destination)
    }

    @Test fun replacingMoveWithRefusedRenameRenamesTheOldItemOnlyOnceTheCopyIsComplete() {
        val source = write(File(folder("A"), "big.bin"), "x".repeat(300_000))
        val destination = folder("B")
        val existing = write(File(destination, "big.bin"), "old")
        var checked = false
        val control = OperationControl { _, _ ->
            // While the new copy is being written the existing item still has its own name.
            if (!checked) {
                checked = true
                assertEquals("old", existing.readText())
                assertEquals(emptyList<File>(), backups(destination))
            }
        }
        val repository = repository { _, _ -> false }
        repository.copy(listOf(repository.entry(source)), destination, move = true, control = control) { true }
        assertTrue(checked)
        assertEquals("x".repeat(300_000), File(destination, "big.bin").readText())
        assertEquals("old", backups(destination).single().readText())
        assertFalse(source.exists())
        assertNoLeftovers(destination)
    }

    // ---------------------------------------------------------------- staging and publishing

    @Test fun extractBuildsInJournaledStagingAndPublishesWhenComplete() {
        val archive = zip(File(folder("Download"), "photos.zip"), "a.txt" to "a".repeat(200_000), "trip/b.txt" to "b")
        val destination = archive.parentFile!!
        var checked = false
        val control = OperationControl { _, _ ->
            if (!checked) {
                checked = true
                assertFalse(File(destination, "photos").exists())
                val work = staging(destination).single()
                val journal = OperationJournal.read(journals().single())!!
                assertEquals(listOf(OperationJournal.Record.Stage(work)), journal.records)
                assertEquals(destination, journal.destination)
            }
        }
        val repository = repository()
        repository.extract(repository.entry(archive), destination, "photos", null, control, never)
        assertTrue(checked)
        assertEquals("a".repeat(200_000), File(destination, "photos/a.txt").readText())
        assertEquals("b", File(destination, "photos/trip/b.txt").readText())
        assertNoLeftovers(destination)
    }

    @Test fun extractAsksFirstAndRenamesTheExistingFolderOnlyAtTheEnd() {
        val archive = zip(File(folder("Download"), "photos.zip"), "a.txt" to "a".repeat(200_000))
        val destination = archive.parentFile!!
        write(File(destination, "photos/old.txt"), "old")
        val events = ArrayList<String>()
        val control = OperationControl { _, _ ->
            if ("extracting" !in events) {
                events += "extracting"
                assertEquals("old", File(destination, "photos/old.txt").readText())
                assertEquals(emptyList<File>(), backups(destination))
            }
        }
        val repository = repository()
        repository.extract(repository.entry(archive), destination, "photos", null, control) { events += "asked $it"; true }
        assertEquals(listOf("asked photos", "extracting"), events)
        assertEquals("a".repeat(200_000), File(destination, "photos/a.txt").readText())
        assertFalse(File(destination, "photos/old.txt").exists())
        assertEquals("old", File(backups(destination).single(), "old.txt").readText())
        assertNoLeftovers(destination)
    }

    @Test fun cancelledExtractLeavesNothingBehind() {
        val archive = zip(File(folder("Download"), "photos.zip"), "a.txt" to "a".repeat(200_000), "b.txt" to "b".repeat(200_000))
        val destination = archive.parentFile!!
        write(File(destination, "photos/old.txt"), "old")
        lateinit var control: OperationControl
        control = OperationControl { _, _ -> control.cancel() }
        val repository = repository()
        failure { repository.extract(repository.entry(archive), destination, "photos", null, control) { true } }
        assertEquals(setOf("photos.zip", "photos"), destination.list()!!.toSet())
        assertEquals("old", File(destination, "photos/old.txt").readText())
        assertNoLeftovers(destination)
    }

    @Test fun zipAsksBeforeCompressingAndPublishesByRename() {
        val source = write(File(folder("Docs"), "report.txt"), "report")
        val destination = source.parentFile!!
        write(File(destination, "report.zip"), "old archive")
        val repository = repository()
        repository.zip(listOf(repository.entry(source)), destination, "report.zip", null, OperationControl()) {
            assertEquals(emptyList<File>(), staging(destination))
            true
        }
        val created = File(destination, "report.zip")
        assertEquals(listOf("report.txt"), ArchiveEngine.preview(created).items.map { it.path })
        assertEquals("old archive", backups(destination).single().readText())
        assertNoLeftovers(destination)
    }

    @Test fun extractHerePublishesEveryItemAndKeepsReplacedOnesAsBackups() {
        val archive = zip(File(folder("Download"), "notes.zip"), "a.txt" to "new a", "b.txt" to "b")
        val destination = archive.parentFile!!
        write(File(destination, "a.txt"), "old a")
        val repository = repository()
        repository.extractHere(repository.entry(archive), destination, null, OperationControl()) { it == "a.txt" }
        assertEquals("new a", File(destination, "a.txt").readText())
        assertEquals("b", File(destination, "b.txt").readText())
        assertEquals("old a", backups(destination).single().readText())
        assertNoLeftovers(destination)
    }

    // ---------------------------------------------------------------- recovery after a killed process

    @Test fun recoveryRemovesJournaledStagingAndRestoresUnfinishedBackup() {
        val destination = folder("Download")
        val journal = OperationJournal.create(journalFolder(), "Extracting \u201cphotos.zip\u201d into \u201cDownload\u201d", destination)
        val work = File(destination, ".alalzip-tmp-1234abcd")
        journal.stage(work)
        write(File(work, "0/a.txt"), "partial")
        val original = write(File(destination, "photos/old.txt"), "old").parentFile!!
        val backup = File(destination, "AP-backup-1234abcd-photos")
        journal.backup(original, backup)
        assertTrue(original.renameTo(backup))
        journal.target(File(destination, "photos"))
        // The process dies here: the finished folder was never renamed into place.

        val report = repository().recoverInterrupted()
        assertNotNull(report)
        assertTrue(report!!, "Extracting \u201cphotos.zip\u201d into \u201cDownload\u201d" in report)
        assertTrue(report, "unfinished files were removed" in report)
        assertTrue(report, "\u201cphotos\u201d was restored from its backup" in report)
        assertEquals("old", File(destination, "photos/old.txt").readText())
        assertEquals(emptyList<File>(), backups(destination))
        assertNoLeftovers(destination)
        assertNull(repository().recoverInterrupted())
    }

    @Test fun recoveryKeepsTheBackupOnceTheReplacementWasPlaced() {
        val destination = folder("Download")
        val journal = OperationJournal.create(journalFolder(), "Copying \u201cnotes.txt\u201d", destination)
        val original = write(File(destination, "notes.txt"), "old")
        val backup = File(destination, "AP-backup-1234abcd-notes.txt")
        journal.backup(original, backup)
        assertTrue(original.renameTo(backup))
        journal.target(original)
        write(original, "new")
        journal.placed(original)

        val report = repository().recoverInterrupted()!!
        assertTrue(report, "nothing needed to be undone" in report)
        assertEquals("new", original.readText())
        assertEquals("old", backup.readText())
        assertNoLeftovers(destination)
    }

    @Test fun recoveryKeepsTheBackupWhenTheNameIsTakenAgain() {
        val destination = folder("Download")
        val journal = OperationJournal.create(journalFolder(), "Moving \u201cnotes.txt\u201d", destination)
        val original = write(File(destination, "notes.txt"), "old")
        val backup = File(destination, "AP-backup-1234abcd-notes.txt")
        journal.backup(original, backup)
        assertTrue(original.renameTo(backup))
        journal.target(original)
        // Renamed into place, but the process died before "placed" was written.
        write(File(destination, "NOTES.txt"), "new")

        repository().recoverInterrupted()
        assertEquals("new", File(destination, "NOTES.txt").readText())
        assertEquals("old", backup.readText())
        assertNoLeftovers(destination)
    }

    @Test fun recoveryNeverTouchesWhatNoJournalRecorded() {
        val destination = folder("Download")
        write(File(destination, ".alalzip-tmp-deadbeef/0/file.txt"), "someone else's")
        write(File(destination, "AP-backup-deadbeef-notes.txt"), "kept by the user")
        // A journal whose destination cannot be read now is kept for a later start.
        val unreadable = OperationJournal.create(journalFolder(), "Copying \u201cx\u201d", File(storage, "card/Missing"))
        unreadable.stage(File(storage, "card/Missing/.alalzip-tmp-00000000"))

        assertNull(repository().recoverInterrupted())
        assertEquals("someone else's", File(destination, ".alalzip-tmp-deadbeef/0/file.txt").readText())
        assertEquals("kept by the user", File(destination, "AP-backup-deadbeef-notes.txt").readText())
        assertEquals(listOf(unreadable.file), journals())
    }

    @Test fun journalSurvivesSpecialCharactersAndIgnoresATornLastLine() {
        val destination = folder("50% off\tsale")
        val journal = OperationJournal.create(journalFolder(), "Copying \u201ca\nb\u201d", destination)
        journal.stage(File(destination, ".alalzip-tmp-1234abcd"))
        journal.backup(File(destination, "x%y"), File(destination, "AP-backup-1234abcd-x%y"))
        journal.file.appendText("placed\t/storage/emula")

        val read = OperationJournal.read(journal.file)!!
        assertEquals(journal.id, read.id)
        assertEquals("Copying \u201ca\nb\u201d", read.label)
        assertEquals(destination, read.destination)
        assertEquals(journal.records, read.records)
        assertEquals(2, read.records.size)
    }

    @Test fun killedExtractIsRolledBackOnTheNextStart() {
        val archive = zip(File(folder("Download"), "photos.zip"), "a.txt" to "a".repeat(200_000), "b.txt" to "b".repeat(200_000))
        val destination = archive.parentFile!!
        write(File(destination, "photos/old.txt"), "old")
        var report: String? = null
        lateinit var control: OperationControl
        control = OperationControl { _, _ ->
            if (report == null) {
                // A new process starts while this one is frozen mid-extraction, as if it had been killed.
                report = repository().recoverInterrupted()
                assertEquals(emptyList<File>(), staging(destination))
                assertEquals("old", File(destination, "photos/old.txt").readText())
                control.cancel()
            }
        }
        val frozen = repository()
        failure { frozen.extract(frozen.entry(archive), destination, "photos", null, control) { true } }
        assertTrue(report!!, "Extracting \u201cphotos.zip\u201d into \u201cDownload\u201d: unfinished files were removed." in report!!)
        assertEquals(setOf("photos.zip", "photos"), destination.list()!!.toSet())
        assertEquals("old", File(destination, "photos/old.txt").readText())
        assertNoLeftovers(destination)
    }

    @Test fun killedBetweenBackupAndMoveGivesTheOldItemItsNameBack() {
        val source = write(File(folder("A"), "notes.txt"), "new")
        val destination = folder("B")
        write(File(destination, "notes.txt"), "old")
        val frozen = repository { _, _ ->
            // The old item has just been renamed to its backup name when the process dies.
            assertEquals("old", backups(destination).single().readText())
            val report = repository().recoverInterrupted()!!
            assertTrue(report, "\u201cnotes.txt\u201d was restored from its backup" in report)
            false
        }
        try {
            frozen.copy(listOf(frozen.entry(source)), destination, move = true, control = OperationControl()) { true }
            fail("The frozen process must not carry on as if nothing happened")
        } catch (_: PocketError) { }
        assertEquals("old", File(destination, "notes.txt").readText())
        assertEquals("new", source.readText())
        assertEquals(emptyList<File>(), backups(destination))
        assertNoLeftovers(destination)
    }

    // ---------------------------------------------------------------- archives opened from other apps

    private fun incoming() = File(cache, "incoming-archives")
    private fun sessions() = incoming().listFiles()?.toList().orEmpty()
    private fun copies() = incoming().walkTopDown().filter { it.isFile }.toList()
    /** Endless provider stream; [onRead] runs before each chunk is handed out, counting from 0. */
    private fun stream(onRead: (Int) -> Unit) = object : InputStream() {
        private var reads = 0
        override fun read(): Int = throw AssertionError("Copies must read in chunks")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            onRead(reads++)
            b.fill(7, off, off + len)
            return len
        }
    }

    @Test fun importCopiesTheArchiveIntoPrivateCache() {
        val bytes = zip(File(temporary.root, "shared.zip"), "a.txt" to "hello").readBytes()
        val repository = repository()
        val entry = repository.importIncoming("photos.zip", bytes.size.toLong(), OperationControl()) { bytes.inputStream() }
        assertEquals("photos.zip", entry.name)
        assertEquals(bytes.size.toLong(), entry.size)
        assertEquals(listOf(entry.file), copies())
        assertTrue(bytes.contentEquals(entry.file.readBytes()))
        assertEquals(listOf("a.txt"), repository.preview(entry).items.map { it.path })
    }

    @Test fun importRefusesAnArchiveThatCannotFitBeforeCopyingAnything() {
        var opened = false
        val error = failure {
            repository().importIncoming("huge.zip", Long.MAX_VALUE / 2, OperationControl()) { opened = true; "x".byteInputStream() }
        }
        assertTrue(error.message!!, error.message!!.startsWith("Not enough storage"))
        assertFalse(opened)
        assertEquals(emptyList<File>(), sessions())
    }

    @Test fun cancelledImportDeletesThePartialCopy() {
        val control = OperationControl()
        val source = stream { reads ->
            if (reads == 2) {
                assertTrue(copies().single().length() > 0)
                control.cancel()
            }
        }
        failure { repository().importIncoming("big.zip", 0, control) { source } }
        assertEquals(emptyList<File>(), sessions())
    }

    @Test fun failedImportDeletesThePartialCopy() {
        val source = stream { reads -> if (reads == 2) throw IOException("Connection lost") }
        try {
            repository().importIncoming("cloud.zip", 0, OperationControl()) { source }
            fail("A broken stream must not produce a copy")
        } catch (error: IOException) {
            assertEquals("Connection lost", error.message)
        }
        assertEquals(emptyList<File>(), sessions())
    }

    @Test fun unreadableOrEmptyImportLeavesNothing() {
        val repository = repository()
        assertEquals("Android could not open the selected archive.",
            failure { repository.importIncoming("a.zip", 0, OperationControl()) { null } }.message)
        assertEquals("The selected archive is empty or could not be copied.",
            failure { repository.importIncoming("a.zip", 0, OperationControl()) { ByteArray(0).inputStream() } }.message)
        assertEquals(emptyList<File>(), sessions())
    }

    @Test fun newImportReplacesThePreviousCopy() {
        val repository = repository()
        val first = repository.importIncoming("one.zip", 0, OperationControl()) { "1".byteInputStream() }
        val second = repository.importIncoming("two.zip", 0, OperationControl()) { "2".byteInputStream() }
        assertFalse(first.file.exists())
        assertEquals(listOf(second.file), copies())
    }

    @Test fun importKeepsOneSafeFileName() {
        val repository = repository()
        fun imported(name: String) = repository.importIncoming(name, 0, OperationControl()) { "1".byteInputStream() }.name
        assertEquals("a_b_c_.zip", imported("a/b\\c:.zip"))
        assertEquals("Archive.zip", imported(".."))
        val long = imported("\u00e9".repeat(300) + ".zip")
        assertTrue(long, long.endsWith(".zip") && long.toByteArray().size <= 200)
    }

    @Test fun discardDeletesOnlyPrivateCopies() {
        val repository = repository()
        val copy = repository.importIncoming("one.zip", 0, OperationControl()) { "1".byteInputStream() }
        val shared = write(File(folder("Download/Archives"), "keep.zip"), "k")
        repository.discardIncoming(repository.entry(shared))
        assertTrue(shared.isFile)
        assertTrue(copy.file.isFile)
        repository.discardIncoming(copy)
        assertEquals(emptyList<File>(), sessions())
    }

    @Test fun startupCleanupRemovesCopiesOfAnEarlierProcess() {
        repository().importIncoming("one.zip", 0, OperationControl()) { "1".byteInputStream() }
        repository().cleanAbandoned()
        assertEquals(emptyList<File>(), sessions())
    }
}
