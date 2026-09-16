package app.archivepocket.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Keep each hostile type separate so the CI failure names the offending header type. */
class TarEntryTypeTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun archive(type: Byte, name: String = "entry", data: ByteArray = byteArrayOf()): File {
        val file = temporary.newFile()
        GzipCompressorOutputStream(file.outputStream()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val entry = TarArchiveEntry(name, type).apply {
                    size = data.size.toLong()
                    if (type == TarConstants.LF_SYMLINK || type == TarConstants.LF_LINK) linkName = "outside"
                }
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
        // Verify the fixture survived serialization with the intended type and path.
        GzipCompressorInputStream(file.inputStream()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                val entry = requireNotNull(tar.nextEntry)
                assertEquals(type, entry.linkFlag)
                assertEquals(name, entry.name)
            }
        }
        return file
    }

    private fun rejected(type: Byte, name: String = "entry") {
        val input = archive(type, name)
        val out = temporary.newFolder()
        try {
            TarGzSupport.extract(input, out, OperationControl())
            fail("Accepted unsafe TAR type ${type.toInt()} for $name")
        } catch (_: PocketError) {
            assertTrue("Reject before creating an output entry", out.listFiles()!!.isEmpty())
        }
    }

    @Test fun rejectsFifo() = rejected(TarConstants.LF_FIFO)
    @Test fun rejectsCharacterDevice() = rejected(TarConstants.LF_CHR)
    @Test fun rejectsBlockDevice() = rejected(TarConstants.LF_BLK)
    @Test fun rejectsHardLink() = rejected(TarConstants.LF_LINK)
    @Test fun rejectsSymbolicLink() = rejected(TarConstants.LF_SYMLINK)
    @Test fun rejectsUnknownType() = rejected('?'.code.toByte())
    @Test fun rejectsSpecialTypeEvenWithDirectoryName() = rejected(TarConstants.LF_FIFO, "entry/")

    private fun extractsRegular(type: Byte) {
        val data = "regular-file".toByteArray()
        val input = archive(type, data = data)
        val out = temporary.newFolder()
        TarGzSupport.extract(input, out, OperationControl())
        assertArrayEquals(data, File(out, "entry").readBytes())
    }
    @Test fun acceptsNormalFile() = extractsRegular(TarConstants.LF_NORMAL)
    @Test fun acceptsLegacyNormalFile() = extractsRegular(TarConstants.LF_OLDNORM)
    @Test fun acceptsDirectory() {
        val input = archive(TarConstants.LF_DIR, "folder/")
        val out = temporary.newFolder()
        TarGzSupport.extract(input, out, OperationControl())
        assertTrue(File(out, "folder").isDirectory)
    }
    @Test fun rejectsParentTraversal() {
        rejected(TarConstants.LF_NORMAL, "../escape")
        assertFalse(File(temporary.root, "escape").exists())
    }
}
