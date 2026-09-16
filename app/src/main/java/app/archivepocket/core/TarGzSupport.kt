package app.archivepocket.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.File
import java.io.OutputStream

/** Standards-compatible TAR.GZ creation and extraction. TAR.GZ has no password-encryption standard. */
object TarGzSupport {
    fun create(sources: List<ArchiveSource>, output: File, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        val seen = HashSet<String>()
        for (source in sources) {
            control.check()
            val key = java.text.Normalizer.normalize(Safety.relative(source.path), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            if (!seen.add(key) || source.size < 0) throw PocketError("Duplicate path or invalid source size.")
        }
        val buffer = ByteArray(Safety.IO_BUFFER_SIZE)
        val parameters = GzipParameters().apply { compressionLevel = 1 }
        val watch = SpaceWatch(output.parentFile!!)
        output.outputStream().buffered(Safety.IO_BUFFER_SIZE).use { raw ->
            // Check actual compressed output, including gzip headers/trailers, not just source bytes.
            val guarded = object : OutputStream() {
                override fun write(b: Int) { control.check(); watch.consume(1); raw.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    control.check(); watch.consume(len.toLong()); raw.write(b, off, len)
                }
                override fun flush() = raw.flush()
                override fun close() = raw.close()
            }
            GzipCompressorOutputStream(guarded, parameters).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for (source in sources) {
                        control.check()
                        val path = Safety.relative(source.path) + if (source.directory) "/" else ""
                        val entry = TarArchiveEntry(path).apply { if (!source.directory) size = source.size }
                        tar.putArchiveEntry(entry)
                        if (!source.directory) source.open().use { input ->
                            val copied = Safety.copyStream(input, tar, control, path, limit = source.size, buffer = buffer)
                            if (copied != source.size) throw PocketError("Source size changed while creating TAR.GZ.")
                        }
                        tar.closeArchiveEntry()
                    }
                    tar.finish()
                }
            }
        }
    }

    fun extract(input: File, root: File, control: OperationControl) {
        val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
        val watch = SpaceWatch(root)
        val buffer = ByteArray(Safety.IO_BUFFER_SIZE)
        GzipCompressorInputStream(input.inputStream().buffered(Safety.IO_BUFFER_SIZE)).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    control.check()
                    val entry = tar.nextEntry ?: break
                    if (entry.isSymbolicLink || entry.isLink) throw PocketError("TAR links are not supported.")
                    // isFile/isDirectory are compatibility predicates, not a strict type allowlist.
                    // Inspect the stored header flag before any destination path is created.
                    when (entry.linkFlag) {
                        TarConstants.LF_NORMAL, TarConstants.LF_OLDNORM, TarConstants.LF_DIR -> Unit
                        else -> throw PocketError("TAR special files are not supported.")
                    }
                    if (!tar.canReadEntryData(entry)) throw PocketError("Unsupported TAR entry encoding.")
                    budget.entry(entry.name, entry.size)
                    val target = Safety.target(root, entry.name)
                    if (entry.isDirectory) {
                        if (!target.isDirectory && !target.mkdirs()) throw PocketError("Cannot create temporary directory.")
                    } else {
                        if (target.exists()) throw PocketError("Archive path collision.")
                        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw PocketError("Cannot create temporary directory.")
                        bounded(target.outputStream().buffered(Safety.IO_BUFFER_SIZE), target.name, budget, watch, control).use { out ->
                            Safety.copyStream(tar, out, control, target.name, limit = entry.size, buffer = buffer, reportProgress = false)
                        }
                        if (target.length() != entry.size) throw PocketError("TAR entry size mismatch.")
                    }
                }
                // TAR ends before the gzip trailer. Drain to EOF so gzip CRC/size checks actually run.
                // Bound padding as well: a compressed zero tail must not bypass the expansion guard.
                while (true) {
                    control.check()
                    val n = gzip.read(buffer)
                    if (n < 0) break
                    if (n > 0) budget.add(n)
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
