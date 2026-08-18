package moe.shizuku.manager.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.File

object StubManager {

    const val STUB_PACKAGE = "moe.shizuku.privileged.api"

    private const val ASSET_PATH = "shevery-stub.apk"
    private const val REMOTE_TMP_PATH = "/data/local/tmp/shevery-stub.apk"
    private const val CHANNEL_SERVER = "Shevery"
    private const val CHANNEL_ROOT = "root"
    private const val CHANNEL_ADB = "ADB"

    data class Result(val ok: Boolean, val channel: String, val error: String? = null) {
        val failed: Boolean get() = !ok
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(STUB_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    suspend fun install(context: Context): Result {
        return withContext(Dispatchers.IO) {
            val privateApk = extractTo(context.filesDir, context)
            if (privateApk == null) {
                return@withContext Result(false, "none", "failed to extract stub apk")
            }
            val apkBytes = privateApk.readBytes()

            val scripts = arrayOf(
                ShellScript(
                    CHANNEL_SERVER,
                    "cat > $REMOTE_TMP_PATH && pm install -r -d -t $REMOTE_TMP_PATH && rm -f $REMOTE_TMP_PATH"
                ) { output -> output.write(apkBytes) },
                ShellScript(
                    CHANNEL_ROOT,
                    "pm install -r -d -t '${privateApk.absolutePath}'"
                ),
                ShellScript(
                    CHANNEL_ADB,
                    "cp -f '${externalApkPath(context)}' $REMOTE_TMP_PATH && pm install -r -d -t $REMOTE_TMP_PATH && rm -f $REMOTE_TMP_PATH"
                )
            )

            var lastFailure: Result? = null
            for (script in scripts) {
                val result = when (script.channel) {
                    CHANNEL_SERVER -> runViaServer(script)
                    CHANNEL_ROOT -> runViaRoot(script)
                    CHANNEL_ADB -> runViaAdb(script)
                    else -> Result(false, script.channel, "unknown channel")
                }
                if (result.ok && pollInstalled(context, wantInstalled = true)) {
                    return@withContext Result(true, result.channel)
                }
                if (!result.ok) {
                    lastFailure = result
                }
            }
            lastFailure ?: Result(false, "none", "no channel available")
        }
    }

    suspend fun uninstall(context: Context): Result {
        return withContext(Dispatchers.IO) {
            if (!isInstalled(context)) {
                return@withContext Result(true, "none")
            }

            val script = "pm uninstall $STUB_PACKAGE"

            var lastFailure: Result? = null
            val channels = sequenceOf(CHANNEL_SERVER, CHANNEL_ROOT, CHANNEL_ADB)
            for (channel in channels) {
                val result = when (channel) {
                    CHANNEL_SERVER -> runViaServer(ShellScript(channel, script))
                    CHANNEL_ROOT -> runViaRoot(ShellScript(channel, script))
                    else -> runViaAdb(ShellScript(channel, script))
                }
                if (result.ok && pollInstalled(context, wantInstalled = false)) {
                    return@withContext Result(true, result.channel)
                }
                lastFailure = result
            }
            lastFailure ?: Result(false, "none", "no channel available")
        }
    }

    private data class ShellScript(
        val channel: String,
        val command: String,
        val stdin: ((java.io.OutputStream) -> Unit)? = null
    )

    private fun extractTo(dir: File, context: Context): File? {
        return try {
            val file = File(dir, ASSET_PATH)
            context.assets.open(ASSET_PATH).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Throwable) {
            logd("Failed to extract stub apk: ${e.message}")
            null
        }
    }

    private fun externalApkPath(context: Context): String {
        val externalDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("external storage unavailable")
        val file = File(externalDir, ASSET_PATH)
        if (!file.exists()) {
            context.assets.open(ASSET_PATH).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    private fun runViaServer(script: ShellScript): Result {
        return try {
            val binder = Shizuku.getBinder()
                ?: return Result(false, CHANNEL_SERVER, "binder is null")
            val service = IShizukuService.Stub.asInterface(binder)
            val process = service.newProcess(arrayOf("sh", "-c", script.command), null, null)
            script.stdin?.let { writer ->
                ParcelFileDescriptor.AutoCloseOutputStream(process.getOutputStream()).use { output -> writer(output) }
            }
            val finished = process.waitForTimeout(60, "SECONDS")
            if (!finished) {
                process.destroy()
                return Result(false, CHANNEL_SERVER, "command timed out")
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) Result(true, CHANNEL_SERVER)
            else Result(false, CHANNEL_SERVER, "exit code $exitCode")
        } catch (e: Throwable) {
            Result(false, CHANNEL_SERVER, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runViaRoot(script: ShellScript): Result {
        return try {
            if (!Shell.getShell().isRoot) {
                return Result(false, CHANNEL_ROOT, "not root")
            }
            val result = Shell.cmd(script.command).exec()
            if (result.isSuccess) {
                Result(true, CHANNEL_ROOT)
            } else {
                Result(false, CHANNEL_ROOT, (result.err?.firstOrNull()) ?: "command failed")
            }
        } catch (e: Throwable) {
            Result(false, CHANNEL_ROOT, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun runViaAdb(script: ShellScript): Result {
        val candidatePorts = sequenceOf(
            EnvironmentUtils.getLiveAdbTcpPort(),
            EnvironmentUtils.getAdbTcpPort(),
            5555
        )
            .filter { it > 0 }
            .distinct()
            .toList()

        if (candidatePorts.isEmpty()) {
            return Result(false, CHANNEL_ADB, "no ADB port")
        }

        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        for (port in candidatePorts) {
            try {
                val output = StringBuilder()
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.shellCommand(script.command) { data ->
                        synchronized(output) { output.append(String(data)) }
                    }
                }
                val text = output.toString()
                if (text.contains("Success")) {
                    return Result(true, CHANNEL_ADB)
                }
                return Result(false, CHANNEL_ADB, text.ifBlank { "no output on port $port" })
            } catch (e: Throwable) {
                logd("Stub command failed via ADB on port $port: ${e.message}")
            }
        }
        return Result(false, CHANNEL_ADB, "all ADB ports failed")
    }

    private suspend fun pollInstalled(context: Context, wantInstalled: Boolean, timeoutMs: Long = 5_000L): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isInstalled(context) == wantInstalled) return true
            delay(200L)
        }
        return isInstalled(context) == wantInstalled
    }
}