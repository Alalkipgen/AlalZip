package app.archivepocket.core

import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.ICryptoGetTextPassword
import net.sf.sevenzipjbinding.IOutCreateCallback
import net.sf.sevenzipjbinding.IOutItem7z
import net.sf.sevenzipjbinding.IProgress
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

/** Native 7-Zip engine. Supports normal and AES-encrypted 7z archives, including header encryption. */
object SevenZipSupport {
    private fun mapped(error: Exception, password: CharArray?): Exception = when {
        password == null || password.isEmpty() -> PasswordRequiredError(false)
        error.message?.contains("password", true) == true || error.message?.contains("data error", true) == true -> PasswordRequiredError(true)
        else -> PocketError("7z operation failed: wrong password, damaged archive, or unsupported method.")
    }

    fun create(sources: List<ArchiveSource>, output: File, password: CharArray?, control: OperationControl) {
        if (sources.size > Safety.MAX_ENTRIES) throw PocketError("Too many selected entries.")
        val outArchive = SevenZip.openOutArchive7z()
        var random: RandomAccessFile? = null
        try {
            random = RandomAccessFile(output, "rw")
            outArchive.setLevel(5)
            outArchive.setSolid(true)
            val callback: IOutCreateCallback<IOutItem7z> = if (password != null && password.isNotEmpty()) {
                outArchive.setHeaderEncryption(true)
                EncryptedCreateCallback(sources, password, control)
            } else PlainCreateCallback(sources, control)
            outArchive.createArchive(RandomAccessFileOutStream(random), sources.size, callback)
        } catch (error: SevenZipException) {
            output.delete()
            throw mapped(error, password)
        } finally {
            runCatching { outArchive.close() }
            runCatching { random?.close() }
        }
    }

    private open class PlainCreateCallback(
        private val sources: List<ArchiveSource>, private val control: OperationControl
    ) : IOutCreateCallback<IOutItem7z> {
        private var current: InputStream? = null
        private var currentPath = ""
        override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItem7z>): IOutItem7z {
            val source = sources[index]
            return factory.createOutItem().apply {
                propertyPath = Safety.relative(source.path) + if (source.directory) "/" else ""
                propertyIsDir = source.directory
                propertyIsAnti = false
                dataSize = if (source.directory) 0 else source.size
            }
        }
        override fun getStream(index: Int): ISequentialInStream? {
            val source = sources[index]
            if (source.directory) return null
            currentPath = source.path
            val stream = source.open().also { current = it }
            return object : ISequentialInStream {
                override fun read(data: ByteArray): Int {
                    control.check()
                    val count = stream.read(data)
                    if (count > 0) control.advance(count, currentPath)
                    return count.coerceAtLeast(0)
                }
                override fun close() { stream.close() }
            }
        }
        override fun setOperationResult(operationResultOk: Boolean) {
            current?.close(); current = null
            if (!operationResultOk) throw SevenZipException("7z item compression failed")
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) { control.check() }
    }

    private class EncryptedCreateCallback(
        sources: List<ArchiveSource>, private val password: CharArray, control: OperationControl
    ) : PlainCreateCallback(sources, control), ICryptoGetTextPassword {
        override fun cryptoGetTextPassword(): String = String(password)
    }

    fun extract(input: File, root: File, password: CharArray?, control: OperationControl) {
        val random = RandomAccessFile(input, "r")
        val stream = RandomAccessFileInStream(random)
        val passwordText = password?.let(::String) ?: ""
        val openCallback = object : net.sf.sevenzipjbinding.IArchiveOpenCallback, ICryptoGetTextPassword {
            override fun cryptoGetTextPassword(): String = passwordText
            override fun setTotal(files: Long?, bytes: Long?) = Unit
            override fun setCompleted(files: Long?, bytes: Long?) = Unit
        }
        var archive: net.sf.sevenzipjbinding.IInArchive? = null
        try {
            archive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, stream, openCallback)
            val count = archive.numberOfItems
            if (count > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries.")
            val budget = ExpansionBudget(input.length(), Safety.expansionLimit(root))
            val callback = ExtractCallback(archive, root, passwordText, budget, control)
            archive.extract(null, false, callback)
        } catch (error: SevenZipException) {
            root.deleteRecursively()
            throw mapped(error, password)
        } finally {
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
        private val control: OperationControl
    ) : IArchiveExtractCallback, ICryptoGetTextPassword {
        private var output: OutputStream? = null
        private var path = ""
        override fun cryptoGetTextPassword(): String = password
        override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
            control.check()
            if (mode != ExtractAskMode.EXTRACT) return null
            path = archive.getStringProperty(index, PropID.PATH) ?: throw SevenZipException("Missing 7z path")
            val directory = archive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            val size = (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: 0L
            budget.entry(path, size)
            val target = Safety.target(root, path)
            if (directory) {
                if (!target.isDirectory && !target.mkdirs()) throw SevenZipException("Cannot create directory")
                return null
            }
            if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw SevenZipException("Cannot create directory")
            if (target.exists()) throw SevenZipException("Archive path collision")
            val raw = target.outputStream().buffered()
            val watch = SpaceWatch(root)
            output = raw
            return ISequentialOutStream { data ->
                try {
                    control.check(); budget.add(data.size); watch.consume(data.size.toLong())
                    raw.write(data); control.advance(data.size, path); data.size
                } catch (error: Exception) { throw SevenZipException("7z output failed", error) }
            }
        }
        override fun prepareOperation(mode: ExtractAskMode) = Unit
        override fun setOperationResult(result: ExtractOperationResult) {
            output?.close(); output = null
            if (result != ExtractOperationResult.OK) throw SevenZipException("7z extraction result: $result")
        }
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(complete: Long) { control.check() }
    }
}
