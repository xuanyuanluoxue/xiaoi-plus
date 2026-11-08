package plus.xiaoi.module.agent;

/**
 * 8770 动作面的只读运行快照（纯 JVM，无 android 依赖）。
 *
 * <p>{@code AgentService} 在启动成功/停止/绑定失败时写入；页面与
 * {@code IssueInputFactory} 只读它，不再自己扫端口或根据通知猜测运行态。</p>
 *
 * <p>快照是"最近一次事实"：未写入过时 {@link Snapshot#updatedAt} 为 0，
 * 调用方必须按未知处理，不能把 0 当成"已停止"或"启动失败"。
 * 错误信息只保留单行短文本，绝不写入令牌/密钥。</p>
 */
public final class AgentRuntimeStatus {

    private static final int MAX_ERROR_CHARS = 200;

    /** 未知快照：从未收到过任何启动/停止/错误事实。 */
    private static final Snapshot UNKNOWN =
            new Snapshot(false, 0, "", false, 0L, "", 0L);

    private static volatile Snapshot sSnapshot = UNKNOWN;

    private AgentRuntimeStatus() {}

    public static final class Snapshot {
        public final boolean running;
        /** 实际监听端口；未运行时为 0。 */
        public final int port;
        /** 实际 bind 地址；未知时为空串（不猜 127.0.0.1）。 */
        public final String bindHost;
        /** 实际是否监听 0.0.0.0；未知快照下为 false，配合 {@link #updatedAt} 判断。 */
        public final boolean lanEnabled;
        /** 本次启动时间；未运行/未知为 0。 */
        public final long startedAt;
        /** 最近一次绑定失败原因；空串 = 没有错误。 */
        public final String lastError;
        /** 最近一次状态写入时间；0 = 从未写入。 */
        public final long updatedAt;

        private Snapshot(boolean running, int port, String bindHost, boolean lanEnabled,
                         long startedAt, String lastError, long updatedAt) {
            this.running = running;
            this.port = port > 0 ? port : 0;
            this.bindHost = bindHost == null ? "" : bindHost;
            this.lanEnabled = lanEnabled;
            this.startedAt = startedAt < 0 ? 0L : startedAt;
            this.lastError = lastError == null ? "" : lastError;
            this.updatedAt = updatedAt < 0 ? 0L : updatedAt;
        }

        /** 是否收到过至少一次状态事实。 */
        public boolean known() {
            return updatedAt > 0;
        }

        /** 已知停止且带错误原因；仅用于展示，不代表"当前一定失败"。 */
        public boolean failed() {
            return !running && lastError.length() > 0;
        }
    }

    /** 启动成功：记录端口/实际绑定地址/是否 LAN。port &lt;= 0 视为停止。 */
    public static void setRunning(int port, String bindHost, boolean lanEnabled) {
        if (port <= 0) {
            setStopped();
            return;
        }
        long now = System.currentTimeMillis();
        Snapshot prev = sSnapshot;
        String host = bindHost == null ? "" : bindHost.trim();
        long startedAt = now;
        if (prev.running && prev.port == port && prev.bindHost.equals(host)) {
            // 同一实例重复上报：保留原启动时间，不伪装成"刚启动"。
            startedAt = prev.startedAt;
        }
        sSnapshot = new Snapshot(true, port, host, lanEnabled, startedAt, "", now);
    }

    /** 正常停止（配置关闭/服务销毁）：清掉错误，避免下次展示旧失败。 */
    public static void setStopped() {
        sSnapshot = new Snapshot(false, 0, "", false, 0L, "", System.currentTimeMillis());
    }

    /** 启动/绑定失败：只记录单行摘要，不把异常堆栈或令牌写进快照。 */
    public static void setError(String error) {
        sSnapshot = new Snapshot(false, 0, "", false, 0L,
                sanitize(error), System.currentTimeMillis());
    }

    /** 回到"从未收到事实"的未知快照（诊断/测试用）。 */
    public static void clear() {
        sSnapshot = UNKNOWN;
    }

    /** 当前快照；从不返回 null，调用方无需判空。 */
    public static Snapshot get() {
        return sSnapshot;
    }

    private static String sanitize(String error) {
        if (error == null) return "";
        String v = error.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > MAX_ERROR_CHARS ? v.substring(0, MAX_ERROR_CHARS) : v;
    }
}
