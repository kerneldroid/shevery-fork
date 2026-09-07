package moe.shizuku.manager.starter

import java.io.File
import moe.shizuku.manager.application
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.ShizukuStateMachine

private val app = application

object Starter {

    private val starterFile = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath

    val adbCommand = "adb shell $userCommand"

    val internalCommand = "$userCommand --apk=${app.applicationInfo.sourceDir}"

    suspend fun waitForBinder(timeoutMs: Long = 30_000L): Boolean {
        return ShizukuStateMachine.awaitRunning(timeoutMs)
    }
}
