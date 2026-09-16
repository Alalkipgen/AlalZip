package app.archivepocket.core

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.enums.AesKeyStrength
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** JVM regressions. Native 7z still requires Android-device testing; see docs/PERFORMANCE.md. */
class StreamingRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(300_017).also { java.util.Random(42).nextBytes(it) }
    private fun source(path: String, bytes: ByteArray = payload) =
        ArchiveSource(path, false, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try { block() } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    @Test fun hashlessCopyPreservesBytesAndAcceptsReusableBuffer() {
        val buffer = ByteArray(7)
        repeat(2) {
            val out = ByteArrayOutputStream()
            assertEquals(payload.size.toLong(), Safety.copyStream(ByteArrayInputStream(payload), out, OperationControl(), "copy", buffer = buffer))
            assertArrayEquals(payload, out.toByteArray())
        }
    }

    @Test fun verifiedCopiesStillReturnSha256() {
        val out = ByteArrayOutputStream()
        val hash = Safety.transfer(ByteArrayInputStream(payload), out, OperationControl(), "copy")
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(payload), hash)
        assertArrayEquals(payload, out.toByteArray())
    }

    @Test fun boundedSinksCanOwnProgressWithoutDoubleCounting() {
        var calls = 0
        Safety.copyStream(ByteArrayInputStream(payload), ByteArrayOutputStream(), OperationControl { _, _ -> calls++ }, "copy", reportProgress = false)
        assertEquals(0, calls)
    }

    @Test fun hashlessCopyHonoursLimitBeforeWritingOversizedChunk() {
        val out = ByteArrayOutputStream()
        failure<PocketError> {
            Safety.copyStream(ByteArrayInputStream(payload), out, OperationControl(), "copy", limit = 2, buffer = ByteArray(3))
        }
        assertEquals(0, out.size())
    }

    @Test fun cancellationStillWorksWithoutHashingOrProgress() {
        val control = OperationControl().also { it.cancel() }
        val out = ByteArrayOutputStream()
        failure<PocketError> { Safety.copyStream(ByteArrayInputStream(payload), out, control, "copy", reportProgress = false) }
        assertEquals(0, out.size())
    }

    @Test fun zeroLengthReadDoesNotEndTheCopy() {
        val input = object : InputStream() {
            val delegate = ByteArrayInputStream(payload)
            var first = true
            override fun read(): Int = delegate.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; return 0 }
                return delegate.read(b, off, len)
            }
        }
        val out = ByteArrayOutputStream()
        Safety.copyStream(input, out, OperationControl(), "copy")
        assertArrayEquals(payload, out.toByteArray())
    }

    private class Volume(var available: Long) : File("test-volume") {
        var queries = 0
        override fun getUsableSpace(): Long { queries++; return available }
    }

    @Test fun spaceWatchAmortizesQueriesAndCountsFirstChunk() {
        val volume = Volume(Safety.RESERVE + 1000)
        val watch = SpaceWatch(volume, 100)
        watch.consume(25); watch.consume(25); watch.consume(50)
        assertEquals(1, volume.queries)
        watch.consume(1)
        assertEquals(2, volume.queries)
    }

    @Test fun spaceWatchAllowsSmallWritesNearReserveButRejectsOversizedNativeChunks() {
        val volume = Volume(Safety.RESERVE + 5)
        SpaceWatch(volume, 100).consume(5)
        failure<PocketError> { SpaceWatch(volume, 2).consume(6) }
        volume.available = Safety.RESERVE - 1
        failure<PocketError> { SpaceWatch(volume).consume(1) }
    }

    @Test fun safetySpaceUsesSingleFilesystemSnapshot() {
        val volume = Volume(Safety.RESERVE + 10)
        Safety.space(volume, 10)
        assertEquals(1, volume.queries)
    }

    @Test fun fastAesZipRoundTripAndSingleMemberOpen() {
        val zip = temporary.newFile("aes.zip")
        val password = "public-regression-fixture".toCharArray()
        try {
            ArchiveEngine.createZip(listOf(source("folder/data.bin")), zip, password, OperationControl())
            ZipFile(zip).use {
                assertEquals(AesKeyStrength.KEY_STRENGTH_256, it.fileHeaders.single().aesExtraDataRecord.aesKeyStrength)
            }
            val missing = temporary.newFolder()
            assertFalse(failure<PasswordRequiredError> { ArchiveEngine.extract(zip, missing, null, OperationControl()) }.wrongPassword)
            assertTrue(missing.listFiles()!!.isEmpty())
            val out = temporary.newFolder()
            ArchiveEngine.extract(zip, out, password, OperationControl())
            assertArrayEquals(payload, File(out, "folder/data.bin").readBytes())
            val member = File(temporary.newFolder(), "data.bin")
            ArchiveEngine.openItem(zip, "folder/data.bin", member, password, OperationControl())
            assertArrayEquals(payload, member.readBytes())
        } finally { password.fill('\u0000') }
    }

    @Test fun zipManySmallEntriesReuseTransferPath() {
        val data = "small-file".toByteArray()
        val zip = temporary.newFile("many.zip")
        ArchiveEngine.createZip((0 until 200).map { source("folder/$it.txt", data) }, zip, null, OperationControl())
        val out = temporary.newFolder()
        ArchiveEngine.extract(zip, out, null, OperationControl())
        repeat(200) { assertArrayEquals(data, File(out, "folder/$it.txt").readBytes()) }
    }

    @Test fun tarGzFastRoundTripIncludesDirectoriesAndEmptyFiles() {
        val archive = temporary.newFile("roundtrip.tar.gz")
        val entries = listOf(ArchiveSource("folder", true) { error("Directory must not be opened") }, source("folder/data.bin"), source("empty", byteArrayOf()))
        TarGzSupport.create(entries, archive, OperationControl())
        val out = temporary.newFolder()
        ArchiveEngine.extract(archive, out, null, OperationControl())
        assertArrayEquals(payload, File(out, "folder/data.bin").readBytes())
        assertTrue(File(out, "empty").isFile)
        assertEquals(0L, File(out, "empty").length())
    }

    @Test fun tarGzRejectsDuplicateSourcePathsBeforeCreatingOutput() {
        val archive = File(temporary.root, "duplicate.tar.gz")
        failure<PocketError> { TarGzSupport.create(listOf(source("a"), source("A")), archive, OperationControl()) }
        assertFalse(archive.exists())
    }

    @Test fun tarGzRejectsTruncatedSource() {
        val archive = temporary.newFile("truncated.tar.gz")
        val entry = ArchiveSource("x", false, 10) { ByteArrayInputStream(byteArrayOf(1)) }
        failure<IOException> { TarGzSupport.create(listOf(entry), archive, OperationControl()) }
    }

    @Test fun tarGzRejectsCorruptGzipTrailer() {
        val archive = temporary.newFile("crc.tar.gz")
        TarGzSupport.create(listOf(source("data.bin")), archive, OperationControl())
        val data = archive.readBytes()
        data[data.size - 8] = (data[data.size - 8].toInt() xor 1).toByte()
        archive.writeBytes(data)
        failure<IOException> { TarGzSupport.extract(archive, temporary.newFolder(), OperationControl()) }
    }

    @Test fun tarGzRejectsLinksSpecialFilesAndTraversal() {
        for ((name, type) in listOf("link" to TarConstants.LF_SYMLINK, "fifo" to TarConstants.LF_FIFO, "../escape" to TarConstants.LF_NORMAL)) {
            val archive = temporary.newFile("hostile-${System.nanoTime()}.tar.gz")
            GzipCompressorOutputStream(archive.outputStream()).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    val entry = TarArchiveEntry(name, type).apply { if (type == TarConstants.LF_SYMLINK) linkName = "outside" }
                    tar.putArchiveEntry(entry); tar.closeArchiveEntry(); tar.finish()
                }
            }
            failure<PocketError> { TarGzSupport.extract(archive, temporary.newFolder(), OperationControl()) }
        }
        assertFalse(File(temporary.root, "escape").exists())
    }
}
