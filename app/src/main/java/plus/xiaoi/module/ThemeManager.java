package plus.xiaoi.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * 主题的唯一入口。
 *
 * 事实源是 App prefs 的 {@link Prefs#KEY_THEME}：
 * - {@link #get(Context)} 读；
 * - {@link #set(Context, int)} 写；
 * - {@link #wrap(Context, int)} 用 uiMode 覆写资源限定符（不引 AppCompat）。
 *
 * 与 config.json 无关：主题是这台手机上的界面偏好，不随模型配置导出/推送。
 * 页面不再各自读 prefs 或自己改 theme，统一走这里。
 */
public final class ThemeManager {

    public static final int SYSTEM = ThemeMode.SYSTEM;
    public static final int LIGHT = ThemeMode.LIGHT;
    public static final int DARK = ThemeMode.DARK;

    private ThemeManager() {}

    public static int get(Context ctx) {
        try {
            return ThemeMode.normalize(ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getInt(Prefs.KEY_THEME, SYSTEM));
        } catch (Throwable t) {
            return SYSTEM;
        }
    }

    public static void set(Context ctx, int mode) {
        int m = ThemeMode.normalize(mode);
        try {
            SharedPreferences p = ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
            p.edit().putInt(Prefs.KEY_THEME, m).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 按 mode 覆写 uiMode；跟随系统或已经匹配时原样返回。 */
    public static Context wrap(Context base, int mode) {
        if (ThemeMode.followsSystem(mode)) return base;
        try {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            int night = c.uiMode & Configuration.UI_MODE_NIGHT_MASK;
            int want = ThemeMode.forcesDark(mode) ? Configuration.UI_MODE_NIGHT_YES
                    : Configuration.UI_MODE_NIGHT_NO;
            if (night == want) return base;
            c.uiMode = (c.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | want;
            Context out = base.createConfigurationContext(c);
            try {
                out.getResources().updateConfiguration(c, out.getResources().getDisplayMetrics());
            } catch (Throwable ignored) {
            }
            return out;
        } catch (Throwable t) {
            return base;
        }
    }
}
