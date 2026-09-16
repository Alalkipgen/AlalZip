package app.archivepocket.core

import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IArchiveOpenCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Native 7-Zip engine (C++ via 7-Zip-JBinding). Used for 7z creation/extraction and, since the
 * bundled engine also contains the RAR4/RAR5 unpackers, as the primary RAR reader: this is the same
 * kind of native unpacker desktop/mobile RAR apps use, and it replays solid predecessors in C++
 * instead of Java. AES and header encryption remain enabled for password-protected archives.
 */
object SevenZipSupport {
    private const val MAX_DICTIONARY = 64L * 1024 * 1024

    /**
     * Whether the bundled native library loaded. Android builds ship `lib7-Zip-JBinding.so`; host JVM unit
     * tests have no platform jar, so this is `false` there and RAR callers use the pure-Java junrar path.
     * The probe is cached because 7-Zip-JBinding never retries a failed initialization anyway.
     */
    val nativeReady: Boolean by lazy {
        try {
            SevenZip.initSevenZipFromPlatformJAR()
            SevenZip.isInitializedSuccessfully()
        } catch (_: SevenZipNativeInitializationException) {
            false
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** JNI can wrap callback exceptions: retain the actual cancellation/storage/password failure. */
    private class FailureState {
        var failure: Exception? = null
            private set
        fun <T> guard(block: () -> T): T = try { block() } catch (error: Exception) {
            if (failure == null) failure = error
            throw SevenZipException("7-Zip callback failed", error)
        }
    }

    fun create(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        val names = HashSet<String>()
        for (source in sources) {
            control.check()
            val name = java.text.Normalizer.normalize(Safety.relative(source.path), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT)
            if (!names.add(name) || source.size < 0) throw PocketError("Duplicate path or invalid source size.")
        }
        val failures = FailureState()
        val outArchive = SevenZip.openOutArchive7z()
        var random: RandomAccessFile? = null
        var callback: PlainCreateCallback? = null
        try {
            random = RandomAccessFile(output, "rw")
            random.setLength(0)
            // Prefer interactive/mobile speed to maximum compression. Existing solid archives still extract.
            outArchive.setLevel(3)
            outArchive.setSolid(false)
            callback = if (password != null && password.isNotEmpty()) {
                outArchive.setHeaderEncryption(true)
                EncryptedCreateCallback(sources, password, control, failures)
            } else PlainCreateCallback(sources, control, failures)
            outArchive.createArchive(RandomAccessFileOutStream(random), sources.size, callback)
            control.check()
        } catch (error: SevenZipException) {
            throw failures.failure ?: PocketError("7z creation failed. Check storage and source files.")
        } finally {
            runCatching { callback?.close() }
            runCatching { outArchive.close() }
            runCatching { random?.close() }
        }
    }

    private open class PlainCreateCallback(
        private val sources: List<ArchiveSource>, private val control: OperationControl,
        private val failures: FailureState
    ) : IOutCreateCallback<IOutItem7z> {
        private var current: InputStream? = null
        fun close() { current?.close(); current = null }
        override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItem7z>): IOutItem7z = failures.guard {
            control.check()
            val source = sources[index]
            factory.createOutItem().apply {
                propertyPath = Safety.relative(source.path) + if (source.directory) "/" else ""
                propertyIsDir = source.directory
                propertyIsAnti = false
                dataSize = if (source.directory) 0 else source.size
            }
        }
        override fun getStream(index: Int): ISequentialInStream? = failures.guard {
            control.check()
            close()
            val source = sources[index]
            if (source.directory) null else {
                val stream = source.open().buffered(Safety.IO_BUFFER_SIZE).also { current = it }
                object : ISequentialInStream {
                    override fun read(data: ByteArray): Int = failures.guard {
                        control.check()
                        val count = stream.read(data)
                        if (count > 0) control.advance(count, source.path)
                        count.coerceAtLeast(0)
                    }
                    override fun close() { stream.close() }
                }
            }
        }
        override fun setOperationResult(operationResultOk: Boolean) = failures.guard {
            close()
            if (!operationResultOk) throw PocketError("7z item compression failed.")
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) = failures.guard { control.check() }
    }

    private class EncryptedCreateCallback(
        sources: List<ArchiveSource>, private val password: CharArray, control: OperationControl,
        failures: FailureState
    ) : PlainCreateCallback(sources, control, failures), ICryptoGetTextPassword {
        override fun cryptoGetTextPassword(): String = String(password)
    }

    /** Header-password callback shared by every native open. */
    private class OpenCallback(
        private val passwordText: String, private val control: OperationControl, private val failures: FailureState
    ) : IArchiveOpenCallback, ICryptoGetTextPassword {
        var requestedHeaderPassword = false
            private set
        override fun cryptoGetTextPassword(): String = failures.guard {
            control.check()
            requestedHeaderPassword = true
            if (passwordText.isEmpty()) throw PasswordRequiredError(false)
            passwordText
        }
        override fun setTotal(files: Long?, bytes: Long?) = failures.guard { control.check() }
        override fun setCompleted(files: Long?, bytes: Long?) = failures.guard { control.check() }
    }

    private fun label(format: ArchiveFormat): String = if (format == ArchiveFormat.SEVEN_ZIP) "7z" else "RAR"

    /**
     * Parse the dictionary size out of 7-Zip's method string (for example `LZMA:24`, `m3:4096k`, `v5.0:m3:128m`).
     * Returns `null` when no `<number><k|m|g>` token is present so unknown formats stay allowed.
     */
    internal fun dictionaryBytes(method: String?): Long? {
        val match = Regex("(\\d+)([kmg])(?![a-z0-9])", RegexOption.IGNORE_CASE).findAll(method ?: return null).lastOrNull() ?: return null
        val count = match.groupValues[1].toLongOrNull() ?: return null
        val shift = when (match.groupValues[2].lowercase(java.util.Locale.ROOT)) { "k" -> 10; "m" -> 20; else -> 30 }
        return if (count > (Long.MAX_VALUE shr shift)) Long.MAX_VALUE else count shl shift
    }

    /** Mirror junrar's 64 MiB mobile cap: RAR5 dictionaries can reach 4 GiB and would exhaust phone memory. */
    private fun checkRarDictionary(archive: IInArchive, index: Int) {
        val method = runCatching { archive.getStringProperty(index, PropID.METHOD) }.getOrNull()
        val bytes = dictionaryBytes(method) ?: return
        if (bytes > MAX_DICTIONARY) throw PocketError("RAR dictionary exceeds the 64 MiB mobile safety limit.")
    }

    private fun isSplit(archive: IInArchive, index: Int): Boolean =
        runCatching { archive.getProperty(index, PropID.SPLIT_BEFORE) == true || archive.getProperty(index, PropID.SPLIT_AFTER) == true }.getOrDefault(false)

    private fun mapResult(result: ExtractOperationResult, encrypted: Boolean, password: String, format: ArchiveFormat): Exception = when {
        result == ExtractOperationResult.WRONG_PASSWORD -> PasswordRequiredError(password.isNotEmpty())
        encrypted && (result == ExtractOperationResult.DATAERROR || result == ExtractOperationResult.CRCERROR) ->
            if (format == ArchiveFormat.SEVEN_ZIP) PocketError("Wrong password or damaged encrypted 7z data. Check both before retrying.")
            else PasswordRequiredError(password.isNotEmpty())
        else -> PocketError("${label(format)} extraction failed: $result")
    }

    fun extract(input: File, root: File, password: CharArray?, control: OperationControl) {
        extractNative(input, root, password, control, ArchiveFormat.SEVEN_ZIP, fallback = false)
    }

    /**
     * Native RAR4/RAR5 extraction. Returns `false` (having written nothing) when the native engine is
     * unavailable or declines to open the file without asking for a password, so the caller can fall back
     * to junrar and keep its established error mapping.
     */
    fun extractRar(input: File, root: File, password: CharArray?, control: OperationControl, rar5: Boolean): Boolean =
        nativeReady && extractNative(input, root, password, control, if (rar5) ArchiveFormat.RAR5 else ArchiveFormat.RAR, fallback = true)

    private fun extractNative(input: File, root: File, password: CharArray?, control: OperationControl, format: ArchiveFormat, fallback: Boolean): Boolean {
        val name = label(format)
        val failures = FailureState()
        val passwordText = password?.let(::String) ?: ""
        val random = RandomAccessFile(input, "r")
        val stream = RandomAccessFileInStream(random)
        var archive: IInArchive? = null
        var callback: ExtractCallback? = null
        val open = OpenCallback(passwordText, control, failures)
        try {
            control.check()
            val opened = try { SevenZip.openInArchive(format, stream, open) } catch (error: SevenZipException) {
                if (fallback && failures.failure == null && !open.requestedHeaderPassword) return false
                throw failures.failure ?: if (open.requestedHeaderPassword && passwordText.isNotEmpty()) {
                    if (format == ArchiveFormat.SEVEN_ZIP) PocketError("Wrong password or damaged 7z encrypted headers. Try the password again or check the archive.")
                    else PasswordRequiredError(true)
                } else PocketError("$name archive could not be opened: damaged, truncated or unsupported.")
            }
            archive = opened
            if (format != ArchiveFormat.SEVEN_ZIP && runCatching { opened.getArchiveProperty(PropID.IS_VOLUME) == true }.getOrDefault(false)) {
                throw PocketError("Multi-volume RAR is not supported in this version.")
            }
            val count = opened.numberOfItems
            if (count > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
            // Only metadata is scanned: no test/decrypt pass before the real extraction.
            // Ask before writing even when unencrypted entries precede encrypted entries.
            for (index in 0 until count) {
                control.check()
                if (passwordText.isEmpty() && opened.getProperty(index, PropID.ENCRYPTED) == true) throw PasswordRequiredError(false)
                val path = opened.getStringProperty(index, PropID.PATH) ?: throw PocketError("Missing $name path.")
                if (format != ArchiveFormat.SEVEN_ZIP) {
                    if (isSplit(opened, index)) throw PocketError("Multi-volume RAR is not supported in this version.")
                    checkRarDictionary(opened, index)
                }
                val size = (opened.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0L
                budget.entry(path, size)
                Safety.target(root, path)
            }
            callback = ExtractCallback(opened, root, passwordText, budget, control, failures, format)
            opened.extract(null, false, callback)
            control.check()
            return true
        } catch (error: SevenZipException) {
            throw failures.failure ?: PocketError("$name extraction failed: damaged archive, unsupported method, or storage error.")
        } finally {
            // Close handles before the repository removes partial output; do not delete its root twice.
            runCatching { callback?.close() }
            runCatching { archive?.close() }
            runCatching { stream.close() }
            runCatching { random.close() }
        }
    }

    private class ExtractCallback(
        private val archive: IInArchive,
        private val root: File,
        private val password: String,
        private val budget: ExpansionBudget,
        private val control: OperationControl,
        private val failures: FailureState,
        private val format: ArchiveFormat
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var output: OutputStream? = null
        private var target: File? = null
        private var expectedSize = 0L
        private var written = 0L
        private var encrypted = false
        private val watch = SpaceWatch(root)
        fun close() { output?.close(); output = null }
        override fun cryptoGetTextPassword(): String = failures.guard {
            control.check()
            if (password.isEmpty()) throw PasswordRequiredError(false)
            password
        }
        override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? = failures.guard {
            control.check()
            close()
            target = null
            if (mode != ExtractAskMode.EXTRACT) null else {
                val path = archive.getStringProperty(index, PropID.PATH) ?: throw PocketError("Missing ${label(format)} path.")
                val directory = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                encrypted = archive.getProperty(index, PropID.ENCRYPTED) == true
                expectedSize = (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0L
                written = 0L
                val file = Safety.target(root, path)
                if (directory) {
                    if (!file.isDirectory && !file.mkdirs()) throw PocketError("Cannot create directory.")
                    null
                } else {
                    if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw PocketError("Cannot create directory.")
                    if (file.exists()) throw PocketError("Archive path collision.")
                    // Never recreate archive links: every non-directory entry becomes an ordinary file.
                    val raw = file.outputStream().buffered(Safety.IO_BUFFER_SIZE)
                    output = raw
                    target = file
                    ISequentialOutStream { data -> failures.guard {
                        control.check()
                        if (data.size > expectedSize - written) throw PocketError("${label(format)} entry exceeds declared size.")
                        budget.add(data.size); watch.consume(data.size.toLong())
                        raw.write(data)
                        written += data.size
                        control.advance(data.size, path)
                        data.size
                    } }
                }
            }
        }
        override fun prepareOperation(mode: ExtractAskMode) = Unit
        override fun setOperationResult(result: ExtractOperationResult) = failures.guard {
            close()
            if (result != ExtractOperationResult.OK) throw mapResult(result, encrypted, password, format)
            if (target != null && target!!.length() != expectedSize) throw PocketError("${label(format)} entry size mismatch.")
            target = null
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) = failures.guard { control.check() }
    }

    /**
     * Native single-member RAR preview. 7-Zip's C++ unpacker handles solid predecessors internally
     * (they arrive as SKIP requests and nothing is written for them), which is why opening the last
     * image of a solid RAR is fast in native RAR apps. Returns `false` (having written nothing) when
     * the native engine is unavailable, declines to open the file without asking for a password, or
     * does not list `wanted`, so the caller runs the pure-Java path and keeps its error messages.
     */
    fun openRarItem(input: File, wanted: String, output: File, password: CharArray?, control: OperationControl, rar5: Boolean): Boolean {
        if (!nativeReady) return false
        val format = if (rar5) ArchiveFormat.RAR5 else ArchiveFormat.RAR
        val failures = FailureState()
        val passwordText = password?.let(::String) ?: ""
        val random = RandomAccessFile(input, "r")
        val stream = RandomAccessFileInStream(random)
        var archive: IInArchive? = null
        var callback: ItemCallback? = null
        val open = OpenCallback(passwordText, control, failures)
        try {
            control.check()
            val opened = try { SevenZip.openInArchive(format, stream, open) } catch (error: SevenZipException) {
                if (failures.failure == null && !open.requestedHeaderPassword) return false
                throw failures.failure ?: if (open.requestedHeaderPassword && passwordText.isNotEmpty()) PasswordRequiredError(true)
                else PocketError("RAR archive could not be opened: damaged, truncated or unsupported.")
            }
            archive = opened
            if (runCatching { opened.getArchiveProperty(PropID.IS_VOLUME) == true }.getOrDefault(false)) {
                throw PocketError("Multi-volume RAR is not supported in this version.")
            }
            val count = opened.numberOfItems
            if (count > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            var index = -1
            var path = ""
            for (i in 0 until count) {
                control.check()
                if (opened.getProperty(i, PropID.IS_FOLDER) == true) continue
                val candidate = opened.getStringProperty(i, PropID.PATH) ?: continue
                // 7-Zip reports OS separators; RAR headers written on Windows may still carry backslashes.
                if (runCatching { Safety.relative(candidate.replace('\\', '/')) }.getOrNull() == wanted) { index = i; path = candidate; break }
            }
            if (index < 0) return false
            val encrypted = opened.getProperty(index, PropID.ENCRYPTED) == true
            if (encrypted && passwordText.isEmpty()) throw PasswordRequiredError(false)
            if (isSplit(opened, index)) throw PocketError("Split or linked RAR entries cannot be opened.")
            checkRarDictionary(opened, index)
            val size = (opened.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: -1L
            if (size < 0) throw PocketError("RAR entry size is unknown. Extract the archive instead.")
            val budget = ExpansionBudget(input.length(), ArchiveEngine.MAX_OPEN_SIZE)
            budget.entry(path, size)
            callback = ItemCallback(index, output, size, passwordText, encrypted, budget, control, failures)
            try {
                opened.extract(intArrayOf(index), false, callback)
                control.check()
            } catch (error: SevenZipException) {
                throw failures.failure ?: PocketError("RAR entry could not be opened: damaged data, unsupported method, or storage error.")
            }
            if (output.length() != size) throw PocketError("RAR entry size mismatch.")
            return true
        } catch (error: Exception) {
            runCatching { callback?.close() }
            output.delete()
            throw error
        } finally {
            runCatching { callback?.close() }
            runCatching { archive?.close() }
            runCatching { stream.close() }
            runCatching { random.close() }
        }
    }

    private class ItemCallback(
        private val selected: Int,
        private val output: File,
        private val expectedSize: Long,
        private val password: String,
        private val encrypted: Boolean,
        private val budget: ExpansionBudget,
        private val control: OperationControl,
        private val failures: FailureState
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var sink: OutputStream? = null
        private var written = 0L
        fun close() { sink?.close(); sink = null }
        override fun cryptoGetTextPassword(): String = failures.guard {
            control.check()
            if (password.isEmpty()) throw PasswordRequiredError(false)
            password
        }
        override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? = failures.guard {
            control.check()
            // Solid predecessors are requested with SKIP mode and decoded natively; only the selected member is written.
            if (mode != ExtractAskMode.EXTRACT || index != selected) return@guard null
            close()
            val raw = ArchiveEngine.openItemOutput(output, expectedSize, control)
            sink = raw
            ISequentialOutStream { data -> failures.guard {
                control.check()
                if (data.size > expectedSize - written) throw PocketError("Archive item exceeds its declared size.")
                budget.add(data.size)
                raw.write(data)
                written += data.size
                data.size
            } }
        }
        override fun prepareOperation(mode: ExtractAskMode) = Unit
        override fun setOperationResult(result: ExtractOperationResult) = failures.guard {
            close()
            if (result != ExtractOperationResult.OK) throw mapResult(result, encrypted, password, ArchiveFormat.RAR)
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) = failures.guard { control.check() }
    }
}
