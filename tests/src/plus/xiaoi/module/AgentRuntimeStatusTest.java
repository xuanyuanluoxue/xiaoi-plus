package plus.xiaoi.module;

import plus.xiaoi.module.agent.AgentRuntimeStatus;

/** 8770 只读快照：未知/启动/重复上报保留启动时间/停止/错误/清理。 */
public final class AgentRuntimeStatusTest {

    private AgentRuntimeStatusTest() {}

    public static int run() {
        AgentRuntimeStatus.clear();
        AgentRuntimeStatus.Snapshot empty = AgentRuntimeStatus.get();
        T.ok("空快照不运行", !empty.running);
        T.eq("空快照端口 0", 0, empty.port);
        T.eq("空快照 bindHost 未知", "", empty.bindHost);
        T.ok("空快照 lan=false（配合 updatedAt 判断未知）", !empty.lanEnabled);
        T.eq("空快照启动时间 0", 0L, empty.startedAt);
        T.eq("空快照无错误", "", empty.lastError);
        T.eq("空快照 updatedAt=0", 0L, empty.updatedAt);
        T.ok("空快照 known=false", !empty.known());
        T.ok("空快照 failed=false", !empty.failed());

        AgentRuntimeStatus.setRunning(8770, "127.0.0.1", false);
        AgentRuntimeStatus.Snapshot local = AgentRuntimeStatus.get();
        T.ok("启动后 running", local.running);
        T.eq("启动后端口", 8770, local.port);
        T.eq("启动后 bindHost", "127.0.0.1", local.bindHost);
        T.ok("启动后 lan=false", !local.lanEnabled);
        T.ok("启动后 known", local.known());
        T.ok("启动后无错误", local.lastError.length() == 0);
        T.ok("启动后 startedAt>0", local.startedAt > 0L);
        T.ok("启动后 updatedAt>=startedAt", local.updatedAt >= local.startedAt);

        long firstStart = local.startedAt;
        AgentRuntimeStatus.setRunning(8770, "127.0.0.1", false);
        T.eq("同一实例重复上报保留启动时间", firstStart,
                AgentRuntimeStatus.get().startedAt);

        AgentRuntimeStatus.setRunning(8770, "0.0.0.0", true);
        AgentRuntimeStatus.Snapshot lan = AgentRuntimeStatus.get();
        T.eq("LAN 地址", "0.0.0.0", lan.bindHost);
        T.ok("LAN 开启", lan.lanEnabled);
        T.ok("地址变化视为新启动", lan.startedAt >= firstStart);

        AgentRuntimeStatus.setError("绑定 0.0.0.0:8770 失败：BindException\n第二行");
        AgentRuntimeStatus.Snapshot failed = AgentRuntimeStatus.get();
        T.ok("错误后不运行", !failed.running);
        T.eq("错误后端口 0", 0, failed.port);
        T.eq("错误后 bindHost 未知", "", failed.bindHost);
        T.eq("错误信息保留", "绑定 0.0.0.0:8770 失败：BindException 第二行",
                failed.lastError);
        T.ok("错误后 failed=true", failed.failed());

        AgentRuntimeStatus.setStopped();
        AgentRuntimeStatus.Snapshot stopped = AgentRuntimeStatus.get();
        T.ok("停止后不运行", !stopped.running);
        T.eq("停止后端口 0", 0, stopped.port);
        T.eq("停止后清空错误", "", stopped.lastError);
        T.ok("停止后 known", stopped.known());
        T.ok("停止后 failed=false", !stopped.failed());

        AgentRuntimeStatus.setRunning(8771, "127.0.0.1", false);
        T.eq("非 8770 端口也如实记录", 8771, AgentRuntimeStatus.get().port);
        AgentRuntimeStatus.setRunning(0, "0.0.0.0", true);
        T.ok("port<=0 视为停止", !AgentRuntimeStatus.get().running);
        T.eq("port<=0 清端口", 0, AgentRuntimeStatus.get().port);

        AgentRuntimeStatus.setRunning(8770, "127.0.0.1", false);
        AgentRuntimeStatus.clear();
        T.ok("clear 回到未知", !AgentRuntimeStatus.get().known());
        T.eq("clear 后 updatedAt=0", 0L, AgentRuntimeStatus.get().updatedAt);

        return T.report("AgentRuntimeStatusTest");
    }
}
