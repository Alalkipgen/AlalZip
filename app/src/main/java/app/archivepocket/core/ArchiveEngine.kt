package app.archivepocket.core

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.exception.RarException
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class ArchiveSource(val path: String, val directory: Boolean, val size: Long = 0, val open: () -> InputStream)
data class ArchiveItem(val path: String, val directory: Boolean, val size: Long)
data class ArchivePreview(val archiveName: String, val items: List<ArchiveItem>)

object ArchiveEngine {
    internal const val MAX_OPEN_SIZE = 512L * 1024 * 1024

    private fun hasPassword(password: CharArray?): Boolean = password != null && password.isNotEmpty()

    /** RAR5 signature is `Rar!\x1a\x07\x01\x00`; RAR4 ends with `\x00`. */
    private fun isRar5(signature: ByteArray, count: Int): Boolean = count >= 8 && signature[6] == 1.toByte() && signature[7] == 0.toByte()

    /** An encrypted ZIP without a password asks before extracting any entries. */
    private fun requireZipPassword(zip: ZipFile, password: CharArray?) {
        if (!hasPassword(password) && zip.isEncrypted) throw PasswordRequiredError(false)
    }

    private fun mapZip(error: ZipException, password: CharArray?): Exception =
        if (hasPassword(password) && error.type == ZipException.Type.WRONG_PASSWORD) PasswordRequiredError(true) else error

    private fun openRar(input: File, password: CharArray?): Archive {
        val options = ArchiveOptions.builder().password(password).maxDictionarySize(64L * 1024 * 1024).build()
        return try { Archive(input, options) } catch (e: RarException) { throw mapRar(e, password) }
    }

    private fun requireRarPassword(rar: Archive, password: CharArray?) {
        if (hasPassword(password)) return
        val encrypted = runCatching { rar.isEncrypted }.getOrDefault(false) || rar.fileHeaders.any { it.isEncrypted }
        if (encrypted) throw PasswordRequiredError(false)
    }

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
                if (signature[6] != 0.toByte() && !isRar5(signature, count)) {
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
                zip.setBufferSize(Safety.IO_BUFFER_SIZE)
                if (password != null) zip.setPassword(password)
                if (zip.isSplitArchive) throw PocketError("Split ZIP entry viewing is not supported.")
                val header = zip.fileHeaders.firstOrNull { !it.isDirectory && runCatching { Safety.relative(it.fileName) }.getOrNull() == wanted }
                    ?: throw PocketError("Archive item was not found.")
                if (header.isEncrypted && !hasPassword(password)) throw PasswordRequiredError(false)
                try {
                    // Validate the password before creating output. The bounded sink owns byte accounting.
                    zip.getInputStream(header).use { stream ->
                        openItemOutput(output, header.uncompressedSize, control).use { out ->
                            Safety.copyStream(stream, out, control, output.name, limit = MAX_OPEN_SIZE, reportProgress = false)
                        }
                    }
                } catch (e: ZipException) { output.delete(); throw mapZip(e, password) }
                if (output.length() != header.uncompressedSize) throw PocketError("ZIP entry size mismatch.")
            }
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
                // Native C++ unpacker first: it is the same class of engine RAR apps use and it replays solid
                // predecessors natively. Pure-Java junrar stays as the fallback for host JVM tests and for
                // anything the native engine declines to open, keeping the established error mapping.
                if (SevenZipSupport.openRarItem(input, wanted, output, password, control, isRar5(signature, count))) return
                openRar(input, password).use { rar ->
                    if (rar.hasBrokenHeaders()) throw PocketError("RAR has damaged/truncated headers.")
                    val headers = rar.fileHeaders
                    if (headers.size > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
                    val index = headers.indexOfFirst { !it.isDirectory && runCatching { Safety.relative(it.fileName) }.getOrNull() == wanted }
                    if (index < 0) throw PocketError("Archive item was not found.")
                    val header = headers[index]
                    // Junrar otherwise replays solid predecessors into a NullOutputStream that
                    // only overrides write(Int); Java's bulk fallback loops over every byte.
                    // Explicit sequential extraction uses a bounded bulk discard instead, retains
                    // the dictionary in this one Archive instance, and verifies predecessor CRCs.
                    val solid = rar.mainHeader?.isSolid == true || header.isSolid
                    val first = if (solid) 0 else index
                    val budget = ExpansionBudget(input.length(), MAX_OPEN_SIZE)
                    for (i in first..index) {
                        control.check()
                        val member = headers[i]
                        if (member.isSplitBefore || member.isSplitAfter || member.redirection != null) throw PocketError("Split or linked RAR entries cannot be opened.")
                        if (member.isRar5Container && member.rar5WinSize > 64L * 1024 * 1024) throw PocketError("RAR dictionary exceeds the mobile safety limit.")
                        if (member.isEncrypted && !hasPassword(password)) throw PasswordRequiredError(false)
                        if (member.fullUnpackSize < 0 || member.fullUnpackSize > MAX_OPEN_SIZE) throw PocketError("File or solid RAR prefix is too large to preview. Extract it first.")
                        budget.entry(member.fileName, member.fullUnpackSize)
                    }
                    try {
                        for (i in first until index) {
                            control.check()
                            val member = headers[i]
                            if (member.isDirectory) continue
                            val discard = previewDiscard(control, budget, "Preparing solid RAR: ${member.fileName}")
                            rar.extractFile(member, discard)
                        }
                        control.check()
                        openItemOutput(output, header.fullUnpackSize, control).use { raw ->
                            val bounded = object : OutputStream() {
                                override fun write(b: Int) { budget.add(1); raw.write(b) }
                                override fun write(b: ByteArray, off: Int, len: Int) { budget.add(len); raw.write(b, off, len) }
                                override fun flush() = raw.flush()
                            }
                            rar.extractFile(header, bounded)
                        }
                    } catch (e: RarException) {
                        output.delete()
                        control.check()
                        var cause: Throwable? = e
                        repeat(16) {
                            val current = cause
                            if (current is PocketError) throw current
                            cause = current?.cause
                        }
                        throw mapRar(e, password)
                    }
                    if (output.length() != header.fullUnpackSize) throw PocketError("RAR entry size mismatch.")
                }
            }
            else -> throw PocketError("Opening items is supported for ZIP and RAR4/RAR5 archives.")
        }
    }

    /** Consume decoded solid-prefix chunks without allocating files or visiting every byte. */
    internal fun previewDiscard(control: OperationControl, budget: ExpansionBudget, label: String): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) { control.check(); budget.add(1); control.advance(1, label) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (off < 0 || len < 0 || off > b.size - len) throw IndexOutOfBoundsException()
                control.check()
                budget.add(len)
                control.advance(len, label)
            }
        }

    /** Bounded, space-watched sink for a single previewed member; shared by the Java and native paths. */
    internal fun openItemOutput(output: File, declaredSize: Long, control: OperationControl): OutputStream {
        if (declaredSize < 0 || declaredSize > MAX_OPEN_SIZE) throw PocketError("File is too large to open from an archive (512 MiB limit). Extract it first.")
        Safety.space(output.parentFile!!, declaredSize)
        val raw = output.outputStream().buffered(Safety.IO_BUFFER_SIZE)
        val watch = SpaceWatch(output.parentFile!!)
        return object : OutputStream() {
            private var written = 0L
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.check()
                if (len > declaredSize - written) throw PocketError("Archive item exceeds its declared size.")
                watch.consume(len.toLong())
                raw.write(b, off, len); written += len; control.advance(len, output.name)
            }
            override fun flush() = raw.flush()
            override fun close() = raw.close()
        }
    }

    fun create7z(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) =
        SevenZipSupport.create(sources, output, password, control)

    fun createTarGz(sources: List<ArchiveSource>, output: File, control: OperationControl) =
        TarGzSupport.create(sources, output, control)

    fun createZip(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        val seen = HashSet<String>()
        val buffer = ByteArray(Safety.IO_BUFFER_SIZE)
        output.outputStream().buffered(Safety.IO_BUFFER_SIZE).use { raw ->
            ZipOutputStream(raw, password).use { zip ->
                for (source in sources) {
                    control.check()
                    val path = Safety.relative(source.path)
                    if (!seen.add(path.lowercase(java.util.Locale.ROOT))) throw PocketError("Duplicate archive path.")
                    val parameters = ZipParameters().apply {
                        fileNameInZip = path + if (source.directory) "/" else ""
                        compressionMethod = if (source.directory) CompressionMethod.STORE else CompressionMethod.DEFLATE
                        compressionLevel = CompressionLevel.FAST
                        if (password != null && password.isNotEmpty() && !source.directory) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    zip.putNextEntry(parameters)
                    if (!source.directory) source.open().use {
                        Safety.copyStream(it, zip, control, path, spaceRoot = output.parentFile, buffer = buffer)
                    }
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
            count >= 6 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) ->
                SevenZipSupport.extract(input, destination, password, control)
            signature[0] == 0x1f.toByte() && signature[1] == 0x8b.toByte() -> {
                if (hasPassword(password)) throw PocketError("TAR.GZ does not support passwords. Use 7z for encrypted archives.")
                TarGzSupport.extract(input, destination, control)
            }
            count >= 7 && signature.copyOfRange(0, 6).contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)) -> {
                if (signature[6] != 0.toByte() && !isRar5(signature, count)) {
                    throw PocketError("Unsupported RAR signature. Only RAR4/RAR5 containers are accepted.")
                }
                // Native unpacker first (fast, C++); junrar fallback when it is unavailable or declines the file.
                if (!SevenZipSupport.extractRar(input, destination, password, control, isRar5(signature, count))) {
                    extractRar(input, destination, password, control)
                }
            }
            else -> throw PocketError("Not a supported ZIP, RAR4/RAR5, 7z or TAR.GZ archive (or damaged header).")
        }
    }

    private fun boundedOutput(file: File, budget: ExpansionBudget, control: OperationControl, watch: SpaceWatch): OutputStream {
        if (file.exists()) throw PocketError("Archive path collision.")
        if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw PocketError("Cannot create temporary directory.")
        val raw = file.outputStream().buffered(Safety.IO_BUFFER_SIZE)
        return object : OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                control.check(); budget.add(len); watch.consume(len.toLong())
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
        val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
        val watch = SpaceWatch(root)
        val buffer = ByteArray(Safety.IO_BUFFER_SIZE)
        ZipFile(input).use { zip ->
            zip.setBufferSize(Safety.IO_BUFFER_SIZE)
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
                    zip.getInputStream(header).use { stream ->
                        boundedOutput(target, budget, control, watch).use { output ->
                            Safety.copyStream(stream, output, control, target.name,
                                limit = header.uncompressedSize, buffer = buffer, reportProgress = false)
                        }
                    }
                    if (target.length() != header.uncompressedSize) throw PocketError("ZIP entry size mismatch.")
                }
            } } catch (e: ZipException) { throw mapZip(e, password) }
        }
    }

    private fun extractRar(input: File, root: File, password: CharArray?, control: OperationControl) {
        val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
        val watch = SpaceWatch(root)
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
                    boundedOutput(target, budget, control, watch).use { rar.extractFile(header, it) }
                    if (target.length() != header.fullUnpackSize) throw PocketError("RAR entry size mismatch.")
                }
            } } catch (e: RarException) { throw mapRar(e, password) }
        }
    }
}
