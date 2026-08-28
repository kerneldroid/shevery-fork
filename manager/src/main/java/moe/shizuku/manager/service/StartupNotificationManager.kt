package moe.shizuku.manager.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.receiver.SheveryControlReceiver

object StartupNotificationManager {
    private const val CHANNEL_ID = "shevery_startup"
    private const val NOTIFICATION_ID = 1005
    private val channelCreated = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun ensureChannel(context: Context) {
        if (channelCreated.getAndSet(true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_startup_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun setup(context: Context) {
        ensureChannel(context)
    }

    fun showProgress(context: Context, message: String) {
        show(context, message, indeterminate = true, cancellable = true)
    }

    fun showStarted(context: Context, message: String) {
        show(context, message, indeterminate = false, cancellable = false)
        Handler(Looper.getMainLooper()).postDelayed({
            dismiss(context)
        }, 3000)
    }

    fun showFailed(context: Context, message: String) {
        show(context, message, indeterminate = false, cancellable = false, attemptAction = true)
    }

    private fun show(
        context: Context,
        message: String,
        indeterminate: Boolean,
        cancellable: Boolean = false,
        attemptAction: Boolean = false
    ) {
        ensureChannel(context)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wadb_24)
            .setContentTitle(context.getString(R.string.notification_startup_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setProgress(0, 0, indeterminate)

        // Add "Cancel" action during progress so user can abort the boot start.
        if (cancellable) {
            val cancelIntent = Intent(context, SheveryControlReceiver::class.java).apply {
                action = SheveryControlReceiver.ACTION_STOP_SERVER
            }
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context, 0x7F020001, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_close_24,
                context.getString(R.string.notification_startup_cancel),
                cancelPendingIntent
            )
        }

        // Add "Attempt now" action on failure so user can retry immediately.
        if (attemptAction) {
            val attemptIntent = Intent(context, SheveryControlReceiver::class.java).apply {
                action = SheveryControlReceiver.ACTION_START_SERVER
            }
            val attemptPendingIntent = PendingIntent.getBroadcast(
                context, 0x7F020002, attemptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_server_restart,
                context.getString(R.string.notification_startup_attempt_now),
                attemptPendingIntent
            )
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun dismiss(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
