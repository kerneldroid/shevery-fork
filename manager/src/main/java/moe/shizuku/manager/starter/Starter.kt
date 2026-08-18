package moe.shizuku.manager.starter

import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.TimeoutException
import moe.shizuku.manager.application
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.ShizukuStateMachine

private val app = application

object Starter {

    private val starterFile = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    val internalCommand = "$userCommand --apk=${app.applicationInfo.sourceDir}"

    suspend fun waitForBinder(): Boolean {
        val startMs = System.currentTimeMillis()
        val timeoutMs = 60_000L
        while (System.currentTimeMillis() - startMs < timeoutMs) {
            if (rikka.shizuku.Shizuku.pingBinder()) return true
            kotlinx.coroutines.delay(250)
        }
        return false
    }
}
