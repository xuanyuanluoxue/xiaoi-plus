package plus.xiaoi.module.application.runtime;

/** UI 偏好端口（WP11）：表现层不直接读写 SharedPreferences。 */
public interface UiPrefs {

    /** 底栏是否使用玻璃效果；默认 false（与历史行为一致）。 */
    boolean glassBottomBar();

    void setGlassBottomBar(boolean enabled);
}
