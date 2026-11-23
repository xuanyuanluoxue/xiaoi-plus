package plus.xiaoi.module.core.mcp;

/**
 * MCP 工具归属（纯 JVM）。
 *
 * <p>0.16.0 单端口：8771（小爱进程能力桥）按这张表把归属 App 的工具经控制面
 * Binder 转发到 App 进程执行（App 进程不单独监听），客户端只配一个端口。
 *
 * <p>转发必须按「归属」判断而不是「我认不认识」——两端共用这一张表，
 * 才不会出现「转发到对端、对端又转回来」的环。
 */
public final class ToolOwnership {

    private ToolOwnership() {}

    /** 归属：小爱进程能力桥（8771）。 */
    public static final String BRIDGE = "bridge";
    /** 归属：App 进程动作面（8772）。 */
    public static final String APP = "app";

    private static final String[] BRIDGE_TOOLS = {
            "xiaoai_status",
            "xiaoai_nlp",
            "xiaoai_submit",
            "xiaoai_todo_create",
            "xiaoai_cli",
            "xiaoai_config",
            "xiaoai_island",
            "xiaoai_switch_model",
            "xiaoai_logs",
            "xiaoai_stats",
            "xiaoai_prompt",
            "xiaoai_memory",
            "xiaoai_apptools",
            "xiaoai_apptool",
            "xiaoai_alarms",
            "xiaoai_events",
            "xiaoai_transcript",
            "xiaoai_weather",
    };

    private static final String[] APP_TOOLS = {
            "xiaoai_capabilities",
            "xiaoai_action",
            "xiaoai_confirm",
            "xiaoai_audit",
            "xiaoai_notify",
            "xiaoai_ask",
    };

    /** @return {@link #BRIDGE} / {@link #APP}；未知工具返回 null */
    public static String ownerOf(String name) {
        if (name == null) return null;
        if (contains(BRIDGE_TOOLS, name)) return BRIDGE;
        if (contains(APP_TOOLS, name)) return APP;
        return null;
    }

    public static boolean isBridge(String name) {
        return BRIDGE.equals(ownerOf(name));
    }

    public static boolean isApp(String name) {
        return APP.equals(ownerOf(name));
    }

    public static String[] bridgeTools() {
        return BRIDGE_TOOLS.clone();
    }

    public static String[] appTools() {
        return APP_TOOLS.clone();
    }

    private static boolean contains(String[] table, String name) {
        for (String s : table) {
            if (s.equals(name)) return true;
        }
        return false;
    }
}
