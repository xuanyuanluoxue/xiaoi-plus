package plus.xiaoi.module;

import plus.xiaoi.module.core.BridgeStatus;
import plus.xiaoi.module.core.StateModel;

/** 控制面快照：默认断开、set/get、30s 新鲜度、rev/运行态、clear。 */
public final class BridgeStatusTest {

    private BridgeStatusTest() {}

    public static int run() {
        BridgeStatus.clear();
        BridgeStatus.Snapshot empty = BridgeStatus.get();
        T.ok("初始未连接", !empty.connected);
        T.eq("初始端口 0", 0, empty.port);
        T.ok("初始不新鲜", !empty.fresh(1_000L));
        T.eq("新增 bindHost 默认未知", "", empty.bindHost);
        T.eq("新增 lanEnabled 默认未知", null, empty.lanEnabled);
        T.eq("新增 lastConnectedAt 默认未知", 0L, empty.lastConnectedAt);
        T.eq("新增 lastDisconnectedAt 默认未知", 0L, empty.lastDisconnectedAt);

        BridgeStatus.set(8771, "deepseek-chat", true, true, 12L, 1_000L, true);
        BridgeStatus.Snapshot s = BridgeStatus.get();
        T.ok("set 后已连接", s.connected);
        T.eq("端口", 8771, s.port);
        T.eq("模型", "deepseek-chat", s.model);
        T.ok("启用", s.enabled);
        T.ok("有密钥", s.hasKey);
        T.eq("已应用 rev", 12L, s.appliedRev);
        T.eq("上报时间", 1_000L, s.updatedAt);
        T.ok("运行态已知", s.runtimeKnown);
        T.eq("旧 set 不伪造 bindHost", "", s.bindHost);
        T.eq("旧 set 不伪造 lanEnabled", null, s.lanEnabled);
        T.ok("30s 内新鲜", s.fresh(1_000L + StateModel.STALE_MS));
        T.ok("超过 30s 不新鲜", !s.fresh(1_000L + StateModel.STALE_MS + 1L));

        BridgeStatus.set(8771, "deepseek-chat", true, true, 12L, 1_500L, true,
                "0.0.0.0", Boolean.TRUE, 900L, 800L);
        BridgeStatus.Snapshot bound = BridgeStatus.get();
        T.eq("扩展 set 保留 bindHost", "0.0.0.0", bound.bindHost);
        T.eq("扩展 set 保留 lanEnabled", Boolean.TRUE, bound.lanEnabled);
        T.eq("扩展 set 保留连接时间", 900L, bound.lastConnectedAt);
        T.eq("扩展 set 保留断开时间", 800L, bound.lastDisconnectedAt);

        BridgeStatus.set(8771, "deepseek-chat", true, true, 12L, 1_600L, true,
                "127.0.0.1", Boolean.FALSE, 900L, 0L);
        BridgeStatus.Snapshot loopback = BridgeStatus.get();
        T.eq("弱令牌回退 loopback", "127.0.0.1", loopback.bindHost);
        T.eq("弱令牌 LAN=false", Boolean.FALSE, loopback.lanEnabled);

        BridgeStatus.set(8772, "", false, false, 0L, 2_000L, false);
        BridgeStatus.Snapshot registeredOnly = BridgeStatus.get();
        T.ok("register-only 仍连接", registeredOnly.connected);
        T.ok("register-only 运行态未知", !registeredOnly.runtimeKnown);
        T.eq("register-only 端口", 8772, registeredOnly.port);

        BridgeStatus.set(0, "x", true, true, 1L, 3_000L, true);
        T.ok("port=0 立即断开", !BridgeStatus.get().connected);
        T.eq("断开后 bindHost 未知", "", BridgeStatus.get().bindHost);
        T.eq("断开后 lanEnabled 未知", null, BridgeStatus.get().lanEnabled);

        BridgeStatus.set(8773, "m", true, true, 3L, 4_000L, true);
        BridgeStatus.clear();
        T.ok("clear 后断开", !BridgeStatus.get().connected);
        T.eq("clear 后端口 0", 0, BridgeStatus.get().port);
        T.ok("clear 后不新鲜", !BridgeStatus.isFresh(4_000L));
        T.eq("clear 后 bindHost 未知", "", BridgeStatus.get().bindHost);
        T.eq("clear 后 lanEnabled 未知", null, BridgeStatus.get().lanEnabled);

        BridgeStatus.clear(9_999L);
        T.ok("clear(at) 后断开", !BridgeStatus.get().connected);
        T.eq("clear(at) 保留断开时间", 9_999L, BridgeStatus.get().lastDisconnectedAt);
        T.eq("clear(at) 不伪造连接时间", 0L, BridgeStatus.get().lastConnectedAt);

        return T.report("BridgeStatusTest");
    }
}
