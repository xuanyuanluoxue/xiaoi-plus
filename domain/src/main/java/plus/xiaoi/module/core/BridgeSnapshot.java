package plus.xiaoi.module.core;

/**
 * 控制面 leader 的不可变状态快照（纯 JVM，无 android 依赖）。
 *
 * <p>WP13 从 {@code BridgeSnapshot} 提出来：快照是纯数据，属于领域层；
 * 「最新快照」是进程状态，属于应用层（{@code application.bridge.BridgeStatus}）。
 * 这样 {@code IssueInputFactory} 只依赖本类，领域层不再持有可变静态。</p>
 *
 * <p>快照是「最近一次事实」，不是「当前保证」：超过 {@link StateModel#STALE_MS}
 * 未更新时调用方必须显示等待/未确认，不能假装在线。</p>
 */
public final class BridgeSnapshot {

    public final boolean connected;
    public final int port;
    public final String model;
    public final boolean enabled;
    public final boolean hasKey;
    /** leader 已加载的配置 rev。 */
    public final long appliedRev;
    /** 最近一次 register/reportState 的时间。 */
    public final long updatedAt;
    /** true = 收到过 reportState；只有 register 时运行态仍未知。 */
    public final boolean runtimeKnown;
    /** 8771 实际 bind 地址；空串 = 未知（旧版本桥未上报，不猜 loopback）。 */
    public final String bindHost;
    /** 8771 实际是否监听 0.0.0.0；null = 未知。 */
    public final Boolean lanEnabled;
    /** 本进程首次观察到 leader 的时间；0 = 未知。 */
    public final long lastConnectedAt;
    /** 最近一次 leader 断开的时间；0 = 未知。 */
    public final long lastDisconnectedAt;

    public BridgeSnapshot(boolean connected, int port, String model, boolean enabled,
                          boolean hasKey, long appliedRev, long updatedAt,
                          boolean runtimeKnown) {
        this(connected, port, model, enabled, hasKey, appliedRev, updatedAt,
                runtimeKnown, "", null, 0L, 0L);
    }

    public BridgeSnapshot(boolean connected, int port, String model, boolean enabled,
                          boolean hasKey, long appliedRev, long updatedAt,
                          boolean runtimeKnown, String bindHost, Boolean lanEnabled,
                          long lastConnectedAt, long lastDisconnectedAt) {
        this.connected = connected;
        this.port = port > 0 ? port : 0;
        this.model = model == null ? "" : model;
        this.enabled = enabled;
        this.hasKey = hasKey;
        this.appliedRev = appliedRev < 0 ? 0L : appliedRev;
        this.updatedAt = updatedAt < 0 ? 0L : updatedAt;
        this.runtimeKnown = runtimeKnown;
        this.bindHost = bindHost == null ? "" : bindHost;
        this.lanEnabled = lanEnabled;
        this.lastConnectedAt = lastConnectedAt < 0 ? 0L : lastConnectedAt;
        this.lastDisconnectedAt = lastDisconnectedAt < 0 ? 0L : lastDisconnectedAt;
    }

    public boolean fresh(long now) {
        return connected && StateModel.fresh(updatedAt, now);
    }
}
