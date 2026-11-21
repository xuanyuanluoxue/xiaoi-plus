package plus.xiaoi.module.core;

/**
 * 协议契约常量（纯 JVM，无 android 依赖）。
 *
 * <p>0.16.0 起，限流/上限/TTL/端口这些「文档与代码都会引用」的数字只在这里定义一次，
 * 测试、HTTP 层、工具层、文档表格都引用同一份，避免文档与实现再次漂移。
 *
 * <p>改这里的值等于改对外契约：需要同时更新 {@code docs/integration.md} 的上限表、
 * {@code tests/} 里的断言以及 CHANGELOG。
 */
public final class Contract {

    private Contract() {}

    // ------------------------------------------------------------- HTTP 线协议

    /** 单次请求体上限（字节）。超过返回 413，不解析、不猜测。 */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /** 单行（请求行/header）上限。 */
    public static final int MAX_HEADER_LINE = 8192;

    /** header 行数上限。 */
    public static final int MAX_HEADER_LINES = 128;

    // ------------------------------------------------------------- 端口

    /** 能力桥（小爱进程）的首选端口；被占用时顺延。0.16.0 起这是**唯一的** MCP 端口。 */
    public static final int BRIDGE_PORT = 8771;

    // ------------------------------------------------------------- 工具参数上限

    /** xiaoai_submit.text 上限（字符）。 */
    public static final int SUBMIT_TEXT_MAX = 4000;
    /** xiaoai_ask.text 上限（字符）。 */
    public static final int ASK_TEXT_MAX = 8000;
    /** xiaoai_ask.context 的 UTF-8 字节上限。 */
    public static final int ASK_CONTEXT_MAX_BYTES = 8 * 1024;
    /** xiaoai_notify.text 上限（字符）。 */
    public static final int NOTIFY_TEXT_MAX = 2000;
    /** 超级岛正文建议上限；超出截断并标记 truncated。 */
    public static final int NOTIFY_ISLAND_MAX = 60;
    /** 超级岛右侧大字建议上限。 */
    public static final int NOTIFY_ISLAND_TITLE_MAX = 22;

    public static final int TODO_TITLE_MAX = 200;
    public static final int TODO_CONTENT_MAX = 8192;
    public static final int MODEL_MAX = 200;

    // ------------------------------------------------------------- 提示词与记忆（P2）

    /** 系统提示词正文上限（字符）。超长在注入时截断。 */
    public static final int PROMPT_TEXT_MAX = 8192;
    /** 记忆注入默认取多少行 outline。 */
    public static final int MEMORY_INJECT_LINES_DEFAULT = 20;
    /** 记忆注入行数上限。 */
    public static final int MEMORY_INJECT_LINES_MAX = 100;

    // ------------------------------------------------------------- 查询上限

    public static final int LOGS_LIMIT_DEFAULT = 80;
    public static final int LOGS_LIMIT_MAX = 300;
    /** 进程内日志环形缓冲容量；与 {@link #LOGS_LIMIT_MAX} 分开，便于将来单独调整。 */
    public static final int LOGS_CAPACITY = 300;
    public static final int STATS_DAYS_DEFAULT = 7;
    public static final int STATS_DAYS_MAX = 90;
    public static final int AUDIT_LIMIT_DEFAULT = 50;
    public static final int AUDIT_LIMIT_MAX = 200;

    // ------------------------------------------------------------- 确认与超时

    /** 确认令牌有效期；响应里的 expiresInMs 必须等于这个值。 */
    public static final long CONFIRM_TTL_MS = 60_000L;

    public static final long ASK_TIMEOUT_DEFAULT_MS = 120_000L;
    public static final long ASK_TIMEOUT_MAX_MS = 180_000L;
}
