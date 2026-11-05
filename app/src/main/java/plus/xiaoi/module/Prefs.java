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

    private Prefs() {}
}
