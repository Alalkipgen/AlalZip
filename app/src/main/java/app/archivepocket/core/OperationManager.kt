package app.archivepocket.core

import android.content.Context
import app.archivepocket.data.FileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What every Archive screen and the progress notification show about the app's single operation. */
data class OperationState(
    val running: Boolean = false,
    val label: String = "",
    /** Bytes processed so far, verification reads included. */
    val progress: Long = 0,
    /** Expected bytes, or 0 while unknown. */
    val total: Long = 0,
    /** Item currently being processed. */
    val item: String = "",
    /** Name waiting for a Replace / Cancel decision. */
    val collision: String? = null,
    val cancelling: Boolean = false,
    /** Id of the most recently finished operation; it changes exactly once per finished operation. */
    val finished: Long = 0,
    /** Results that no screen has shown yet, oldest first. */
    val outcomes: List<OperationOutcome> = emptyList(),
)

/** How one operation ended: [error] is null when it completed. [target] is the folder a success notice opens. */
data class OperationOutcome(val id: Long, val label: String, val error: Exception?, val target: File?)

/**
 * Owns the one archive operation that may run at a time, for the whole app process. Screens and
 * [OperationService] only observe [state] and send commands, so pressing Back, closing a screen
 * that another app opened or swiping Archive away from Recents never stops work; only [cancel] does.
 * Interrupted work is never restarted.
 */
class OperationManager internal constructor(
    private val onStart: () -> Unit,
    startupCleanup: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutable = MutableStateFlow(OperationState())
    val state: StateFlow<OperationState> = mutable.asStateFlow()
    private var lastId = 0L
    @Volatile private var control: OperationControl? = null
    @Volatile private var answer: CompletableFuture<Boolean>? = null

    /** Leftovers of an earlier process are cleaned once per process, before any operation touches storage. */
    private val startup: Job = scope.launch {
        try { startupCleanup() } catch (e: Exception) { report("Startup cleanup", e) }
    }

    private fun report(label: String, error: Exception?) {
        synchronized(lock) {
            val id = ++lastId
            mutable.update { it.copy(finished = id, outcomes = it.outcomes + OperationOutcome(id, label, error, null)) }
        }
    }

    /**
     * Runs [action] on a background thread and returns its id, or returns null without doing
     * anything while another operation is still running. [expectedBytes] is evaluated on that thread.
     */
    fun start(label: String, target: File? = null, expectedBytes: (() -> Long)? = null, action: (OperationControl) -> Unit): Long? {
        val token = OperationControl { bytes, item -> mutable.update { it.copy(progress = bytes, item = item) } }
        val id: Long
        synchronized(lock) {
            if (mutable.value.running) return null
            id = ++lastId
            control = token
            mutable.update { it.copy(running = true, label = label, progress = 0, total = 0, item = "", collision = null, cancelling = false) }
        }
        // The foreground service keeps the process alive while Archive is in the background.
        onStart()
        scope.launch {
            var error: Exception? = null
            var completed = false
            try {
                startup.join()
                token.check()
                // Known totals turn the progress bar into a real percentage; unknown ones stay indeterminate.
                val total = expectedBytes?.let { runCatching(it).getOrDefault(0L) } ?: 0L
                if (total > 0) mutable.update { it.copy(total = total) }
                action(token)
                completed = true
            } catch (e: Exception) {
                error = e
            } finally {
                val failure = error ?: if (completed) null else PocketError("The operation stopped unexpectedly.")
                synchronized(lock) {
                    control = null
                    answer = null
                    mutable.update {
                        it.copy(
                            running = false, collision = null, cancelling = false, finished = id,
                            outcomes = it.outcomes + OperationOutcome(id, label, failure, target)
                        )
                    }
                }
            }
        }
        return id
    }

    /** Asks the running operation to stop at its next check; a pending collision question is answered "keep". */
    fun cancel() {
        synchronized(lock) {
            val token = control ?: return
            token.cancel()
            mutable.update { it.copy(cancelling = true) }
        }
        answer?.complete(false)
    }

    /** Answers the pending "Name already exists" question from any screen. */
    fun answerCollision(replace: Boolean) { answer?.complete(replace) }

    /**
     * Called on the operation's thread when [name] already exists: publishes the question and waits
     * until a screen answers it or the operation is cancelled.
     */
    fun confirm(name: String): Boolean {
        val token = control ?: return false
        val future = CompletableFuture<Boolean>()
        answer = future
        mutable.update { it.copy(collision = name) }
        try {
            while (true) {
                token.check()
                try { return future.get(200, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
            }
        } finally {
            answer = null
            mutable.update { it.copy(collision = null) }
        }
    }

    /** Removes and returns the oldest result that no screen has shown yet. */
    fun takeOutcome(): OperationOutcome? {
        while (true) {
            val current = mutable.value
            val first = current.outcomes.firstOrNull() ?: return null
            if (mutable.compareAndSet(current, current.copy(outcomes = current.outcomes.drop(1)))) return first
        }
    }

    companion object {
        @Volatile private var instance: OperationManager? = null

        /** The process-wide manager, created by the first screen that needs it. */
        fun get(context: Context): OperationManager = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): OperationManager {
            val repository = FileRepository(app)
            return OperationManager(onStart = { OperationService.start(app) }, startupCleanup = repository::cleanAbandoned)
        }
    }
}
