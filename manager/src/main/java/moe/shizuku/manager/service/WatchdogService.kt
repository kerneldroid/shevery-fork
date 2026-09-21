package moe.shizuku.manager.service

import android.app.KeyguardManager
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
import kotlinx.coroutines.*
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.server.IShizukuService

class WatchdogService : Service() {

    private var errorProtectJob: Job? = null
    private var heartbeatJob: Job? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    // Second death detector: explicit heartbeat signal every 20s.
    // The 10s poll above checks binder presence/version; heartbeat validates
    // a full transaction round-trip (getVersion + getUid) with strict shape.
    private val heartbeatIntervalMs = 20_000L
    private val heartbeatTimeoutMs = 5_000L

    private val binderReceivedListener = object : rikka.shizuku.Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            startAsForeground()
        }
    }
    private val binderDeadListener = object : rikka.shizuku.Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            startAsForeground()
        }
    }

    override fun onCreate() {
        super.onCreate()
        WatchdogManager.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!WatchdogManager.shouldRunService()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startErrorProtectLoop()
        startHeartbeatLoop()
        rikka.shizuku.Shizuku.removeBinderReceivedListener(binderReceivedListener)
        rikka.shizuku.Shizuku.removeBinderDeadListener(binderDeadListener)
        rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
        return START_STICKY
    }

    override fun onDestroy() {
        rikka.shizuku.Shizuku.removeBinderReceivedListener(binderReceivedListener)
        rikka.shizuku.Shizuku.removeBinderDeadListener(binderDeadListener)
        stopErrorProtectLoop()
        serviceJob.cancel()
        super.onDestroy()
        // If we were killed while protection is still wanted, ask the system
        // to bring us back (START_STICKY covers most cases, this is a backup).
        if (WatchdogManager.shouldRunService()) {
            reconcile(applicationContext)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swipe-away from recents must not kill protection.
        if (WatchdogManager.shouldRunService()) {
            reconcile(applicationContext)
        }
    }

    private data class HealthResult(
        val healthy: Boolean,
        val reason: String,
        val binderAlive: Boolean
    )

    private fun checkHealth(): HealthResult {
        try {
            val binder = rikka.shizuku.Shizuku.getBinder()
                ?: return HealthResult(false, "binder is null", false)
            val ping = try {
                rikka.shizuku.Shizuku.pingBinder() && binder.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (!ping) {
                return HealthResult(false, "pingBinder() failed", false)
            }
            // NOTE: rikka.shizuku.Shizuku.getVersion() is CACHED after the first
            // successful call, so it can NOT detect a zombie binder whose
            // transactions already fail. Force a real transaction instead.
            val version = try {
                IShizukuService.Stub.asInterface(binder).version
            } catch (e: Throwable) {
                return HealthResult(false, "binder transaction failed: ${e.javaClass.simpleName}", true)
            }
            if (version <= 0) {
                return HealthResult(false, "bad remote version=$version", true)
            }
            return HealthResult(true, "ok version=$version", true)
        } catch (e: Throwable) {
            return HealthResult(false, "check threw ${e.javaClass.simpleName}: ${e.message}", rikka.shizuku.Shizuku.pingBinder())
        }
    }

    private fun startErrorProtectLoop() {
        errorProtectJob?.cancel()
        errorProtectJob = null
        if (!moe.shizuku.manager.module.ModuleSettings.isWatchdogEnabled()) return

        errorProtectJob = serviceScope.launch {
            var consecutiveFailures = 0
            logi("ErrorProtect: monitoring started (10s interval)")
            while (isActive) {
                if (!moe.shizuku.manager.module.ModuleSettings.isWatchdogEnabled()) break

                val result = checkHealth()
                if (result.healthy) {
                    if (consecutiveFailures > 0) {
                        logi("ErrorProtect: service recovered (${result.reason})")
                    }
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    // Visible without verbose logging: this is the whole point of ErrorProtect.
                    logw("ErrorProtect: unhealthy [$consecutiveFailures/$FAILURES_TO_RESTART]: ${result.reason}")
                    if (consecutiveFailures >= FAILURES_TO_RESTART) {
                        consecutiveFailures = 0
                        handleUnhealthy(result)
                        if (!isActive) break
                    }
                }
                delay(10_000)
            }
            logi("ErrorProtect: monitoring stopped")
        }
    }

    private suspend fun handleUnhealthy(result: HealthResult) {
        // Never auto-start a server the user never started: UNKNOWN means
        // no launch ever happened, there is nothing to "restart".
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.UNKNOWN) {
            logd("ErrorProtect: server never started (UNKNOWN mode), skipping restart")
            return
        }
        // Same guards as the event-driven path: a stop that is already being
        // handled (expected death), a user-initiated stop, or a disabled
        // watchdog must not trigger a restart from the polling loop.
        if (WatchdogManager.expectingDeath) {
            logd("ErrorProtect: death is expected, skipping restart")
            return
        }
        if (WatchdogManager.isUserStopRequested()) {
            logi("ErrorProtect: last stop was user-initiated, skipping restart")
            return
        }
        if (!WatchdogManager.shouldRunService()) {
            logd("ErrorProtect: watchdog disabled, skipping restart")
            return
        }
        withContext(Dispatchers.IO) {
            // If the binder still answers ping but transactions fail, it is a
            // zombie: ask for a graceful exit first so the restart is clean.
            // If ping already fails, the process is gone — restart directly.
            if (result.binderAlive) {
                logw("ErrorProtect: zombie binder detected (${result.reason}). Stopping before restart...")
                WatchdogManager.requestStopServer(applicationContext, userInitiated = false)
                ShizukuStateMachine.awaitStopped(3_000L)
            } else {
                logw("ErrorProtect: binder dead (${result.reason}). Restarting...")
            }
            WatchdogManager.attemptRestart(applicationContext)
            val recovered = WatchdogManager.waitForBinder(15_000L)
            if (recovered) {
                logi("ErrorProtect: Shevery service recovered after restart")
                WatchdogManager.showRecoveryNotificationIfEnabled(applicationContext)
            } else {
                logw("ErrorProtect: restart attempted but binder is still dead")
                if (moe.shizuku.manager.module.ModuleSettings.isNotifyOnServiceDeath()) {
                    WatchdogManager.showDeathNotificationPublic(applicationContext)
                }
            }
        }
    }
    private fun stopErrorProtectLoop() {
        errorProtectJob?.cancel()
        errorProtectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private sealed interface HeartbeatResult {
        data class Healthy(val version: Int, val uid: Int) : HeartbeatResult
        data class NoResponse(val detail: String) : HeartbeatResult
        data class Error(val detail: String) : HeartbeatResult
        data class NonStandard(val detail: String) : HeartbeatResult
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        if (!moe.shizuku.manager.module.ModuleSettings.isWatchdogEnabled()) return

        heartbeatJob = serviceScope.launch {
            var consecutiveFailures = 0
            logi("ErrorProtect: heartbeat started (20s interval)")
            while (isActive) {
                delay(heartbeatIntervalMs)
                if (!moe.shizuku.manager.module.ModuleSettings.isWatchdogEnabled()) break

                val result = heartbeatCheck()
                if (result is HeartbeatResult.Healthy) {
                    if (consecutiveFailures > 0) {
                        logi("ErrorProtect: heartbeat recovered (version=${result.version} uid=${result.uid})")
                    }
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures++
                val detail = when (result) {
                    is HeartbeatResult.NoResponse -> "no-response: ${result.detail}"
                    is HeartbeatResult.Error -> "error: ${result.detail}"
                    is HeartbeatResult.NonStandard -> "non-standard: ${result.detail}"
                    is HeartbeatResult.Healthy -> "ok"
                }
                logw("ErrorProtect: heartbeat failed [$consecutiveFailures]: $detail")
                handleHeartbeatFailure(detail)
                if (!isActive) break
            }
            logi("ErrorProtect: heartbeat stopped")
        }
    }

    private suspend fun heartbeatCheck(): HeartbeatResult = withContext(Dispatchers.IO) {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                return@withContext HeartbeatResult.NoResponse("pingBinder=false")
            }
            val binder = rikka.shizuku.Shizuku.getBinder()
                ?: return@withContext HeartbeatResult.NoResponse("binder=null")
            val service = IShizukuService.Stub.asInterface(binder)
            val version = withTimeoutOrNull(heartbeatTimeoutMs) {
                runInterruptible { service.getVersion() }
            } ?: return@withContext HeartbeatResult.NoResponse("getVersion timeout ${heartbeatTimeoutMs}ms")
            if (version <= 0) {
                return@withContext HeartbeatResult.NonStandard("version=$version, expected>0")
            }
            val uid = withTimeoutOrNull(heartbeatTimeoutMs) {
                runInterruptible { service.getUid() }
            } ?: return@withContext HeartbeatResult.NoResponse("getUid timeout ${heartbeatTimeoutMs}ms")
            if (uid < 0) {
                return@withContext HeartbeatResult.NonStandard("uid=$uid, expected>=0")
            }
            HeartbeatResult.Healthy(version, uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            HeartbeatResult.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun handleHeartbeatFailure(reason: String) {
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.UNKNOWN) {
            logd("ErrorProtect: heartbeat failed but server never started (UNKNOWN mode), skipping restart")
            return
        }
        if (WatchdogManager.expectingDeath) {
            logd("ErrorProtect: heartbeat failed but death is expected, skipping restart")
            return
        }
        if (WatchdogManager.isUserStopRequested()) {
            logi("ErrorProtect: heartbeat failed but last stop was user-initiated, skipping restart")
            return
        }
        if (!WatchdogManager.shouldRunService()) {
            logd("ErrorProtect: heartbeat failed but watchdog disabled, skipping restart")
            return
        }
        // Same lockscreen discipline as the 10s poll: plain-TCP adb is torn
        // down behind the keyguard, the keyguard-aware AdbStartWorker re-runs
        // full discovery + adbd rebind after USER_PRESENT itself.
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            logd("ErrorProtect: heartbeat failed but device locked -- deferring restart until unlock")
            return
        }
        withContext(Dispatchers.IO) {
            val alive = try {
                rikka.shizuku.Shizuku.pingBinder()
            } catch (e: Throwable) {
                false
            }
            if (alive) {
                logw("ErrorProtect: heartbeat zombie ($reason). Stopping before restart...")
                WatchdogManager.requestStopServer(applicationContext, userInitiated = false)
                ShizukuStateMachine.awaitStopped(3_000L)
            } else {
                logw("ErrorProtect: heartbeat dead ($reason). Restarting...")
            }
            // attemptRestart routes ROOT / ADB (incl. TCP 5555 rebind) /
            // Dhizuku from getLastLaunchMode -- no transport hardcoded here.
            WatchdogManager.attemptRestart(applicationContext)
            val recovered = WatchdogManager.waitForBinder(15_000L)
            if (recovered) {
                logi("ErrorProtect: Shevery service recovered after heartbeat restart")
                WatchdogManager.showRecoveryNotificationIfEnabled(applicationContext)
            } else {
                logw("ErrorProtect: heartbeat restart attempted but binder is still dead")
                if (moe.shizuku.manager.module.ModuleSettings.isNotifyOnServiceDeath()) {
                    WatchdogManager.showDeathNotificationPublic(applicationContext)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel(notificationManager)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            0x7F030001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_ok_24dp)
            .setContentTitle(getString(R.string.watchdog_service_title))
            .setContentText(getString(R.string.watchdog_service_text))
            .setContentIntent(launchPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel(notificationManager: NotificationManager) {
        if (channelCreated) return
        channelCreated = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_watchdog),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "service_watchdog"
        private const val NOTIFICATION_ID = 1004
        private const val FAILURES_TO_RESTART = 2
        private var channelCreated = false

        fun reconcile(context: Context) {
            val appContext = context.applicationContext
            if (WatchdogManager.shouldRunService()) {
                start(appContext)
            } else {
                stop(appContext)
            }
        }

        private fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                logd("Failed to start watchdog service: ${e.message}")
            }
        }

        private fun stop(context: Context) {
            try {
                context.stopService(Intent(context, WatchdogService::class.java))
            } catch (e: Exception) {
                logd("Failed to stop watchdog service: ${e.message}")
            }
        }
    }
}
