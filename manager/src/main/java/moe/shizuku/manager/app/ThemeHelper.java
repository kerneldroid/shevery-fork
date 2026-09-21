package moe.shizuku.manager.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import androidx.annotation.StyleRes;
import androidx.appcompat.app.AppCompatDelegate;

import moe.shizuku.manager.R;
import moe.shizuku.manager.ShizukuSettings;
import moe.shizuku.manager.utils.EnvironmentUtils;
import rikka.core.util.ResourceUtils;

public class ThemeHelper {

    private static final String THEME_DEFAULT = "DEFAULT";
    private static final String THEME_BLACK = "BLACK";

    public static final String KEY_LIGHT_THEME = "light_theme";
    public static final String KEY_BLACK_NIGHT_THEME = "black_night_theme";
    public static final String KEY_USE_SYSTEM_COLOR = "use_system_color";

    public static boolean isBlackNightTheme(Context context) {
        return ShizukuSettings.getPreferences().getBoolean(KEY_BLACK_NIGHT_THEME, EnvironmentUtils.isWatch(context));
    }

    public static boolean isUsingSystemColor() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ShizukuSettings.getPreferences().getBoolean(KEY_USE_SYSTEM_COLOR, true);
    }

    public static String getTheme(Context context) {
        if (isBlackNightTheme(context)
                && ResourceUtils.isNightMode(context.getResources().getConfiguration()))
            return THEME_BLACK;

        return ShizukuSettings.getPreferences().getString(KEY_LIGHT_THEME, THEME_DEFAULT);
    }

    @StyleRes
    public static int getThemeStyleRes(Context context) {
        switch (getTheme(context)) {
            case THEME_BLACK:
                return R.style.ThemeOverlay_Black;
            case THEME_DEFAULT:
            default:
                return R.style.ThemeOverlay;
        }
    }

    /**
     * Single source of truth for "is the app dark right now", shared by the
     * activity system bars (AppActivity) and the Compose content
     * (ShizukuExpressiveTheme). An explicit Light/Dark choice always wins;
     * Follow System reads the caller context's *resolved* configuration --
     * i.e. what AppCompat actually applied -- so bars and content agree by
     * construction.
     *
     * Deliberately NOT UiModeManager and NOT Resources.getSystem(): on phones
     * UiModeManager.getNightMode() reports car-dock state (almost always NO),
     * and the system config is the input to AppCompat, not its output.
     * Value 3 (legacy "follow" constant once shipped in night_mode_value) is
     * normalized to FOLLOW_SYSTEM so devices that stored it keep working.
     */
    public static boolean resolveAppDark(Context context) {
        int nightMode = ShizukuSettings.getNightMode();
        if (nightMode == AppCompatDelegate.MODE_NIGHT_YES) return true;
        if (nightMode == AppCompatDelegate.MODE_NIGHT_NO) return false;
        if (nightMode == 3) nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        Configuration config = context.getResources().getConfiguration();
        return (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
