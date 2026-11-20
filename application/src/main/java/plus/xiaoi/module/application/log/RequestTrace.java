package plus.xiaoi.module.application.log;

/**
 * 单次请求的关联标识（纯 JVM）。
 *
 * <p>只覆盖同一线程内的同步调用链：`Mcp.handle` 的 `tools/call` 进入时 {@link #enter()}，
 * 退出时 {@link #exit()}；期间 `Logs` 会在正文前加 `req=<6hex>`。跨 Binder、隔线程或
 * 进程重启后的动作不会继承这个 id，避免把不相关的日志伪造成同一条链。</p>
 *
 * <p>不做嵌套栈：当前只有 MCP 一个入口使用；需要嵌套时应改成保存 previous 的栈式实现。</p>
 */
public final class RequestTrace {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<String>();
    private static long sCounter;

    private RequestTrace() {}

    /** 生成并设置当前线程的请求 id。 */
    public static String enter() {
        String id = nextId();
        CURRENT.set(id);
        return id;
    }

    /** 当前线程的请求 id；不在请求内返回 null。 */
    public static String current() {
        return CURRENT.get();
    }

    /** 清除当前线程的请求 id；必须放在 finally。 */
    public static void exit() {
        CURRENT.remove();
    }

    private static synchronized String nextId() {
        long n = ++sCounter & 0xFFFFFFL;
        String hex = Long.toHexString(n);
        while (hex.length() < 6) hex = "0" + hex;
        return hex;
    }
}
