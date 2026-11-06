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
        T.ok("30s 内新鲜", s.fresh(1_000L + StateModel.STALE_MS));
        T.ok("超过 30s 不新鲜", !s.fresh(1_000L + StateModel.STALE_MS + 1L));

        BridgeStatus.set(8772, "", false, false, 0L, 2_000L, false);
        BridgeStatus.Snapshot registeredOnly = BridgeStatus.get();
        T.ok("register-only 仍连接", registeredOnly.connected);
        T.ok("register-only 运行态未知", !registeredOnly.runtimeKnown);
        T.eq("register-only 端口", 8772, registeredOnly.port);

        BridgeStatus.set(0, "x", true, true, 1L, 3_000L, true);
        T.ok("port=0 立即断开", !BridgeStatus.get().connected);

        BridgeStatus.set(8773, "m", true, true, 3L, 4_000L, true);
        BridgeStatus.clear();
        T.ok("clear 后断开", !BridgeStatus.get().connected);
        T.eq("clear 后端口 0", 0, BridgeStatus.get().port);
        T.ok("clear 后不新鲜", !BridgeStatus.isFresh(4_000L));

        return T.report("BridgeStatusTest");
    }
}
