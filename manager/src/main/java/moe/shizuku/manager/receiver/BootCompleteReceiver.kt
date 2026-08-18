package moe.shizuku.manager.receiver

import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val KEYGUARD_WAIT_TIMEOUT_MS = 120_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        if (ShizukuSettings.getStartOnBootAdb()
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        ) {
            val tcpPort = EnvironmentUtils.getAdbTcpPort()
            if (tcpPort > 0 && (EnvironmentUtils.isTV(context) || ShizukuSettings.isTcpMode())) {
                ShizukuReceiverStarter.start(context)
            } else if (hasLocalNetworkPermission(context)) {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isPreS = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                if (km != null && km.isKeyguardLocked && isPreS) {
                    handlePreSKeyguard(context, km)
                } else {
                    ShizukuReceiverStarter.start(context)
                }
            } else {
                Log.w(AppConstants.TAG, "Start-on-boot ADB skipped: missing local network permission")
                moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                    context,
                    context.getString(moe.shizuku.manager.R.string.notification_startup_no_permission)
                )
            }
        } else if (ShizukuSettings.getStartOnBoot()
            && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    private fun handlePreSKeyguard(context: Context, km: KeyguardManager) {
        Log.i(AppConstants.TAG, "Device locked at boot (pre-S), deferring ADB start until unlock")
        moe.shizuku.manager.service.StartupNotificationManager.showProgress(
            context,
            context.getString(moe.shizuku.manager.R.string.notification_startup_waiting_unlock)
        )

        val appContext = context.applicationContext
        val timeoutHandler = Handler(Looper.getMainLooper())
        var unlockReceiver: BroadcastReceiver? = null
        val timeoutRunnable = Runnable {
            unlockReceiver?.let { r ->
                try { appContext.unregisterReceiver(r) } catch (_: Exception) {}
            }
            moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                appContext,
                appContext.getString(moe.shizuku.manager.R.string.notification_startup_failed)
            )
            Log.w(AppConstants.TAG, "Keyguard timeout on pre-S — aborting ADB boot start")
        }

        timeoutHandler.postDelayed(timeoutRunnable, KEYGUARD_WAIT_TIMEOUT_MS)

        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    ShizukuReceiverStarter.start(ctx)
                }
            }
        }

        try {
            ContextCompat.registerReceiver(
                appContext,
                unlockReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(AppConstants.TAG, "Failed to register unlock receiver", e)
            moe.shizuku.manager.service.StartupNotificationManager.showFailed(
                context,
                context.getString(moe.shizuku.manager.R.string.notification_startup_failed)
            )
            return
        }

        // Re-check keyguard after registration: if user unlocked between
        // the isKeyguardLocked check and the registerReceiver call,
        // the USER_PRESENT broadcast may have already fired and been missed.
        if (!km.isKeyguardLocked) {
            try { appContext.unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
            timeoutHandler.removeCallbacks(timeoutRunnable)
            ShizukuReceiverStarter.start(context)
            return
        }
        // Timeout handles the case where user never unlocks
    }

    private fun hasLocalNetworkPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && context.checkSelfPermission(NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= 36
            && context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
                    != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }

    private fun rootStart(context: Context) {
        if (!com.topjohnwu.superuser.Shell.getShell().isRoot) {
            com.topjohnwu.superuser.Shell.getCachedShell()?.close()
            return
        }
        com.topjohnwu.superuser.Shell.cmd(moe.shizuku.manager.starter.Starter.internalCommand).exec()
    }
}
