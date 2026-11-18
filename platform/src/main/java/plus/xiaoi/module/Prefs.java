package plus.xiaoi.module;

/**
 * App 进程本地 SharedPreferences 的键名（唯一字面量来源）。
 *
 * 存在这里的都是「只属于这台手机上的界面偏好」：主题、日志筛选等。
 * 模型/桥配置不属于这里 —— 它们的事实源是 config.json，再由 ConfigRepository
 * 镜像到 prefs 供小爱进程读取。
 */
public final class Prefs {

    /** App 与小爱进程共用的偏好文件名（模块 prefs 镜像也用它）。 */
    public static final String NAME = "xiaoi_plus";

    /** 主题：0 跟随系统 / 1 浅色 / 2 深色（仅 App 进程使用）。 */
    public static final String KEY_THEME = "theme_mode";

    /**
     * WP1 控制面开关（模块 prefs，小爱进程用 XSharedPreferences 读取）。
     * 默认 true；设为 false 时行为与 beta.4 完全一致（只走旧 HTTP 推送）。
     * 本包没有 UI 写入口，避免在 WP1 切换主路径。
     */
    public static final String KEY_CONTROL_PLANE = "control_plane";

    /**
     * 悬浮玻璃底栏开关（仅 App 进程）。默认 true；关掉或系统关闭动画时
     * 底栏退回同尺寸实心胶囊。当前没有 UI 写入口，预留给外观页。
     */
    public static final String KEY_GLASS_BOTTOM_BAR = "ui_glass_bottom_bar";

    /** 界面偏好的独立存储：避开被污染的同名 prefs（实测读到幽灵 false）。 */
    public static final String UI_PREFS = "xiaoi_ui";

    private Prefs() {}
}
