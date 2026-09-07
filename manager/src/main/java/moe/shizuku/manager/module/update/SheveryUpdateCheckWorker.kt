package moe.shizuku.manager.module.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

class SheveryUpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val JITTER_MS = 10 * 60 * 1000L // 10 min
    }

    override suspend fun doWork(): Result {
        // Gate 1: run-attempt cap
        if (runAttemptCount >= MAX_ATTEMPTS) {
            return Result.failure()
        }

        // Gate 2: minimum-interval throttle (settings-respectful)
        // DAILY → 20h+10m, WEEKLY → 6d+10m, MANUAL → unthrottled (prefix-work-launches only happen if enabled)
        val frequency = moe.shizuku.manager.module.ModuleSettings.getAppUpdateFrequency()
        val minIntervalMs = when (frequency) {
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.DAILY -> TimeUnit.HOURS.toMillis(20) + JITTER_MS
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.WEEKLY -> TimeUnit.DAYS.toMillis(6) + JITTER_MS
            moe.shizuku.manager.module.ModuleSettings.UpdateFrequency.MANUAL -> 0L
        }

        val lastWall = moe.shizuku.manager.module.ModuleSettings.getAppLastCheckTime()
        val now = System.currentTimeMillis()
        if (minIntervalMs > 0 && lastWall > 0 && (now - lastWall) < minIntervalMs) {
            return Result.success() // too soon
        }

        return try {
            val result = SheveryUpdateChecker.getInstance().checkAppUpdate(applicationContext)
            if (result.error == null) {
                moe.shizuku.manager.module.ModuleSettings.setAppLastCheckTime(now)
            }

            if (result.hasUpdate && result.downloadUrl != null) {
                moe.shizuku.manager.module.ModuleSettings.setPendingUpdate(
                    version = result.latestVersion ?: "",
                    url = result.downloadUrl ?: "",
                    detectedAt = now
                )
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry() // transient network failure
        } catch (e: Exception) {
            Result.failure() // permanent (parse error, 4xx, etc.)
        }
    }
}
