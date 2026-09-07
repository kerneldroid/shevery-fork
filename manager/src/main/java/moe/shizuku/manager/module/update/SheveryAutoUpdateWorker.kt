package moe.shizuku.manager.module.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SheveryAutoUpdateWorker {
    private const val WORK_NAME = "shevery_auto_update_check"

    fun maybeSchedule(context: Context) {
        // Defensive: never throw from Application.onCreate / BroadcastReceiver
        try {
            schedule(context)
        } catch (e: Exception) {
            android.util.Log.e("SheveryAutoUpdate", "Failed to schedule update check", e)
        }
    }

    fun cancel(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            android.util.Log.e("SheveryAutoUpdate", "Failed to cancel update check", e)
        }
    }

    private fun schedule(context: Context) {
        if (!moe.shizuku.manager.module.ModuleSettings.isAppUpdateAutoCheckEnabled()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }

        val frequencyDays = when (moe.shizuku.manager.module.ModuleSettings.getAppUpdateFrequency()) {
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.DAILY -> 1L
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.WEEKLY -> 7L
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.MANUAL -> return // no schedule
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = PeriodicWorkRequestBuilder<SheveryUpdateCheckWorker>(frequencyDays, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()

        // KEEP: app-start re-enqueue must not reset the schedule
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            work
        )
    }
}
