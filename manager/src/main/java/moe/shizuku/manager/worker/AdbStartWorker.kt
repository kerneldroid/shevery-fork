package moe.shizuku.manager.worker

import android.app.KeyguardManager
import android.content.pm.ServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.SheveryControlReceiver
import moe.shizuku.manager.receiver.ShizukuReceiverStarter
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import java.io.EOFException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val MAX_RETRY_COUNT = 3

        fun enqueue(context: Context) {
            val cb = Constraints.Builder()
            if (EnvironmentUtils.isWifiRequired()) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "adb_start_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        const val CHANNEL_ID = "AdbStartWorker"
        const val NOTIFICATION_ID = 1448
    }

    override suspend fun doWork(): Result {
        try {
            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.RUNNING
            )

            // Promote to a foreground service so the worker survives
            // the mDNS discovery + keyguard wait on Android 12+.
            val fgNotification = ShizukuReceiverStarter.buildNotification(applicationContext, null)
            val fgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ForegroundInfo(
                    ShizukuReceiverStarter.NOTIFICATION_ID,
                    fgNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                ForegroundInfo(ShizukuReceiverStarter.NOTIFICATION_ID, fgNotification)
            }
            setForeground(fgInfo)

            val cr = applicationContext.contentResolver

            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)

            val tcpPort = EnvironmentUtils.getAdbTcpPort()

            val port = if (!EnvironmentUtils.isWifiRequired()) {
                tcpPort
            } else if (EnvironmentUtils.isTelevision()) {
                // TV devices with a configured/static TCP port use TCP directly;
                // avoid mDNS discovery which is unreliable on LEANBACK.
                if (tcpPort > 0) tcpPort else throw SecurityException("TV device requires TCP ADB port to be configured")
            } else {
                callbackFlow {
                    val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                        if (p > 0) trySend(p)
                    }

                    var awaitingAuth = false
                    var timeoutJob: Job? = null
                    var unlockReceiver: BroadcastReceiver? = null

                    fun startDiscoveryWithTimeout() {
                        adbMdns.start()
                        timeoutJob?.cancel()
                        timeoutJob = this.launch {
                            delay(15_000)
                            close(TimeoutException("Timed out during mDNS port discovery"))
                        }
                    }

                    fun handleAuth() {
                        val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        if (km.isKeyguardLocked) {
                            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                            unlockReceiver = object : BroadcastReceiver() {
                                override fun onReceive(context: Context, intent: Intent) {
                                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                                        context.unregisterReceiver(this)
                                        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                    }
                                }
                            }
                            ContextCompat.registerReceiver(
                                applicationContext,
                                unlockReceiver,
                                filter,
                                ContextCompat.RECEIVER_EXPORTED
                            )
                        } else {
                            awaitingAuth = true
                        }
                        timeoutJob?.cancel()
                        adbMdns.stop()
                    }

                    val observer = object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                                0 -> if (awaitingAuth) {
                                    close(SecurityException("Network is not authorized for wireless debugging"))
                                } else {
                                    handleAuth()
                                }
                                1 -> startDiscoveryWithTimeout()
                            }
                        }
                    }

                    Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                    val uri = Settings.Global.getUriFor("adb_wifi_enabled")
                    if (uri != null) {
                        cr.registerContentObserver(uri, false, observer)
                    }
                    startDiscoveryWithTimeout()

                    awaitClose {
                        adbMdns.stop()
                        timeoutJob?.cancel()
                        cr.unregisterContentObserver(observer)
                        unlockReceiver?.let {
                            try {
                                applicationContext.unregisterReceiver(it)
                            } catch (_: Exception) {}
                        }
                    }
                }.first()
            }

            AdbStarter.start("127.0.0.1", port, applicationContext)
            Starter.waitForBinder()

            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ShizukuReceiverStarter.NOTIFICATION_ID)

            return Result.success()
        } catch (e: CancellationException) {
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            } else {
                when (stopReason) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> ShizukuReceiverStarter.WorkerState.STOPPED
                    else -> ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
                }
            }
            ShizukuReceiverStarter.updateNotification(applicationContext, state)
            throw e
        } catch (e: Exception) {
            val ignored = listOf(
                EOFException::class,
                SecurityException::class,
                TimeoutException::class
            )
            if (ignored.none { it.isInstance(e) }) {
                showErrorNotification(applicationContext, e)
            }

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                return Result.success()
            } else {
                val attemptCount = runAttemptCount
                if (attemptCount < MAX_RETRY_COUNT) {
                    ShizukuReceiverStarter.updateNotification(
                        applicationContext,
                        ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
                    )
                    return Result.retry()
                } else {
                    ShizukuReceiverStarter.updateNotification(
                        applicationContext,
                        ShizukuReceiverStarter.WorkerState.STOPPED
                    )
                    return Result.failure()
                }
            }
        }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, SheveryControlReceiver::class.java).apply {
            action = SheveryControlReceiver.ACTION_START_SERVER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.wadb_error_title))
            .setContentText(context.getString(R.string.wadb_error_notify_dev))
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
    }
}
