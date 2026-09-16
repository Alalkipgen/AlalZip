package app.archivepocket.core

import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.ISequentialInStream
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.OutItemFactory
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Native 7-Zip engine; AES and header encryption remain enabled for password-protected archives. */
object SevenZipSupport {
    /** JNI can wrap callback exceptions: retain the actual cancellation/storage/password failure. */
    private class FailureState {
        var failure: Exception? = null
            private set
        fun <T> guard(block: () -> T): T = try { block() } catch (error: Exception) {
            if (failure == null) failure = error
            throw SevenZipException("7z callback failed", error)
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

    fun extract(input: File, root: File, password: CharArray?, control: OperationControl) {
        val failures = FailureState()
        val passwordText = password?.let(::String) ?: ""
        val random = RandomAccessFile(input, "r")
        val stream = RandomAccessFileInStream(random)
        var archive: net.sf.sevenzipjbinding.IInArchive? = null
        var callback: ExtractCallback? = null
        var requestedHeaderPassword = false
        val openCallback = object : net.sf.sevenzipjbinding.IArchiveOpenCallback, ICryptoGetTextPassword {
            override fun cryptoGetTextPassword(): String = failures.guard {
                control.check()
                requestedHeaderPassword = true
                if (passwordText.isEmpty()) throw PasswordRequiredError(false)
                passwordText
            }
            override fun setTotal(files: Long?, bytes: Long?) = failures.guard { control.check() }
            override fun setCompleted(files: Long?, bytes: Long?) = failures.guard { control.check() }
        }
        try {
            control.check()
            val opened = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, stream, openCallback)
            archive = opened
            val count = opened.numberOfItems
            if (count > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
            // Only metadata is scanned: no test/decrypt pass before the real extraction.
            // Ask before writing even when unencrypted entries precede encrypted entries.
            for (index in 0 until count) {
                control.check()
                if (passwordText.isEmpty() && opened.getProperty(index, PropID.ENCRYPTED) == true) throw PasswordRequiredError(false)
                val path = opened.getStringProperty(index, PropID.PATH) ?: throw PocketError("Missing 7z path.")
                val size = (opened.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0L
                budget.entry(path, size)
                Safety.target(root, path)
            }
            callback = ExtractCallback(opened, root, passwordText, budget, control, failures)
            opened.extract(null, false, callback)
            control.check()
        } catch (error: SevenZipException) {
            throw failures.failure ?: if (requestedHeaderPassword && archive == null && passwordText.isNotEmpty()) {
                PocketError("Wrong password or damaged 7z encrypted headers. Try the password again or check the archive.")
            } else PocketError("7z extraction failed: damaged archive, unsupported method, or storage error.")
        } finally {
            // Close handles before the repository removes partial output; do not delete its root twice.
            runCatching { callback?.close() }
            runCatching { archive?.close() }
            runCatching { stream.close() }
            runCatching { random.close() }
        }
    }

    private class ExtractCallback(
        private val archive: net.sf.sevenzipjbinding.IInArchive,
        private val root: File,
        private val password: String,
        private val budget: ExpansionBudget,
        private val control: OperationControl,
        private val failures: FailureState
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
                val path = archive.getStringProperty(index, PropID.PATH) ?: throw PocketError("Missing 7z path.")
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
                    val raw = file.outputStream().buffered(Safety.IO_BUFFER_SIZE)
                    output = raw
                    target = file
                    ISequentialOutStream { data -> failures.guard {
                        control.check()
                        if (data.size > expectedSize - written) throw PocketError("7z entry exceeds declared size.")
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
            if (result != ExtractOperationResult.OK) {
                if (result.name == "WRONG_PASSWORD") throw PasswordRequiredError(password.isNotEmpty())
                if (encrypted && (result.name == "DATAERROR" || result.name == "CRCERROR")) {
                    throw PocketError("Wrong password or damaged encrypted 7z data. Check both before retrying.")
                }
                throw PocketError("7z extraction failed: $result")
            }
            if (target != null && target!!.length() != expectedSize) throw PocketError("7z entry size mismatch.")
            target = null
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) = failures.guard { control.check() }
    }
}
