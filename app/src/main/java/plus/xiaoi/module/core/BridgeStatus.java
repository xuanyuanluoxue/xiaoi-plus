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

        Snapshot(boolean connected, int port, String model, boolean enabled, boolean hasKey,
                 long appliedRev, long updatedAt, boolean runtimeKnown) {
            this.connected = connected;
            this.port = port > 0 ? port : 0;
            this.model = model == null ? "" : model;
            this.enabled = enabled;
            this.hasKey = hasKey;
            this.appliedRev = appliedRev < 0 ? 0L : appliedRev;
            this.updatedAt = updatedAt < 0 ? 0L : updatedAt;
            this.runtimeKnown = runtimeKnown;
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
        if (port <= 0) {
            sSnapshot = DISCONNECTED;
            return;
        }
        sSnapshot = new Snapshot(true, port, model, enabled, hasKey, appliedRev,
                updatedAt, runtimeKnown);
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
}
