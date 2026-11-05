package plus.xiaoi.module;

/** 主题模式判定（纯 JVM）。 */
public final class ThemeModeTest {

    private ThemeModeTest() {}

    public static int run() {
        T.eq("跟随系统归一", ThemeMode.SYSTEM, ThemeMode.normalize(-1));
        T.eq("越界归一", ThemeMode.SYSTEM, ThemeMode.normalize(9));
        T.eq("浅色归一", ThemeMode.LIGHT, ThemeMode.normalize(1));
        T.eq("深色归一", ThemeMode.DARK, ThemeMode.normalize(2));

        T.ok("深色 forcesDark", ThemeMode.forcesDark(ThemeMode.DARK));
        T.ok("浅色不 forcesDark", !ThemeMode.forcesDark(ThemeMode.LIGHT));
        T.ok("跟随系统不 forcesDark", !ThemeMode.forcesDark(ThemeMode.SYSTEM));

        T.ok("浅色 forcesLight", ThemeMode.forcesLight(ThemeMode.LIGHT));
        T.ok("深色不 forcesLight", !ThemeMode.forcesLight(ThemeMode.DARK));

        T.ok("跟随系统", ThemeMode.followsSystem(ThemeMode.SYSTEM));
        T.ok("深色不跟随系统", !ThemeMode.followsSystem(ThemeMode.DARK));

        return T.report("ThemeModeTest");
    }
}
