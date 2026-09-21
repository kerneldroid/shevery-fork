package moe.shizuku.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import moe.shizuku.manager.receiver.TestKeysReceiver
import moe.shizuku.manager.settings.AiManagerScreen
import moe.shizuku.manager.ui.compose.ShizukuExpressiveTheme

/**
 * Debug-only host activity for emulator test flows. Renders the real
 * AiManagerScreen (Settings' provider manager) without needing to scroll
 * Settings, and dynamically registers the TEST_KEYS receiver so API keys can
 * be injected from ADB while the activity is up. A manifest-declared receiver
 * is blocked by Android's background-execution limits even with an activity
 * resumed, so registration must be dynamic. Registered only in the debug
 * AndroidManifest; absent from release builds.
 */
class TestHostActivity : ComponentActivity() {

    private var testKeysReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        testKeysReceiver = TestKeysReceiver().also { receiver ->
            registerReceiver(
                receiver,
                IntentFilter(TestKeysReceiver.ACTION_TEST_KEYS),
                Context.RECEIVER_EXPORTED,
            )
        }
        setContent {
            ShizukuExpressiveTheme {
                BackHandler { finish() }
                AiManagerScreen(
                    onNavigateUp = { finish() },
                    onChanged = { },
                )
            }
        }
    }

    override fun onDestroy() {
        testKeysReceiver?.let { unregisterReceiver(it) }
        testKeysReceiver = null
        super.onDestroy()
    }
}