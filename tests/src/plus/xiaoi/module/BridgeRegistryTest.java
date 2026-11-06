package plus.xiaoi.module;

import java.util.List;

import plus.xiaoi.module.control.BridgeRegistry;
import plus.xiaoi.module.control.InstanceRecord;

/** App 侧实例表：重连覆盖、leader 选择、状态更新与容量上限。 */
public final class BridgeRegistryTest {

    private BridgeRegistryTest() {}

    public static int run() {
        BridgeRegistry r = new BridgeRegistry();

        InstanceRecord a = r.register(InstanceRecord.registered(
                "com.miui.voiceassist", 100, 0, "{\"model\":\"m\"}", 1L));
        T.eq("注册进程数", 1, r.size());
        T.ok("无端口时没有 leader", r.leader() == null);

        InstanceRecord a2 = r.register(InstanceRecord.registered(
                "com.miui.voiceassist", 101, 8771, "{}", 2L));
        T.eq("同进程重连覆盖", 1, r.size());
        T.eq("覆盖后 pid", 101, r.get(a.key()).pid);
        T.ok("端口>0 成为 leader", r.leader() != null && r.leader().port == 8771);

        // 状态先到、register 未到：补建记录
        r.reportState("com.miui.voiceassist:core", 202, "deepseek", true,
                true, "8.2.65.4714", 0, 3L);
        InstanceRecord core = r.get("com.miui.voiceassist:core");
        T.ok("reportState 补建记录", core != null);
        T.ok("状态已保存", core.hasState && "deepseek".equals(core.model) && core.hasKey);

        // WP4：reportState 的 port 是权威值，0 表示该进程已停止监听 → 清 leader
        r.reportState("com.miui.voiceassist", 101, "deepseek", true, true, "1", 0, 4L);
        T.eq("状态上报 port=0 清端口", 0,
                r.get("com.miui.voiceassist").port);
        T.ok("port=0 后没有 leader", r.leader() == null);

        // 两个 leader 时取最近更新的
        r.reportState("com.miui.voiceassist:core", 202, "m2", true, true, "1", 8772, 5L);
        T.eq("leader 取最近更新", 8772, r.leader().port);

        // 同一时间戳取端口大的
        r.reportState("com.miui.voiceassist", 101, "m3", true, true, "1", 8775, 5L);
        T.eq("同时间戳取大端口", 8775, r.leader().port);

        // 容量上限：超出后淘汰最旧
        BridgeRegistry cap = new BridgeRegistry();
        for (int i = 0; i < BridgeRegistry.MAX_INSTANCES + 1; i++) {
            cap.register(InstanceRecord.registered("p" + i, i, 0, "", 100L + i));
        }
        T.eq("实例表有上限", BridgeRegistry.MAX_INSTANCES, cap.size());
        T.ok("淘汰最旧实例", cap.get("p0") == null);
        T.ok("保留最新实例", cap.get("p" + BridgeRegistry.MAX_INSTANCES) != null);

        List<InstanceRecord> snap = cap.snapshot();
        T.eq("snapshot 条数", BridgeRegistry.MAX_INSTANCES, snap.size());

        cap.clear();
        T.eq("clear 后为空", 0, cap.size());
        return T.report("BridgeRegistryTest");
    }
}
