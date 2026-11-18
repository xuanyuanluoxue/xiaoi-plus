package plus.xiaoi.module.core;

/**
 * 控制面 leader 最近一次上报的快照（纯 JVM，无 android 依赖）。
 *
 * <p>App 进程内的 {@code BridgeControlService} 在 register/reportState 后写入这里；
 * 主页/配置页只读它来推导 D3（已应用）与 D4（运行时在用），不再扫描端口、
 * 不再经 HTTP/MCP 猜测运行态。服务销毁时 {@link #clear()}，避免把旧 leader 当在线。</p>
 *
 * <p>快照是「最近一次事实」，不是「当前保证」：超过 {@link StateModel#STALE_MS}
 * 未更新时调用方必须显示等待/未确认，不能假装在线。</p>
 */
public final class BridgeStatus {

    public static final class Snapshot {
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

        Snapshot(boolean connected, int port, String model, boolean enabled, boolean hasKey,
                 long appliedRev, long updatedAt, boolean runtimeKnown) {
            this(connected, port, model, enabled, hasKey, appliedRev, updatedAt,
                    runtimeKnown, "", null, 0L, 0L);
        }

        Snapshot(boolean connected, int port, String model, boolean enabled, boolean hasKey,
                 long appliedRev, long updatedAt, boolean runtimeKnown,
                 String bindHost, Boolean lanEnabled,
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

    private static final Snapshot DISCONNECTED =
            new Snapshot(false, 0, "", false, false, 0L, 0L, false);

    private static volatile Snapshot sSnapshot = DISCONNECTED;

    private BridgeStatus() {}

    /** 按 leader 记录更新快照；port &lt;= 0 视为没有 leader。 */
    public static void set(int port, String model, boolean enabled, boolean hasKey,
                           long appliedRev, long updatedAt, boolean runtimeKnown) {
        set(port, model, enabled, hasKey, appliedRev, updatedAt, runtimeKnown,
                "", null, 0L, 0L);
    }

    /**
     * 扩展写入：额外记录 8771 的实际 bind 地址/LAN 与连接时间。
     *
     * <p>未知字段请传空串/null/0；不要为了"看起来完整"猜 127.0.0.1 或 false。
     * port &lt;= 0 时其它运行态字段一律按断开处理，只保留断开时间。</p>
     */
    public static void set(int port, String model, boolean enabled, boolean hasKey,
                           long appliedRev, long updatedAt, boolean runtimeKnown,
                           String bindHost, Boolean lanEnabled,
                           long lastConnectedAt, long lastDisconnectedAt) {
        if (port <= 0) {
            sSnapshot = new Snapshot(false, 0, "", false, false, 0L, 0L, false,
                    "", null, lastConnectedAt, lastDisconnectedAt);
            return;
        }
        sSnapshot = new Snapshot(true, port, model, enabled, hasKey, appliedRev,
                updatedAt, runtimeKnown, bindHost, lanEnabled,
                lastConnectedAt, lastDisconnectedAt);
    }

    /** 当前快照；服务未创建/已销毁时返回 disconnected，调用方无需判空。 */
    public static Snapshot get() {
        return sSnapshot;
    }

    public static boolean isFresh(long now) {
        return sSnapshot.fresh(now);
    }

    /** 服务销毁 / 没有 leader：回到未知，绝不保留旧端口/旧运行态。 */
    public static void clear() {
        sSnapshot = DISCONNECTED;
    }

    /** 断开并保留最近断开时间；其它未知字段仍保持未知。 */
    public static void clear(long lastDisconnectedAt) {
        sSnapshot = new Snapshot(false, 0, "", false, false, 0L, 0L, false,
                "", null, 0L, lastDisconnectedAt);
    }
}
