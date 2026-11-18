package plus.xiaoi.module.agent;

import plus.xiaoi.module.core.AgentRuntimeSnapshot;

/**
 * 8770 动作面的进程内快照持有者（WP13 从领域层移出）。
 *
 * <p>{@code AgentService} 在启动成功/停止/绑定失败时写入；页面与
 * {@code IssueInputFactory} 只读它，不再自己扫端口或根据通知猜测运行态。</p>
 *
 * <p>快照类型 {@link AgentRuntimeSnapshot} 留在领域层（纯数据）；「最新快照」是
 * 进程状态，因此放在应用层。App 与小爱进程各自持有自己的副本，互不影响。</p>
 */
public final class AgentRuntimeStatus {

    private static final int MAX_ERROR_CHARS = 200;

    /** 未知快照：从未收到过任何启动/停止/错误事实。 */
    private static final AgentRuntimeSnapshot UNKNOWN =
            new AgentRuntimeSnapshot(false, 0, "", false, 0L, "", 0L);

    private static volatile AgentRuntimeSnapshot sSnapshot = UNKNOWN;

    private AgentRuntimeStatus() {}

    /** 启动成功：记录端口/实际绑定地址/是否 LAN。port &lt;= 0 视为停止。 */
    public static void setRunning(int port, String bindHost, boolean lanEnabled) {
        if (port <= 0) {
            setStopped();
            return;
        }
        long now = System.currentTimeMillis();
        AgentRuntimeSnapshot prev = sSnapshot;
        String host = bindHost == null ? "" : bindHost.trim();
        long startedAt = now;
        if (prev.running && prev.port == port && prev.bindHost.equals(host)) {
            // 同一实例重复上报：保留原启动时间，不伪装成"刚启动"。
            startedAt = prev.startedAt;
        }
        sSnapshot = new AgentRuntimeSnapshot(true, port, host, lanEnabled, startedAt, "", now);
    }

    /**
     * 单端口（0.16.0）：动作面不再自己监听，设备动作由 8771 的 MCP 端点提供。
     *
     * @param mcpPort  8771 实际端口（未知时传首选端口）
     * @param bindHost 8771 实际 bind 地址；未知传空串（不猜 127.0.0.1）
     * @param lan      8771 是否监听 0.0.0.0
     */
    public static void setServedBy(int mcpPort, String bindHost, boolean lan) {
        long now = System.currentTimeMillis();
        AgentRuntimeSnapshot prev = sSnapshot;
        long startedAt = prev.running ? prev.startedAt : now;
        sSnapshot = new AgentRuntimeSnapshot(true, mcpPort, bindHost, lan, startedAt, "", now);
    }

    /** 正常停止（配置关闭/服务销毁）：清掉错误，避免下次展示旧失败。 */
    public static void setStopped() {
        sSnapshot = new AgentRuntimeSnapshot(false, 0, "", false, 0L, "",
                System.currentTimeMillis());
    }

    /** 启动/绑定失败：只记录单行摘要，不把异常堆栈或令牌写进快照。 */
    public static void setError(String error) {
        sSnapshot = new AgentRuntimeSnapshot(false, 0, "", false, 0L,
                sanitize(error), System.currentTimeMillis());
    }

    /** 回到"从未收到事实"的未知快照（诊断/测试用）。 */
    public static void clear() {
        sSnapshot = UNKNOWN;
    }

    /** 当前快照；从不返回 null，调用方无需判空。 */
    public static AgentRuntimeSnapshot get() {
        return sSnapshot;
    }

    private static String sanitize(String error) {
        if (error == null) return "";
        String v = error.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > MAX_ERROR_CHARS ? v.substring(0, MAX_ERROR_CHARS) : v;
    }
}
