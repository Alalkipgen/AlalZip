package app.archivepocket.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.OutputStream

/** Standards-compatible TAR.GZ creation and extraction. TAR.GZ has no password-encryption standard. */
object TarGzSupport {
    fun create(sources: List<ArchiveSource>, output: File, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        GzipCompressorOutputStream(output.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                for (source in sources) {
                    control.check()
                    val path = Safety.relative(source.path) + if (source.directory) "/" else ""
                    val entry = TarArchiveEntry(path).apply { if (!source.directory) size = source.size }
                    tar.putArchiveEntry(entry)
                    if (!source.directory) source.open().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            control.check(); val count = input.read(buffer); if (count < 0) break
                            tar.write(buffer, 0, count); control.advance(count, path)
                        }
                    }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
    }

    fun extract(input: File, root: File, control: OperationControl) {
        val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
        val watch = SpaceWatch(root)
        GzipCompressorInputStream(input.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var count = 0
                while (true) {
                    control.check()
                    val entry = tar.nextEntry ?: break
                    count++
                    if (count > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
                    if (entry.isSymbolicLink || entry.isLink) throw PocketError("TAR links are not supported.")
                    budget.entry(entry.name, entry.size)
                    val target = Safety.target(root, entry.name)
                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) throw PocketError("Cannot create temporary directory.")
                    } else {
                        if (target.exists()) throw PocketError("Archive path collision.")
                        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw PocketError("Cannot create temporary directory.")
                        bounded(target.outputStream().buffered(), target.name, budget, watch, control).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { val n = tar.read(buffer); if (n < 0) break; out.write(buffer, 0, n) }
                        }
                        if (entry.size >= 0 && target.length() != entry.size) throw PocketError("TAR entry size mismatch.")
                    }
                }
            }
        }
    }

    private fun bounded(raw: OutputStream, name: String, budget: ExpansionBudget, watch: SpaceWatch, control: OperationControl) =
        object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.check(); budget.add(len); watch.consume(len.toLong())
                raw.write(b, off, len); control.advance(len, name)
            }
            override fun flush() = raw.flush()
            override fun close() = raw.close()
        }
}
