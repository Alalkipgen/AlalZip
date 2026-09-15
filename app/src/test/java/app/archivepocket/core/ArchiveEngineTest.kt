package app.archivepocket.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = "မင်္ဂလာပါ — ArchivePocket 🌿".toByteArray(Charsets.UTF_8)
    private fun source(name: String = "စာ/日本語.txt") = ArchiveSource(name, false) { ByteArrayInputStream(payload) }
    private fun archive(password: CharArray? = null): File = temporary.newFile("${System.nanoTime()}.zip").also {
        ArchiveEngine.createZip(listOf(source()), it, password, OperationControl())
    }
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected archive failure") } catch (_: java.io.IOException) { }
        catch (_: com.github.junrar.exception.RarException) { }
    }
    @Test fun previewListsUnicodeEntriesWithoutExtracting() {
        val zip = archive("disposable-fixture-only".toCharArray())
        val preview = ArchiveEngine.preview(zip)
        assertEquals(zip.name, preview.archiveName)
        assertEquals(1, preview.items.size)
        assertEquals("စာ/日本語.txt", preview.items.single().path)
        assertEquals(payload.size.toLong(), preview.items.single().size)
        assertFalse(preview.items.single().directory)
        assertEquals(1, temporary.root.listFiles()!!.size) // preview wrote/extracted nothing
    }
    @Test fun openItemExtractsOnlySelectedMember() {
        val password = "disposable-fixture-only".toCharArray()
        val zip = archive(password)
        val session = temporary.newFolder("view-session")
        val output = File(session, "日本語.txt")
        ArchiveEngine.openItem(zip, "စာ/日本語.txt", output, password, OperationControl())
        assertArrayEquals(payload, output.readBytes())
        assertEquals(listOf("日本語.txt"), session.list()!!.toList())
        password.fill('\u0000')
    }
    @Test fun zipUnicodeRoundTrip() {
        val zip = archive(); val out = temporary.newFolder()
        ArchiveEngine.extract(zip, out, null, OperationControl())
        assertArrayEquals(payload, File(out, "စာ/日本語.txt").readBytes())
    }
    @Test fun aes256CorrectPassword() {
        val password = "disposable-fixture-only".toCharArray()
        val zip = archive(password); val out = temporary.newFolder()
        net.lingala.zip4j.ZipFile(zip).use {
            assertEquals(net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256, it.fileHeaders.single().aesExtraDataRecord.aesKeyStrength)
        }
        ArchiveEngine.extract(zip, out, password, OperationControl())
        assertArrayEquals(payload, File(out, "စာ/日本語.txt").readBytes())
        password.fill('\u0000')
    }
    @Test fun wrongAndMissingPasswordFail() {
        val zip = archive("disposable-fixture-only".toCharArray())
        expectFailure { ArchiveEngine.extract(zip, temporary.newFolder(), "wrong".toCharArray(), OperationControl()) }
        expectFailure { ArchiveEngine.extract(zip, temporary.newFolder(), null, OperationControl()) }
    }
    /** RAR-style flow: no password → ask (wrongPassword=false); rejected password → ask again (wrongPassword=true). Nothing is written. */
    @Test fun encryptedZipSignalsPasswordRequest() {
        val zip = archive("disposable-fixture-only".toCharArray())
        val out = temporary.newFolder()
        val missing = try { ArchiveEngine.extract(zip, out, null, OperationControl()); null } catch (e: PasswordRequiredError) { e }
        assertNotNull(missing); assertFalse(missing!!.wrongPassword)
        val wrong = try { ArchiveEngine.extract(zip, out, "wrong".toCharArray(), OperationControl()); null } catch (e: PasswordRequiredError) { e }
        assertNotNull(wrong); assertTrue(wrong!!.wrongPassword)
        assertTrue(out.walkTopDown().filter { it.isFile }.none())
        val item = File(temporary.newFolder(), "member.txt")
        val openMissing = try { ArchiveEngine.openItem(zip, "စာ/日本語.txt", item, null, OperationControl()); null } catch (e: PasswordRequiredError) { e }
        assertNotNull(openMissing); assertFalse(openMissing!!.wrongPassword); assertFalse(item.exists())
    }
    @Test fun plainZipNeverAsksForPassword() {
        val zip = archive(); val out = temporary.newFolder()
        ArchiveEngine.extract(zip, out, null, OperationControl())
        assertTrue(File(out, "စာ/日本語.txt").isFile)
    }
    @Test fun corruptArchiveFails() {
        val zip = archive(); val bytes = zip.readBytes(); zip.writeBytes(bytes.copyOf(bytes.size / 2))
        expectFailure { ArchiveEngine.extract(zip, temporary.newFolder(), null, OperationControl()) }
    }
    @Test fun traversalFailsWithoutWritingOutside() {
        val zip = temporary.newFile("hostile.zip")
        ZipOutputStream(zip.outputStream()).use { it.putNextEntry(ZipEntry("../escaped.txt")); it.write(payload); it.closeEntry() }
        expectFailure { ArchiveEngine.extract(zip, temporary.newFolder(), null, OperationControl()) }
        assertFalse(File(temporary.root, "escaped.txt").exists())
    }
    @Test fun duplicateCaseCollisionFails() {
        val zip = temporary.newFile("collision.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            for (name in listOf("same.txt", "SAME.txt")) { output.putNextEntry(ZipEntry(name)); output.write(payload); output.closeEntry() }
        }
        expectFailure { ArchiveEngine.extract(zip, temporary.newFolder(), null, OperationControl()) }
    }
    @Test fun cancellationBeforeStartFails() {
        val control = OperationControl().also { it.cancel() }
        expectFailure { ArchiveEngine.createZip(listOf(source()), temporary.newFile(), null, control) }
    }
    @Test fun cancellationDuringStreamingFails() {
        lateinit var control: OperationControl
        control = OperationControl { _, _ -> control.cancel() }
        expectFailure { Safety.transfer(ByteArrayInputStream(ByteArray(256 * 1024)), ByteArrayOutputStream(), control, "test") }
    }
    @Test fun limitsAndUnsafeNames() {
        for (name in listOf("../x", "/absolute", "C:\\file", "a//b", "a/./b", "a/../b", "a\u0000b")) {
            expectFailure { Safety.relative(name) }
        }
        val limit = 64L * 1024 * 1024
        val budget = ExpansionBudget(1, limit)
        expectFailure { budget.entry("huge", limit + 1) }
        expectFailure { ExpansionBudget(1, limit).add(17 * 1024 * 1024) }
        assertEquals("မြန်မာ/日本語.txt", Safety.relative("မြန်မာ/日本語.txt"))
    }
    /** The fixed 2 GiB expansion cap is gone: multi-gigabyte entries pass while the volume has room. */
    @Test fun multiGigabyteEntryFitsWhenSpaceAllows() {
        val budget = ExpansionBudget(4L * 1024 * 1024 * 1024, 8L * 1024 * 1024 * 1024)
        budget.entry("series/episode.mkv", 5L * 1024 * 1024 * 1024)
        expectFailure { budget.entry("series/extra.mkv", 4L * 1024 * 1024 * 1024) }
        assertTrue(Safety.expansionLimit(temporary.root) >= 0)
    }
    @Test fun emptyZipRoundTrip() {
        val zip = temporary.newFile("empty.zip")
        ArchiveEngine.createZip(emptyList(), zip, null, OperationControl())
        val out = temporary.newFolder(); ArchiveEngine.extract(zip, out, null, OperationControl())
        assertEquals(0, out.listFiles()!!.size)
    }
}