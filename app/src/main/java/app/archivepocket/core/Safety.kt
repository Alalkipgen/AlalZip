package app.archivepocket.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class PocketError(message: String) : IOException(message)

/** The archive is encrypted and no usable password was supplied. */
class PasswordRequiredError(val wrongPassword: Boolean) : IOException(if (wrongPassword) "Wrong archive password." else "Archive password required.")

class OperationControl(private val progress: (Long, String) -> Unit = { _, _ -> }) {
    private val cancelled = AtomicBoolean(false)
    private var bytes = 0L
    private var lastUpdate = 0L
    fun cancel() { cancelled.set(true) }
    fun check() { if (cancelled.get() || Thread.currentThread().isInterrupted) throw PocketError("Cancelled. Completed items may remain; sources not yet moved are retained.") }
    fun advance(count: Int, label: String) {
        check()
        bytes += count
        val now = System.nanoTime()
        if (now - lastUpdate > 100_000_000L) { lastUpdate = now; progress(bytes, label) }
    }
}

object Safety {
    const val MAX_ENTRIES = 10_000
    const val MAX_DEPTH = 32
    const val RESERVE = 64L * 1024 * 1024
    const val VERIFY_LIMIT = 512L * 1024 * 1024
    const val MAX_RATIO = 1000L
    /** Bounded per-operation buffers, not one allocation per archive entry. */
    const val IO_BUFFER_SIZE = 128 * 1024
    fun expansionLimit(root: File): Long = (root.usableSpace - RESERVE).coerceAtLeast(0L)
    fun name(value: String): String {
        if (value.isBlank() || value == "." || value == ".." || value.length > 240 ||
            value.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }) {
            throw PocketError("Unsafe or unsupported file name.")
        }
        return value
    }
    fun relative(value: String): String {
        val normalized = value.replace('\\', '/').removeSuffix("/")
        val parts = normalized.split('/')
        if (parts.size > MAX_DEPTH) throw PocketError("Archive path is too deep.")
        parts.forEach(::name)
        return parts.joinToString("/")
    }
    fun target(root: File, relative: String): File {
        val file = File(root, relative(relative)).canonicalFile
        if (!file.path.startsWith(root.canonicalPath + File.separator)) throw PocketError("Archive path escapes destination.")
        return file
    }
    fun space(root: File, required: Long = 0) {
        val available = root.usableSpace
        if (required < 0 || available < RESERVE || required > available - RESERVE) {
            throw PocketError("Not enough storage. Keep at least 64 MiB free plus temporary archive data.")
        }
    }

    /** File copies keep SHA-256 verification. Archive codecs already verify CRC/MAC themselves. */
    fun transfer(input: InputStream, output: OutputStream, control: OperationControl, label: String,
                 limit: Long = Long.MAX_VALUE, spaceRoot: File? = null): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        pump(input, output, control, label, limit, spaceRoot, ByteArray(IO_BUFFER_SIZE), digest, true)
        return digest.digest()
    }

    /** No redundant hash; callers can reuse a buffer and let a bounded output own progress/space. */
    fun copyStream(input: InputStream, output: OutputStream, control: OperationControl, label: String,
                   limit: Long = Long.MAX_VALUE, spaceRoot: File? = null,
                   buffer: ByteArray = ByteArray(IO_BUFFER_SIZE), reportProgress: Boolean = true): Long =
        pump(input, output, control, label, limit, spaceRoot, buffer, null, reportProgress)

    private fun pump(input: InputStream, output: OutputStream, control: OperationControl, label: String,
                     limit: Long, spaceRoot: File?, buffer: ByteArray, digest: MessageDigest?,
                     reportProgress: Boolean): Long {
        require(buffer.isNotEmpty() && limit >= 0)
        val watch = spaceRoot?.let { SpaceWatch(it) }
        var total = 0L
        while (true) {
            control.check()
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            if (n > limit - total) throw PocketError("Safety size limit exceeded.")
            watch?.consume(n.toLong())
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            total += n
            if (reportProgress) control.advance(n, label)
        }
        return total
    }
    fun digest(input: InputStream, control: OperationControl): ByteArray =
        transfer(input, object : OutputStream() { override fun write(b: Int) {} override fun write(b: ByteArray, off: Int, len: Int) {} }, control, "Verifying copy")
}

/** Amortize filesystem queries while accounting for every byte, including a large native chunk. */
class SpaceWatch(private val root: File, private val step: Long = 32L * 1024 * 1024) {
    init { require(step > 0) }
    private var remaining = 0L
    fun consume(count: Long) {
        require(count >= 0)
        if (count == 0L) return
        if (count > remaining) {
            val available = Safety.expansionLimit(root)
            if (count > available) throw PocketError("Not enough storage. Keep at least 64 MiB free plus temporary archive data.")
            remaining = minOf(step, available)
            // If a callback is larger than the polling window, validate it in full and poll next time.
            if (count > remaining) remaining = count
        }
        remaining -= count
    }
}

/** [limit] is the number of expanded bytes this operation may still write to its destination volume. */
class ExpansionBudget(private val compressedSize: Long, private val limit: Long) {
    private var total = 0L
    private var declared = 0L
    private var entries = 0
    private val names = HashSet<String>()
    private fun outOfRoom(): PocketError = PocketError(
        "Not enough free space to expand this archive (about ${limit / (1024 * 1024)} MiB usable here). Free space and try again."
    )
    fun entry(name: String, declared: Long) {
        if (++entries > Safety.MAX_ENTRIES) throw PocketError("Too many archive entries (limit 10,000).")
        if (!names.add(java.text.Normalizer.normalize(Safety.relative(name), java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT))) throw PocketError("Duplicate/case-colliding archive paths are not supported.")
        if (declared < 0 || declared > limit - this.declared) throw outOfRoom()
        this.declared += declared
    }
    fun add(count: Int) {
        require(count >= 0)
        if (count > limit - total) throw outOfRoom()
        total += count
        if (total > 16L * 1024 * 1024 && total / compressedSize.coerceAtLeast(1) > Safety.MAX_RATIO) {
            throw PocketError("Excessive decompression ratio (over 1000:1).")
        }
    }
}
