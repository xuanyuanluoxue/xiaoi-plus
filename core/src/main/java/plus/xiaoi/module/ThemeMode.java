package plus.xiaoi.module;

/**
 * 主题模式的三档语义（纯 JVM，无 android 依赖，便于单测）。
 *
 * 只有 0/1/2 三个合法值；任何越界值都归一到 SYSTEM。
 * 具体如何覆写 uiMode 由 {@link ThemeManager} 负责，本类只做判定。
 */
public final class ThemeMode {

    public static final int SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private ThemeMode() {}

    public static int normalize(int mode) {
        return mode == LIGHT || mode == DARK ? mode : SYSTEM;
    }

    /** 该模式是否强制深色（SYSTEM 返回 false：由系统决定）。 */
    public static boolean forcesDark(int mode) {
        return normalize(mode) == DARK;
    }

    /** 该模式是否强制浅色。 */
    public static boolean forcesLight(int mode) {
        return normalize(mode) == LIGHT;
    }

    /** 该模式是否跟随系统。 */
    public static boolean followsSystem(int mode) {
        return normalize(mode) == SYSTEM;
    }
}
