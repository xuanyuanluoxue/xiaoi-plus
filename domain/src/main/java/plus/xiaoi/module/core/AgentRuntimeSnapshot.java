package plus.xiaoi.module.core;

/**
 * 8770 动作面的不可变运行快照（纯 JVM，无 android 依赖）。
 *
 * <p>WP13 从 {@code AgentRuntimeStatus.Snapshot} 提出来：快照是**纯数据**，属于领域层；
 * 「谁持有最新快照」是进程状态，属于应用层（{@code application.agent.AgentRuntimeStatus}）。
 * 这样 {@code core.IssueInputFactory} 只依赖本类，不再 import {@code agent} 包，
 * 领域层两个包之间没有循环。</p>
 *
 * <p>未写入过时 {@link #updatedAt} 为 0，调用方必须按未知处理，
 * 不能把 0 当成「已停止」或「启动失败」。错误信息只保留单行短文本，绝不写入令牌/密钥。</p>
 */
public final class AgentRuntimeSnapshot {

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

    public AgentRuntimeSnapshot(boolean running, int port, String bindHost, boolean lanEnabled,
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
