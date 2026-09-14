package app.archivepocket

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.archivepocket.core.ArchivePreview
import app.archivepocket.core.OperationControl
import app.archivepocket.core.PasswordRequiredError
import app.archivepocket.core.PocketError
import app.archivepocket.data.Entry
import app.archivepocket.data.FileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An archive operation that stopped because it needs a password (RAR-style flow: try without one first,
 * then prompt). `itemPath` set = open a single member; `folderName` set = extract into that new folder;
 * neither set = extract here. `wrongPassword` = the previous attempt used a rejected password.
 */
data class PasswordRequest(
    val source: Entry, val destination: File?, val folderName: String? = null, val itemPath: String? = null, val wrongPassword: Boolean = false
)

data class PocketState(
    val granted: Boolean = false,
    val folders: List<File> = emptyList(), val entries: List<Entry> = emptyList(),
    val selected: Set<String> = emptySet(), val clipboard: List<Entry> = emptyList(), val cut: Boolean = false,
    val loading: Boolean = false, val busy: Boolean = false, val progress: Long = 0, val operation: String = "",
    val message: String? = null, val collision: String? = null,
    val previewing: Boolean = false, val archivePreview: ArchivePreview? = null, val archiveSource: Entry? = null,
    val fileToOpen: File? = null, val passwordRequest: PasswordRequest? = null,
    val free: Long = 0, val total: Long = 0
)

class PocketViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = FileRepository(app)
    private val mutable = MutableStateFlow(PocketState())
    val state = mutable.asStateFlow()
    val root: File get() = repository.root
    @Volatile private var control: OperationControl? = null
    @Volatile private var answer: CompletableFuture<Boolean>? = null
    private var listing: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.cleanAbandoned() } catch (e: Exception) { message(safeError(e)) }
        }
        checkAccess()
    }

    private fun safeError(error: Exception): String = when (error) {
        is PasswordRequiredError -> if (error.wrongPassword) "Wrong password. The archive was not extracted." else "This archive is password protected."
        is PocketError -> error.message ?: "Operation failed."
        is SecurityException -> "Storage permission denied. Allow storage access and try again."
        is net.lingala.zip4j.exception.ZipException -> "ZIP operation failed: wrong/missing password, damaged archive, or unsupported ZIP method."
        is com.github.junrar.exception.RarException -> "RAR operation failed: wrong/missing password, damaged archive, unsupported feature, or resource limit."
        else -> "Storage/archive operation failed. Check permissions, available space, password and archive integrity."
    }
    fun message(text: String?) { mutable.update { it.copy(message = text) } }

    /** True when the app may browse shared storage directly. */
    fun hasAccess(): Boolean {
        val app = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else app.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** Re-evaluates permission and starts every fresh app process at Internal storage. */
    fun checkAccess() {
        val granted = hasAccess()
        mutable.update { it.copy(granted = granted) }
        if (granted && state.value.folders.isEmpty() && !state.value.loading) {
            // Match RAR-style startup: every fresh app process begins at Internal storage.
            load(listOf(root))
        }
    }
    private fun load(folders: List<File>) {
        if (state.value.busy) return
        listing?.cancel()
        mutable.update { it.copy(loading = true, selected = emptySet()) }
        listing = viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = repository.list(folders.last())
                val (free, total) = repository.storage()
                if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                    mutable.update { it.copy(folders = folders, entries = entries, free = free, total = total) }
                }
            } catch (e: Exception) { if (e !is kotlinx.coroutines.CancellationException) message(safeError(e)) }
            finally { if (kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) mutable.update { it.copy(loading = false) } }
        }
    }
    fun refresh() { if (state.value.folders.isNotEmpty()) load(state.value.folders) else checkAccess() }
    /** Quick access: jump straight to a public folder such as Download or DCIM (must live under the storage root). */
    fun open(target: File) {
        if (state.value.loading || state.value.busy) return
        if (!target.isDirectory) { message("Folder is not available on this device: ${target.name}"); return }
        val chain = ArrayList<File>()
        var current: File? = target
        while (current != null && current.path.startsWith(root.path)) {
            chain.add(0, current)
            if (current.path == root.path) break
            current = current.parentFile
        }
        load(if (chain.isEmpty() || chain.first().path != root.path) listOf(root) else chain)
    }
    /** Opens a ZIP/RAR as a read-only list without extracting its contents. */
    fun preview(entry: Entry) {
        if (state.value.busy || state.value.loading || state.value.previewing || entry.directory) return
        mutable.update { it.copy(previewing = true, archivePreview = null, archiveSource = entry, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { mutable.update { it.copy(archivePreview = repository.preview(entry)) } }
            catch (e: Exception) { mutable.update { it.copy(archiveSource = null) }; message(safeError(e)) }
            finally { mutable.update { it.copy(previewing = false) } }
        }
    }
    fun closePreview() { mutable.update { it.copy(archivePreview = null, archiveSource = null) } }
    /** Decompresses one selected member only into private cache, then hands it to Android's viewer. */
    fun openArchiveItem(path: String, password: CharArray? = null) {
        val source = state.value.archiveSource
        if (source == null || state.value.previewing || state.value.busy) { password?.fill('\u0000'); return }
        val token = OperationControl { bytes, current -> mutable.update { it.copy(progress = bytes, operation = "Open file · $current") } }
        control = token
        mutable.update { it.copy(previewing = true, operation = "Opening archive item", progress = 0, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { mutable.update { it.copy(fileToOpen = repository.openArchiveItem(source, path, password, token)) } }
            catch (e: PasswordRequiredError) { mutable.update { it.copy(passwordRequest = PasswordRequest(source, null, itemPath = path, wrongPassword = e.wrongPassword)) } }
            catch (e: Exception) { message(safeError(e)) }
            finally { password?.fill('\u0000'); control = null; mutable.update { it.copy(previewing = false) } }
        }
    }
    /** The user typed a password for a pending request: rerun exactly that operation with it. */
    fun answerPassword(password: CharArray?) {
        val request = state.value.passwordRequest
        mutable.update { it.copy(passwordRequest = null) }
        if (request == null || password == null || password.isEmpty()) { password?.fill('\u0000'); return }
        when {
            request.itemPath != null -> {
                if (state.value.archiveSource == null) mutable.update { it.copy(archiveSource = request.source) }
                openArchiveItem(request.itemPath, password)
            }
            request.destination == null -> password.fill('\u0000')
            request.folderName != null -> runExtract(request.source, request.destination, request.folderName, password)
            else -> runExtractHere(request.source, request.destination, password)
        }
    }
    fun cancelPassword() { mutable.update { it.copy(passwordRequest = null) } }
    fun consumeOpenedFile() { mutable.update { it.copy(fileToOpen = null) } }
    fun enter(entry: Entry) { if (!state.value.loading && entry.directory) load(state.value.folders + entry.file) }
    fun back() { if (!state.value.loading && state.value.folders.size > 1) load(state.value.folders.dropLast(1)) }
    fun jumpTo(index: Int) { if (!state.value.loading && index in state.value.folders.indices) load(state.value.folders.take(index + 1)) }
    fun select(entry: Entry) { if (!state.value.busy) mutable.update { it.copy(selected = if (entry.path in it.selected) it.selected - entry.path else it.selected + entry.path) } }
    /** Long-press: make sure the pressed entry is part of the selection without toggling others off. */
    fun ensureSelected(entry: Entry) { if (!state.value.busy) mutable.update { it.copy(selected = it.selected + entry.path) } }
    fun selectAll() { mutable.update { it.copy(selected = if (it.selected.size == it.entries.size) emptySet() else it.entries.map(Entry::path).toSet()) } }
    fun clearSelection() { mutable.update { it.copy(selected = emptySet()) } }
    fun selected(): List<Entry> = state.value.entries.filter { it.path in state.value.selected }
    fun clipboard(cut: Boolean) { mutable.update { it.copy(clipboard = selected(), cut = cut, selected = emptySet()) } }
    fun clearClipboard() { mutable.update { it.copy(clipboard = emptyList()) } }
    fun cancel() { control?.cancel(); answer?.complete(false) }
    fun collisionAnswer(replace: Boolean) { answer?.complete(replace) }
    private fun confirm(name: String): Boolean {
        val future = CompletableFuture<Boolean>()
        answer = future
        mutable.update { it.copy(collision = name) }
        try {
            while (true) {
                control?.check()
                try { return future.get(200, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
        } finally { answer = null; mutable.update { it.copy(collision = null) } }
    }
    private fun execute(label: String, password: CharArray? = null, askPassword: ((Boolean) -> PasswordRequest)? = null, action: (OperationControl) -> Unit) {
        if (state.value.busy || state.value.loading) { password?.fill('\u0000'); return }
        val token = OperationControl { bytes, current -> mutable.update { it.copy(progress = bytes, operation = "$label \u00b7 $current") } }
        control = token
        mutable.update { it.copy(busy = true, operation = label, progress = 0, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { action(token); message("$label completed.") }
            catch (e: PasswordRequiredError) {
                // The repository already discarded any partial output; ask the UI for a password and let the user retry.
                if (askPassword != null) mutable.update { it.copy(passwordRequest = askPassword(e.wrongPassword)) } else message(safeError(e))
            }
            catch (e: Exception) { message(safeError(e)) }
            finally {
                password?.fill('\u0000'); control = null
                mutable.update { it.copy(busy = false, selected = emptySet()) }
                refresh()
            }
        }
    }
    fun mkdir(name: String) { val parent = state.value.folders.lastOrNull() ?: return; execute("Create folder") { repository.createFolder(parent, name) } }
    fun rename(name: String) { val entry = selected().singleOrNull() ?: return; val parent = state.value.folders.last(); execute("Rename") { repository.rename(entry, parent, name) } }
    fun delete() { val entries = selected(); execute("Delete") { repository.delete(entries, it) } }
    fun paste() {
        val current = state.value; val parent = current.folders.lastOrNull() ?: return
        execute(if (current.cut) "Move" else "Copy") {
            repository.copy(current.clipboard, parent, current.cut, it, ::confirm)
            mutable.update { state -> state.copy(clipboard = emptyList()) }
        }
    }
    /** Creates the ZIP inside [target] when given, otherwise inside the folder currently shown. */
    fun zip(name: String, password: CharArray?, target: File? = null) {
        val entries = selected(); val destination = resolveDestination(target)
        if (destination == null || entries.isEmpty()) { password?.fill('\u0000'); return }
        execute("Create ZIP", password) { repository.zip(entries, destination, name, password, it, ::confirm) }
    }
    /** Extracts the selected archive into a new sub-folder of [target], defaulting to the folder currently shown. */
    fun extract(name: String, password: CharArray?, target: File? = null) {
        val source = selected().singleOrNull(); val destination = resolveDestination(target)
        if (source == null || destination == null) { password?.fill('\u0000'); return }
        runExtract(source, destination, name, password)
    }
    /**
     * Chosen destinations must be readable folders inside shared storage; anything else falls back to
     * the folder currently shown so an operation can never write outside the browsable tree.
     */
    private fun resolveDestination(target: File?): File? {
        val current = state.value.folders.lastOrNull()
        if (target == null) return current
        val rootPath = runCatching { root.canonicalPath }.getOrElse { root.absolutePath }
        val targetPath = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
        val inside = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        if (!inside || !target.isDirectory) { message("Destination folder is not available; using the current folder."); return current }
        return target
    }
    private fun runExtract(source: Entry, destination: File, name: String, password: CharArray?) {
        execute("Extract archive", password, { wrong -> PasswordRequest(source, destination, folderName = name, wrongPassword = wrong) }) {
            repository.extract(source, destination, name, password, it, ::confirm)
        }
    }
    /** RAR-style "Extract here": archive contents land directly in the folder currently shown. */
    fun extractHere(password: CharArray?, target: File? = null) {
        val source = selected().singleOrNull(); val destination = resolveDestination(target)
        if (source == null || destination == null) { password?.fill('\u0000'); return }
        runExtractHere(source, destination, password)
    }
    private fun runExtractHere(source: Entry, destination: File, password: CharArray?) {
        execute("Extract archive", password, { wrong -> PasswordRequest(source, destination, wrongPassword = wrong) }) {
            repository.extractHere(source, destination, password, it, ::confirm)
        }
    }
    override fun onCleared() { cancel(); super.onCleared() }
}
