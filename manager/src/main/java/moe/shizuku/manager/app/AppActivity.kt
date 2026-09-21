package moe.shizuku.manager.app

import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import moe.shizuku.manager.R
import rikka.core.res.isNight
import rikka.material.app.MaterialActivity

private const val TAG = "SheverySB"

abstract class AppActivity : MaterialActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Run enableEdgeToEdge after super.onCreate: SystemBarStyle.auto's dark detection
        // reads the activity's (resolved( resources (EdgeToEdge passes view.getResources()),
        // so it must see the AppCompat-resolved night mode — running it before super.onCreate
        // can capture the stale pre-resolution configuration, leaving the bars on the old
        // icon appearance after a theme switch until the process restarts.

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isAppDark() },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isAppDark() }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    override fun computeUserThemeKey(): String {
        // Include the resolved night state: rikka keys themed state by this string,
        // and a Light/Dark/Follow switch that leaves the key unchanged can keep
        // stale theme attributes (e.g. the Light theme's opaque bar color) alive
        // across recreate() -- cleared only by process death, i.e. "restart fixes it".
        val night = if (isAppDark()) "dark" else "light"
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor() + night
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)

        // Re-assert the bar icon appearance on the decor pass: enableEdgeToEdge's auto
        // style computes once when the window is wired up, and a theme-driven recreate can
        // leave a stale legacy window flag (API≤29( — the bars keep the old icons until the
        // process restarts. This pass runs after the theme dispatch, so the activity's resources
        // are already resolved — re-write the appearance from them, which makes a Follow-System
        // switch take effect immediately on a theme change, without needing a process restart.
        if (isDecorView) {
            reassertSystemBars()
        }
    }

    /**
     * Follow-System can flip the night mode while the activity is already alive (auto-dark /
     * QS tile(, and a stopped activity isn't always recreated for uiMode — the system bar icons
     * would then stay on the old appearance while the content follows live. Re-derive them from
     * whatever configuration the content is rendering with, on every resume and the decor pass..
     */
    /**
     * Single source of truth lives in ThemeHelper.resolveAppDark(), shared with
     * the Compose content (ShizukuExpressiveTheme): explicit Light/Dark wins,
     * Follow System reads the system night state the same way in both places.
     * Two different detectors here is exactly how the bars ended up disagreeing
     * with the content after a Light/Dark to Follow System switch.
     */
    private fun isAppDark(): Boolean = ThemeHelper.resolveAppDark(this)

    private fun reassertSystemBars() {
        if (window.decorView == null) return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = !isAppDark()

        Log.i(TAG, "reassert act=" + this::class.java.simpleName +
                " nightPref=" + AppCompatDelegate.getDefaultNightMode() +
                " sysNight=" + Resources.getSystem().configuration.isNight() +
                " resNight=" + resources.configuration.isNight() +
                " lightFlag=" + light + " api=" + Build.VERSION.SDK_INT)

        // Re-assert the colors too, not just the icon flags: the activity theme
        // is always Theme.Light, whose opaque statusBarColor resurfaces on any
        // path that re-applies the theme without running enableEdgeToEdge again.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        controller.setAppearanceLightStatusBars(light)
        controller.setAppearanceLightNavigationBars(light)
        controller.setAppearanceLightStatusBars(light)
        controller.setAppearanceLightNavigationBars(light)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.i(TAG, "onConfigChanged act=" + this::class.java.simpleName + " night=" + newConfig.isNight() + " uiMode=" + newConfig.uiMode)
    }
 
    override fun onResume() {
        super.onResume()
        reassertSystemBars()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
