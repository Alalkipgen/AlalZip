package app.archivepocket.core

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.RarException
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class ArchiveSource(val path: String, val directory: Boolean, val open: () -> InputStream)
data class ArchiveItem(val path: String, val directory: Boolean, val size: Long)
data class ArchivePreview(val archiveName: String, val items: List<ArchiveItem>)

object ArchiveEngine {
    private const val MAX_OPEN_SIZE = 512L * 1024 * 1024

    private fun hasPassword(password: CharArray?): Boolean = password != null && password.isNotEmpty()

    /** RAR-style flow: an encrypted ZIP without a password asks for one instead of failing. */
    private fun requireZipPassword(zip: ZipFile, password: CharArray?) {
        if (!hasPassword(password) && runCatching { zip.isEncrypted }.getOrDefault(false)) throw PasswordRequiredError(false)
    }

    /** Translates a Zip4j failure while a password was supplied into a retryable wrong-password signal. */
    private fun mapZip(error: ZipException, password: CharArray?): Exception =
        if (hasPassword(password) && (error.type == ZipException.Type.WRONG_PASSWORD || error.type == ZipException.Type.CHECKSUM_MISMATCH)) PasswordRequiredError(true) else error

    private fun openRar(input: File, password: CharArray?): Archive {
        val options = ArchiveOptions.builder().password(password).maxDictionarySize(64L * 1024 * 1024).build()
        return try { Archive(input, options) } catch (e: RarException) { throw mapRar(e, password) }
    }

    private fun requireRarPassword(rar: Archive, password: CharArray?) {
        if (hasPassword(password)) return
        val encrypted = runCatching { rar.isEncrypted }.getOrDefault(false) || rar.fileHeaders.any { it.isEncrypted }
        if (encrypted) throw PasswordRequiredError(false)
    }

    /** Junrar signals encryption problems through exception subclasses; classify by name to stay version-tolerant. */
    private fun mapRar(error: RarException, password: CharArray?): Exception {
        val kind = error.javaClass.simpleName
        return when {
            !hasPassword(password) && kind.contains("Encrypt") -> PasswordRequiredError(false)
            hasPassword(password) && kind.contains("Encrypt") -> PocketError("This RAR uses encryption that the built-in extractor does not support (for example encrypted RAR5).")
            hasPassword(password) && (kind.contains("Crc") || kind.contains("Decipher")) -> PasswordRequiredError(true)
            hasPassword(password) && (kind.contains("Corrupt") || kind.contains("MainHeaderNull")) -> PocketError("Wrong password or damaged RAR archive (encrypted headers could not be read).")
            else -> error
        }
    }

    /** Reads only archive metadata. No content is extracted or written to storage. */
    fun preview(input: File): ArchivePreview {
        val signature = ByteArray(8)
        val count = input.inputStream().use { it.read(signature) }
        if (count < 2) throw PocketError("Archive header is missing or damaged.")
        val items = when {
            signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> ZipFile(input).use { zip ->
                if (zip.isSplitArchive) throw PocketError("Split ZIP preview is not supported.")
                if (!zip.isValidZipFile) throw PocketError("ZIP archive is damaged or invalid.")
                zip.fileHeaders.map { header -> ArchiveItem(header.fileName, header.isDirectory, header.uncompressedSize) }
            }
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
                if (signature[6] != 0.toByte() && !(count >= 8 && signature[6] == 1.toByte() && signature[7] == 0.toByte())) {
                    throw PocketError("Unsupported RAR signature.")
                }
                Archive(input, ArchiveOptions.builder().maxDictionarySize(64L * 1024 * 1024).build()).use { rar ->
                    if (rar.hasBrokenHeaders()) throw PocketError("RAR has damaged/truncated headers.")
                    rar.fileHeaders.map { header -> ArchiveItem(header.fileName, header.isDirectory, header.fullUnpackSize) }
                }
            }
            else -> throw PocketError("Preview supports ZIP and RAR4/RAR5 archives.")
        }
        if (items.size > Safety.MAX_ENTRIES) throw PocketError("Archive has too many entries to preview safely.")
        return ArchivePreview(input.name, items)
    }

    /** Extracts one explicitly selected archive member to private cache for read-only viewing. */
    fun openItem(input: File, itemPath: String, output: File, password: CharArray?, control: OperationControl) {
        val wanted = Safety.relative(itemPath)
        if (output.exists() || !output.parentFile!!.isDirectory) throw PocketError("Private preview destination is unavailable.")
        val signature = ByteArray(8)
        val count = input.inputStream().use { it.read(signature) }
        if (count < 2) throw PocketError("Archive header is missing or damaged.")
        when {
            signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> ZipFile(input).use { zip ->
                if (password != null) zip.setPassword(password)
                if (zip.isSplitArchive) throw PocketError("Split ZIP entry viewing is not supported.")
                val header = zip.fileHeaders.firstOrNull { !it.isDirectory && runCatching { Safety.relative(it.fileName) }.getOrNull() == wanted }
                    ?: throw PocketError("Archive item was not found.")
                if (header.isEncrypted && !hasPassword(password)) throw PasswordRequiredError(false)
                try {
                    openItemOutput(output, header.uncompressedSize, control).use { out ->
                        zip.getInputStream(header).use { stream -> Safety.transfer(stream, out, control, output.name, limit = MAX_OPEN_SIZE, spaceRoot = output.parentFile) }
                    }
                } catch (e: ZipException) { output.delete(); throw mapZip(e, password) }
                if (output.length() != header.uncompressedSize) throw PocketError("ZIP entry size mismatch.")
            }
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
                openRar(input, password).use { rar ->
                    if (rar.hasBrokenHeaders()) throw PocketError("RAR has damaged/truncated headers.")
                    val header = rar.fileHeaders.firstOrNull { !it.isDirectory && runCatching { Safety.relative(it.fileName) }.getOrNull() == wanted }
                        ?: throw PocketError("Archive item was not found.")
                    if (header.isEncrypted && !hasPassword(password)) throw PasswordRequiredError(false)
                    if (header.isSplitBefore || header.isSplitAfter || header.redirection != null) throw PocketError("Split or linked RAR entries cannot be opened.")
                    if (header.isRar5Container && header.rar5WinSize > 64L * 1024 * 1024) throw PocketError("RAR dictionary exceeds the mobile safety limit.")
                    try { openItemOutput(output, header.fullUnpackSize, control).use { rar.extractFile(header, it) } }
                    catch (e: RarException) { output.delete(); throw mapRar(e, password) }
                    if (output.length() != header.fullUnpackSize) throw PocketError("RAR entry size mismatch.")
                }
            }
            else -> throw PocketError("Opening items is supported for ZIP and RAR4/RAR5 archives.")
        }
    }

    private fun openItemOutput(output: File, declaredSize: Long, control: OperationControl): OutputStream {
        if (declaredSize < 0 || declaredSize > MAX_OPEN_SIZE) throw PocketError("File is too large to open from an archive (512 MiB limit). Extract it first.")
        Safety.space(output.parentFile!!, declaredSize)
        val raw = output.outputStream().buffered()
        return object : OutputStream() {
            private var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.check()
                if (len > MAX_OPEN_SIZE - written) throw PocketError("Archive item exceeds the 512 MiB viewing limit.")
                Safety.space(output.parentFile!!, len.toLong())
                raw.write(b, off, len); written += len; control.advance(len, output.name)
            }
            override fun flush() = raw.flush()
            override fun close() = raw.close()
        }
    }

    fun createZip(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        val seen = HashSet<String>()
        output.outputStream().buffered().use { raw ->
            ZipOutputStream(raw, password).use { zip ->
                for (source in sources) {
                    control.check()
                    val path = Safety.relative(source.path)
                    if (!seen.add(path.lowercase(java.util.Locale.ROOT))) throw PocketError("Duplicate archive path.")
                    val parameters = ZipParameters().apply {
                        fileNameInZip = path + if (source.directory) "/" else ""
                        compressionMethod = if (source.directory) CompressionMethod.STORE else CompressionMethod.DEFLATE
                        if (password != null && password.isNotEmpty() && !source.directory) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    zip.putNextEntry(parameters)
                    if (!source.directory) source.open().use { Safety.transfer(it, zip, control, path, spaceRoot = output.parentFile) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun extract(input: File, destination: File, password: CharArray?, control: OperationControl) {
        require(destination.isDirectory && destination.listFiles()?.isEmpty() == true)
        val signature = ByteArray(8)
        val count = input.inputStream().use { it.read(signature) }
        if (count < 2) throw PocketError("Archive header is missing or damaged.")
        when {
            signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() -> extractZip(input, destination, password, control)
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
                if (signature[6] != 0.toByte() && !(signature[6] == 1.toByte() && signature[7] == 0.toByte())) {
                    throw PocketError("Unsupported RAR signature. Only RAR4/RAR5 containers are accepted.")
                }
                extractRar(input, destination, password, control)
            }
            else -> throw PocketError("Not a supported ZIP or RAR4/RAR5 archive (or damaged header).")
        }
    }

    private fun boundedOutput(file: File, root: File, budget: ExpansionBudget, control: OperationControl): OutputStream {
        if (file.exists()) throw PocketError("Archive path collision.")
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw PocketError("Cannot create temporary directory.")
        val raw = file.outputStream().buffered()
        return object : OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.check(); budget.add(len); Safety.space(root, len.toLong())
                raw.write(b, off, len); control.advance(len, file.name)
            }
            override fun flush() = raw.flush()
            override fun close() = raw.close()
        }
    }

    private fun directory(file: File) {
        if (!file.isDirectory && !file.mkdirs()) throw PocketError("File/directory collision in archive.")
    }

    private fun extractZip(input: File, root: File, password: CharArray?, control: OperationControl) {
        val budget = ExpansionBudget(input.length())
        ZipFile(input).use { zip ->
            if (password != null) zip.setPassword(password)
            if (zip.isSplitArchive) throw PocketError("Split ZIP archives are not supported in this version.")
            requireZipPassword(zip, password)
            val headers = zip.fileHeaders
            if (headers.size > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            try { for (header in headers) {
                control.check()
                budget.entry(header.fileName, header.uncompressedSize)
                val target = Safety.target(root, header.fileName)
                // Never recreate archive symlinks: every non-directory entry becomes an ordinary file.
                if (header.isDirectory) directory(target) else {
                    // Open the entry stream first: a wrong/missing password fails here, before any output file exists.
                    zip.getInputStream(header).use { stream ->
                        boundedOutput(target, root, budget, control).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) { control.check(); val n = stream.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
                        }
                    }
                    if (target.length() != header.uncompressedSize) throw PocketError("ZIP entry size mismatch.")
                }
            } } catch (e: ZipException) { throw mapZip(e, password) }
        }
    }

    private fun extractRar(input: File, root: File, password: CharArray?, control: OperationControl) {
        val budget = ExpansionBudget(input.length())
        // Junrar is extraction-only. Its UnRAR-derived code must not be used to develop a RAR archiver.
        // Options defensively copy passwords; upstream has no API to wipe the options copy.
        openRar(input, password).use { rar ->
            if (rar.hasBrokenHeaders()) throw PocketError("RAR has damaged/truncated headers; extraction refused.")
            requireRarPassword(rar, password)
            val headers = rar.fileHeaders
            if (headers.size > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            if (headers.any { it.isSplitBefore || it.isSplitAfter }) throw PocketError("Multi-volume RAR is not supported in this version.")
            try { for (header in headers) {
                control.check()
                if (header.redirection != null) throw PocketError("RAR links/redirections are not supported.")
                if (header.isRar5Container && header.rar5WinSize > 64L * 1024 * 1024) throw PocketError("RAR dictionary exceeds the 64 MiB mobile safety limit.")
                budget.entry(header.fileName, header.fullUnpackSize)
                val target = Safety.target(root, header.fileName)
                if (header.isDirectory) directory(target) else {
                    boundedOutput(target, root, budget, control).use { rar.extractFile(header, it) }
                    if (target.length() != header.fullUnpackSize) throw PocketError("RAR entry size mismatch.")
                }
            } } catch (e: RarException) { throw mapRar(e, password) }
        }
    }
}