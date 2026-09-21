package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.commandium.AiProviderRepository
import java.io.File

/**
 * Lab-only key injection. Lives in the debug source set, so release builds
 * contain no trace of it. Refuses to run unless [BuildConfig.DEBUG].
 *
 * The emulator lab pushes a pipe-separated file over ADB and fires:
 *   adb shell am broadcast -a moe.shizuku.manager.TEST_KEYS \
 *     --es file /data/local/tmp/test-providers.txt
 *
 * File format, one provider per line:
 *   Name|https://base.url/v1|model-id|api-key
 * Lines starting with # are ignored. A matching name+baseUrl updates the
 * key in place; otherwise the provider is created.
 */
class TestKeysReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_TEST_KEYS = "moe.shizuku.manager.TEST_KEYS"
        const val EXTRA_FILE = "file"
        private const val TAG = "TestKeys"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action != ACTION_TEST_KEYS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val path = intent.getStringExtra(EXTRA_FILE).orEmpty()
                if (path.isBlank()) {
                    Log.w(TAG, "missing file extra")
                    return@launch
                }
                // ADB-only gate: /data/local/tmp is writable via ADB shell,
                // not via other apps' intents.
                if (!path.startsWith("/data/local/tmp/")) {
                    Log.w(TAG, "refusing path outside /data/local/tmp")
                    return@launch
                }
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "not a file: $path")
                    return@launch
                }
                var count = 0
                file.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { line ->
                        val parts = line.split("|")
                        if (parts.size != 4) {
                            Log.w(TAG, "bad line (want name|baseUrl|model|key)")
                            return@forEach
                        }
                        val name = parts[0].trim()
                        val baseUrl = parts[1].trim()
                        val model = parts[2].trim()
                        val key = parts[3].trim()
                        if (name.isEmpty() || baseUrl.isEmpty() || model.isEmpty() || key.isEmpty()) return@forEach
                        val existing = AiProviderRepository.getProviders()
                            .firstOrNull { it.name == name && it.baseUrl == baseUrl }
                        val provider = existing ?: AiProviderRepository.add(name, baseUrl, model)
                        AiProviderRepository.setKey(provider.id, key)
                        count++
                    }
                Log.i(TAG, "injected $count providers from $path")
            } catch (e: Exception) {
                Log.w(TAG, "inject failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
