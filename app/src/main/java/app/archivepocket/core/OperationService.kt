package app.archivepocket.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.archivepocket.R

/**
 * Keeps a long archive operation running while Archive is in the background and shows its progress.
 * The work itself stays in the ViewModel; this service only owns the foreground notification and
 * forwards its Cancel action to [OperationService.cancelRequest].
 */
class OperationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Archive operations", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of extracting, zipping, copying and deleting files."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_CANCEL -> {
                cancelRequest?.invoke()
                startForeground(ID, build("Cancelling\u2026", "", UNKNOWN, cancellable = false))
            }
            else -> startForeground(
                ID,
                build(
                    intent?.getStringExtra(EXTRA_LABEL) ?: "Working\u2026",
                    intent?.getStringExtra(EXTRA_ITEM).orEmpty(),
                    intent?.getIntExtra(EXTRA_PERCENT, UNKNOWN) ?: UNKNOWN,
                    cancellable = true
                )
            )
        }
        return START_NOT_STICKY
    }

    private fun build(label: String, item: String, percent: Int, cancellable: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(label)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent.coerceIn(0, 100), percent == UNKNOWN)
        if (item.isNotBlank()) builder.setContentText(item).setStyle(NotificationCompat.BigTextStyle().bigText(item))
        if (percent != UNKNOWN) builder.setSubText("$percent%")
        if (cancellable) {
            val cancel = Intent(this, OperationService::class.java).setAction(ACTION_CANCEL)
            builder.addAction(
                0, "Cancel",
                PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        return builder.build()
    }

    companion object {
        /** Percentage value used while an operation's total size is unknown. */
        const val UNKNOWN = -1
        private const val CHANNEL = "archive-operations"
        private const val ID = 0x41 // one ongoing operation at a time
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_PERCENT = "percent"
        private const val ACTION_CANCEL = "app.archivepocket.action.CANCEL_OPERATION"
        private const val ACTION_STOP = "app.archivepocket.action.STOP_OPERATION"

        /** Set by the ViewModel so the notification's Cancel action can stop the running operation. */
        @Volatile
        var cancelRequest: (() -> Unit)? = null

        /** Starts or refreshes the ongoing notification. Safe to call repeatedly while working. */
        fun update(context: Context, label: String, item: String, percent: Int) {
            val intent = Intent(context, OperationService::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ITEM, item)
                .putExtra(EXTRA_PERCENT, percent)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OperationService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
