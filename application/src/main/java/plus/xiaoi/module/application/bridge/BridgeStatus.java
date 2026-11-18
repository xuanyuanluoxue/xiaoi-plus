package plus.xiaoi.module.application.bridge;

import plus.xiaoi.module.core.BridgeSnapshot;

/**
 * 控制面 leader 最新快照的进程内持有者（WP13 从领域层移出）。
 *
 * <p>App 进程内的 {@code BridgeControlService} 在 register/reportState 后写入这里；
 * 主页/配置页只读它来推导 D3（已应用）与 D4（运行时在用），不再扫描端口、
 * 不再经 HTTP/MCP 猜测运行态。服务销毁时 {@link #clear()}，避免把旧 leader 当在线。</p>
 */
public final class BridgeStatus {

    private static final BridgeSnapshot DISCONNECTED =
            new BridgeSnapshot(false, 0, "", false, false, 0L, 0L, false);

    private static volatile BridgeSnapshot sSnapshot = DISCONNECTED;

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
            sSnapshot = new BridgeSnapshot(false, 0, "", false, false, 0L, 0L, false,
                    "", null, lastConnectedAt, lastDisconnectedAt);
            return;
        }
        sSnapshot = new BridgeSnapshot(true, port, model, enabled, hasKey, appliedRev,
                updatedAt, runtimeKnown, bindHost, lanEnabled,
                lastConnectedAt, lastDisconnectedAt);
    }

    /** 当前快照；服务未创建/已销毁时返回 disconnected，调用方无需判空。 */
    public static BridgeSnapshot get() {
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
        sSnapshot = new BridgeSnapshot(false, 0, "", false, false, 0L, 0L, false,
                "", null, 0L, lastDisconnectedAt);
    }
}
