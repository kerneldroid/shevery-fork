package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AdbStartWorker.enqueue(context)
        // REPLACE enqueues a fresh work that runs immediately (when the
        // UNMETERED constraint allows), so the banner must mirror the actual
        // next state, not a hardcoded "awaiting retry".
        ShizukuReceiverStarter.updateNotification(context, AdbStartWorker.bannerStateFor(context))
    }
}
