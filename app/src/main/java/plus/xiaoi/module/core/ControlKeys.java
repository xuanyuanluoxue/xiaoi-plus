package plus.xiaoi.module.core;

/**
 * 控制面协议常量（纯 JVM，无 android 依赖，可桌面单测）。
 *
 * 方向约定：
 *  - {@code C2S_*}：小爱进程 → App 进程的 {@code BridgeControlService}（Binder transaction）
 *  - {@code S2C_*}：App 进程 → 小爱进程的 callback（{@code Messenger}）
 *
 * 两组消息码故意不重叠，避免 callback 误收请求码。
 */
public final class ControlKeys {

    private ControlKeys() {}

    /** 控制面协议版本；不兼容时桥应降级到旧推送路径，而不是反复重连。 */
    public static final int PROTOCOL_VERSION = 1;

    /** Binder 接口描述符，首个 transaction 用它拦截非本协议的调用。 */
    public static final String DESCRIPTOR = "plus.xiaoi.module.control.IBridgeControl";

    /** 控制面服务所在包（设置 App）。 */
    public static final String SERVICE_PACKAGE = "plus.xiaoi.module";

    /** 控制面服务的显式类名（无 intent-filter，只允许 ComponentName 绑定）。 */
    public static final String SERVICE_CLASS = "plus.xiaoi.module.control.BridgeControlService";

    /** 唯一允许绑定控制面的包名。 */
    public static final String TARGET_PACKAGE = "com.miui.voiceassist";

    // ------------------------------------------------------------- C2S（请求）
    /** 拉取当前 App 配置 JSON；回复见 KEY_CONFIG_JSON / KEY_CONFIG_HASH。 */
    public static final int C2S_PULL_CONFIG = 1;
    /** 注册 callback Messenger 与进程实例信息。 */
    public static final int C2S_REGISTER = 2;
    /** 上报运行时状态（model/enabled/port/version/pid/process）。 */
    public static final int C2S_REPORT_STATE = 3;
    /** 追加一段（已由桥侧裁剪的）运行日志；只进 App 内存环形缓冲。 */
    public static final int C2S_APPEND_LOGS = 4;

    // ------------------------------------------------------------- S2C（回调）
    /** 配置可能已变化：桥重新 pull（内容相同则幂等跳过）。 */
    public static final int S2C_RELOAD = 101;
    /** 弹一次超级岛。 */
    public static final int S2C_SHOW_ISLAND = 102;
    /** 清除安全模式（WP1 尚未实现安全模式，桥收到后仅记录）。 */
    public static final int S2C_CLEAR_SAFE_MODE = 103;
    /** 刷新日志上报。 */
    public static final int S2C_FLUSH_LOGS = 104;

    // ------------------------------------------------------------- Bundle keys
    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_OK = "ok";
    public static final String KEY_ERROR = "error";
    public static final String KEY_CONFIG_JSON = "config_json";
    public static final String KEY_CONFIG_HASH = "config_hash";

    public static final String KEY_CALLBACK = "callback";
    public static final String KEY_PID = "pid";
    public static final String KEY_PROCESS = "process";
    public static final String KEY_PORT = "port";
    public static final String KEY_LEGACY_JSON = "legacy_json";

    public static final String KEY_MODEL = "model";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_HAS_KEY = "has_key";
    public static final String KEY_VERSION = "version";

    public static final String KEY_LOGS = "logs";
    public static final String KEY_TEXT = "text";
}
