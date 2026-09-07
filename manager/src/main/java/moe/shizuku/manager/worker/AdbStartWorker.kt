package moe.shizuku.manager.worker

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
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
import moe.shizuku.manager.AppConstants
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit

class AdbStartWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "adb_start_worker"

        fun enqueue(context: Context) {
            enqueueWithPolicy(context, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueueWithPolicy(context: Context, policy: ExistingWorkPolicy) {
            val cb = Constraints.Builder()

            // Matches the reference implementation (thedjchi/Shizuku): only
            // constrain on UNMETERED when wireless discovery actually needs
            // Wi-Fi. In TCP mode the worker runs immediately: live-port
            // reuse if adbd is up, otherwise mDNS discovery (which times
            // out fast when Wi-Fi is off and retries with backoff).
            if (EnvironmentUtils.isWifiRequired()) {
                cb.setRequiredNetworkType(NetworkType.UNMETERED)
            }
            val constraints = cb.build()

            val request = OneTimeWorkRequestBuilder<AdbStartWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30_000L, TimeUnit.MILLISECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request
            )
        }

        /**
         * Re-enqueue only if no adb_start work is already pending/running.
         * Uses KEEP so it never cancels an actively running worker (REPLACE
         * would restart it mid-discovery). Never blocks: no Future.get(),
         * safe to call from onReceive()/NetworkCallback (main thread).
         */
        fun enqueueIfIdle(context: Context) {
            enqueueWithPolicy(context, ExistingWorkPolicy.KEEP)
        }

        /** True when an unmetered, internet-capable network is available. */
        fun isUnmeteredNetworkAvailable(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager ?: return false
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            } catch (_: Exception) {
                false
            }
        }

        /** Banner state matching what will actually happen next: while Wi-Fi is
         *  the blocker show AWAITING_WIFI; otherwise RUNNING — or, for the
         *  backing-off worker, AWAITING_RETRY. */
        fun bannerStateFor(context: Context, retrying: Boolean = false): ShizukuReceiverStarter.WorkerState =
            // Matches the enqueue constraint: parked while no unmetered LAN exists.
            if (!isUnmeteredNetworkAvailable(context)) {
                ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
            } else if (retrying) {
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            } else {
                ShizukuReceiverStarter.WorkerState.RUNNING
            }
    }

    override suspend fun doWork(): Result {
        try {
            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.RUNNING
            )

            // No FGS promotion here: the reference (thedjchi/Shizuku( only foregrounds
            // to wait out an *unbounded* keyguard unlock; every wait in our fork is
            // bounded (15s discovery, 30s unlock(+ plus retries, so a background
            // startForegroundService would only throw ForegroundServiceStartNotAllowed
            // on Android  12+, looping forever. The expedited request gives a best-effort
            // execution window; process death mid-attempt is covered by Result.retry().



            val cr = applicationContext.contentResolver

            // Check WRITE_SECURE_SETTINGS before modifying secure settings
            val hasSecureSettingsPermission = applicationContext.checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
            if (hasSecureSettingsPermission) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            } else {
                Log.d(AppConstants.TAG, "WRITE_SECURE_SETTINGS not granted, skipping ADB secure settings")
            }

            val tcpPort = EnvironmentUtils.getAdbTcpPort()

            val port = if (EnvironmentUtils.isTelevision()) {
                // TV devices with a configured/static TCP port use TCP directly;
                // avoid mDNS discovery which is unreliable on LEANBACK.
                if (tcpPort > 0) tcpPort else throw SecurityException("TV device requires TCP ADB port to be configured")
            } else if (!EnvironmentUtils.isWifiRequired() && EnvironmentUtils.isAdbPortLive(tcpPort)) {

                // A configured/static TCP port that is actually live can be used directly.

                // NOTE: TCP mode alone does NOT imply the port is live: adbd's wireless
                // debugging port is random per boot,and 5555 (TCP_MODE_PORT( exists only
                // AFTER the first successful start rebinds adbd to it. When a configured port
                // is stale (e.g., fresh reboot before the service started(, fall through to mDNS
                // so the worker still discovers the live random wireless port.
                tcpPort
            } else {
                // mDNS advert can go stale when Wi-Fi drops and reconnects (the
                // Framework never re-publishes _adb-tls-connect(; adbd's TLS
                // listener usually survives on loopback though,cached last port probe
                // finds it instantly,and a wrong-service connect dies fast (
                // TLS/A_AUTH handshake(, so this fallback is safe.
                val liveTcpPort = EnvironmentUtils.getLiveAdbTcpPort()
                if (liveTcpPort >   0) liveTcpPort else callbackFlow {
                    val adbMdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { p ->
                        if (p > 0) trySend(p)
                    }

                    var awaitingAuth = false
                    var timeoutJob: Job? = null
                    var authWaitJob: Job? = null
                    var unlockReceiver: BroadcastReceiver? = null

                    fun startDiscoveryWithTimeout() {
                        adbMdns.start()
                        authWaitJob?.cancel()
                        authWaitJob = null
                        timeoutJob?.cancel()
                        timeoutJob = this.launch {
                            delay(15_000)
                            close(TimeoutException("Timed out during mDNS port discovery"))
                        }
                    }

                    fun handleAuth() {
                        val km = applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                        timeoutJob?.cancel()
                        timeoutJob = null
                        adbMdns.stop()
                        authWaitJob?.cancel()
                        authWaitJob = null
                        if (km.isKeyguardLocked) {
                            if (unlockReceiver == null) {
                                val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
                                unlockReceiver = object : BroadcastReceiver() {
                                    override fun onReceive(context: Context, intent: Intent) {
                                        if (intent.action == Intent.ACTION_USER_PRESENT) {
                                            context.unregisterReceiver(this)
                                            unlockReceiver = null
                                            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                                        }
                                    }
                                }
                                val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    ContextCompat.RECEIVER_EXPORTED
                                } else {
                                    ContextCompat.RECEIVER_NOT_EXPORTED
                                }
                                ContextCompat.registerReceiver(
                                    applicationContext,
                                    unlockReceiver,
                                    filter,
                                    receiverFlags
                                )
                            }
                            // Bound the unlock wait: an uncapped wait wedges the worker
                            // when the flag flaps during Wi-Fi bring-up. This timeout is
                            // transient, so a later retry (or the unlock) resumes us.
                            authWaitJob = this.launch {
                                delay(30_000)
                                close(TimeoutException("Timed out waiting for unlock to authorize wireless debugging"))
                            }
                        } else {
                            // System cleared adb_wifi_enabled mid-run (typical during
                            // Wi-Fi bring-up). Wait for it to restore the flag (it does
                            // once the wireless stack settles; the observer below re-arms
                            // discovery), instead of re-asserting now and racing its cleanup
                            // — a mid-flight clear would otherwise wedge us in a retry
                            // loop just when the network is coming back.

                            awaitingAuth = true
                        }
                    }

                    val observer = object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            when (Settings.Global.getInt(cr, "adb_wifi_enabled", 0)) {
                                0 -> if (awaitingAuth) {
                                    close(TimeoutException("Wireless debugging was disabled again mid-run"))
                                } else {
                                    handleAuth()
                                }
                                1 -> {
                                    awaitingAuth = false
                                    startDiscoveryWithTimeout()
                                }
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
                        authWaitJob?.cancel()
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
            if (!Starter.waitForBinder()) {
                // waitForBinder can time out while the binder actually arrived;
                // re-ping once before treating this as a failure.
                if (Shizuku.pingBinder()) {
                    ShizukuReceiverStarter.updateNotification(
                        applicationContext,
                        ShizukuReceiverStarter.WorkerState.STOPPED
                    )
                    return Result.success()
                }
                throw TimeoutException("Failed to receive binder within 30 seconds")
            }

            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.STOPPED
            )

            return Result.success()
        } catch (e: CancellationException) {
            val state = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            } else {
                when (getStopReason()) {
                    WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> ShizukuReceiverStarter.WorkerState.AWAITING_WIFI
                    WorkInfo.STOP_REASON_CANCELLED_BY_APP -> ShizukuReceiverStarter.WorkerState.STOPPED
                    else -> ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
                }
            }
            ShizukuReceiverStarter.updateNotification(applicationContext, state)
            throw e
        } catch (e: Exception) {
            // Matches the reference implementation (thedjchi/Shizuku): the
            // auto-start worker never terminally fails. Anything below a
            // running binder — adbd restarts, TLS/key flaps, mDNS misses,
            // Wi-Fi bring-up races — heals with backoff, so retry until the
            // binder is up. Failing here strands the device until the next
            // boot or a manual start.
            val ignored = listOf(
                java.io.EOFException::class,
                SecurityException::class,
                TimeoutException::class,
                javax.net.ssl.SSLException::class,
                java.net.UnknownHostException::class
            )
            if (ignored.none { it.isInstance(e) }) showErrorNotification(applicationContext, e)

            if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
                // Binder arrived during unwind — cancel any stale progress UI, then succeed.
                ShizukuReceiverStarter.updateNotification(applicationContext, ShizukuReceiverStarter.WorkerState.STOPPED)
                return Result.success()
            }
            // Device-side debugging tip: temporarily re-surface the exception here
            // (see docs/DEBUGGING_INSTRUMENTATION.md(; never ship raw exception text
            // user-facing.
            ShizukuReceiverStarter.updateNotification(
                applicationContext,
                ShizukuReceiverStarter.WorkerState.AWAITING_RETRY
            )
            return Result.retry()
        }
    }

    private fun showErrorNotification(context: Context, e: Exception) {
        // Use ShizukuReceiverStarter's channel to avoid duplicate channel creation
        ShizukuReceiverStarter.ensureChannel(context)

        val intent = Intent(context, SheveryControlReceiver::class.java).apply {
            action = SheveryControlReceiver.ACTION_START_SERVER
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0x7F010006, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, ShizukuReceiverStarter.CHANNEL_ID)
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
