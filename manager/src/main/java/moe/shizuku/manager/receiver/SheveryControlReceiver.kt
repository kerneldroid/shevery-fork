package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ktx.logw
import moe.shizuku.manager.service.SheveryNotificationManager
import moe.shizuku.manager.service.WatchdogManager

class SheveryControlReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_START_SERVER = "moe.shizuku.manager.action.START_SERVER"
        const val ACTION_STOP_SERVER = "moe.shizuku.manager.action.STOP_SERVER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_SERVER -> {
                WatchdogManager.clearUserStopRequest(context.applicationContext)
                WatchdogManager.attemptRestart(context.applicationContext)
            }
            ACTION_STOP_SERVER -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        WatchdogManager.stopServerAndWait(context.applicationContext, userInitiated = true)
                        SheveryNotificationManager.updateNotification(context.applicationContext)
                    } catch (e: Exception) {
                        logw("Stop server failed", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
