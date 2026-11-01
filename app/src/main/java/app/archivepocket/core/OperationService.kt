package app.archivepocket.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.archivepocket.MainActivity
import app.archivepocket.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Archive's process in the foreground while [OperationManager] runs an operation and mirrors
 * its progress in a notification. The service owns no work and never restarts any: it observes the
 * manager, forwards the notification's Cancel action to it and stops once the operation has ended.
 */
class OperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { OperationManager.get(this) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var observer: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Archive operations", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of extracting, zipping, copying and deleting files."
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) manager.cancel()
        // Every start has to reach startForeground(), even when the operation has already ended.
        try {
            ServiceCompat.startForeground(this, ID, build(manager.state.value), dataSyncType())
        } catch (_: Exception) {
            // Android refused foreground work (for example once the daily dataSync time is used up). The
            // operation goes on while Archive is visible, it is just not protected in the background.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (observer == null) observer = scope.launch { manager.state.collect { render(it) } }
        return START_NOT_STICKY
    }

    private suspend fun render(operation: OperationState) {
        if (!operation.running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            return
        }
        notifications.notify(ID, build(operation))
        // The in-app bar updates far more often; refresh the notification about once a second, but
        // show a pending decision or a cancellation immediately.
        if (operation.collision == null && !operation.cancelling) delay(800)
    }

    private fun build(operation: OperationState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
        val collision = operation.collision
        when {
            operation.cancelling -> builder.setContentTitle("Cancelling\u2026").setProgress(100, 0, true)
            collision != null -> {
                val text = "\u201c$collision\u201d already exists. Open Archive to replace it or cancel the operation."
                builder.setContentTitle("Needs your decision").setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .addAction(0, "Cancel", cancelIntent())
            }
            else -> {
                val percent = if (operation.total > 0) ((operation.progress * 100) / operation.total).coerceIn(0, 100).toInt() else UNKNOWN
                builder.setContentTitle(operation.label.ifBlank { "Working\u2026" })
                    .setProgress(100, percent.coerceIn(0, 100), percent == UNKNOWN)
                    .addAction(0, "Cancel", cancelIntent())
                if (operation.item.isNotBlank()) builder.setContentText(operation.item).setStyle(NotificationCompat.BigTextStyle().bigText(operation.item))
                if (percent != UNKNOWN) builder.setSubText("$percent%")
            }
        }
        return builder.build()
    }

    private fun cancelIntent(): PendingIntent {
        val cancel = Intent(this, OperationService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Same as tapping the launcher icon: brings Archive's own task to the front, where questions are shown. */
    private fun openApp(): PendingIntent {
        val open = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Android 15+ gives dataSync services a limited amount of time per day. When it runs out, the
     * operation is cancelled, the user is told why, and the service stops at once instead of waiting
     * for the operation's cleanup to finish.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        val label = manager.state.value.label.ifBlank { "The operation" }
        manager.cancel()
        observer?.cancel()
        observer = null
        val text = "\u201c$label\u201d was cancelled because Android's time limit for background work ran out. " +
            "Items that were already finished may remain; open Archive to check the destination."
        val stopped = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Stopped by Android")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notifications.notify(STOPPED_ID, stopped)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Percentage value used while an operation's total size is unknown. */
        private const val UNKNOWN = -1
        private const val CHANNEL = "archive-operations"
        private const val ID = 0x41 // one ongoing operation at a time
        private const val STOPPED_ID = 0x42
        private const val ACTION_CANCEL = "app.archivepocket.action.CANCEL_OPERATION"

        private fun dataSyncType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        /** Called by [OperationManager] when an operation starts; the service then follows it until it ends. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OperationService::class.java)) }
        }
    }
}
