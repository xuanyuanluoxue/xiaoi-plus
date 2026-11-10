package plus.xiaoi.module;

import plus.xiaoi.module.core.StateModel;

/** 四维状态模型：D1~D4 规则、组合、文案与「只有 D4 能叫生效」。 */
public final class StateModelTest {

    private StateModelTest() {}

    private static StateModel.Input ready() {
        StateModel.Input in = new StateModel.Input();
        in.hasProvider = true;
        in.hasModel = true;
        in.hasKey = true;
        in.hasBase = true;
        in.enabled = true;
        in.fileModel = "deepseek-chat";
        in.fileRev = 10L;
        in.appliedRev = 10L;
        in.bridgeConnected = true;
        in.lastReportAt = 1_000L;
        in.now = 2_000L;
        in.runtimeKnown = true;
        in.runtimeEnabled = true;
        in.runtimeHasKey = true;
        in.runtimeModel = "deepseek-chat";
        return in;
    }

    private static StateModel.Result r(StateModel.Input in) {
        return StateModel.resolve(in);
    }

    public static int run() {
        // D1：只要求字段完整，不要求端点可达
        T.eq("D1 完整+启用 → READY", StateModel.ConfigState.READY,
                r(ready()).d1);
        StateModel.Input noKey = ready();
        noKey.hasKey = false;
        T.eq("D1 缺密钥 → 未配置", StateModel.ConfigState.NOT_CONFIGURED, r(noKey).d1);
        StateModel.Input noBase = ready();
        noBase.hasBase = false;
        T.eq("D1 缺地址 → 未配置", StateModel.ConfigState.NOT_CONFIGURED, r(noBase).d1);
        StateModel.Input noModel = ready();
        noModel.hasModel = false;
        T.eq("D1 缺模型 → 未配置", StateModel.ConfigState.NOT_CONFIGURED, r(noModel).d1);
        StateModel.Input noProvider = ready();
        noProvider.hasProvider = false;
        T.eq("D1 缺服务商 → 未配置", StateModel.ConfigState.NOT_CONFIGURED, r(noProvider).d1);
        StateModel.Input paused = ready();
        paused.enabled = false;
        T.eq("D1 完整+暂停 → PAUSED", StateModel.ConfigState.PAUSED, r(paused).d1);
        StateModel.Input invalid = ready();
        invalid.configInvalid = true;
        T.eq("D1 文件损坏 → INVALID", StateModel.ConfigState.INVALID, r(invalid).d1);

        // D2：只由探针决定
        T.eq("D2 UNKNOWN", StateModel.EndpointState.UNKNOWN,
                r(ready()).d2);
        StateModel.Input ok = ready();
        ok.probe = StateModel.Probe.OK;
        T.eq("D2 OK", StateModel.EndpointState.OK, r(ok).d2);
        StateModel.Input checking = ready();
        checking.probe = StateModel.Probe.CHECKING;
        T.eq("D2 CHECKING", StateModel.EndpointState.CHECKING, r(checking).d2);
        StateModel.Input auth = ready();
        auth.probe = StateModel.Probe.AUTH;
        T.eq("D2 AUTH", StateModel.EndpointState.AUTH, r(auth).d2);
        StateModel.Input authOnly = ready();
        authOnly.runtimeKnown = false;
        authOnly.probe = StateModel.Probe.AUTH;
        StateModel.Input model = ready();
        model.probe = StateModel.Probe.MODEL;
        T.eq("D2 MODEL", StateModel.EndpointState.MODEL, r(model).d2);
        StateModel.Input endpoint = ready();
        endpoint.probe = StateModel.Probe.ENDPOINT;
        T.eq("D2 ENDPOINT", StateModel.EndpointState.ENDPOINT, r(endpoint).d2);

        // D3：连接/新鲜度/rev
        StateModel.Input disconnected = ready();
        disconnected.bridgeConnected = false;
        T.eq("D3 未连接 → UNKNOWN", StateModel.AppliedState.UNKNOWN, r(disconnected).d3);
        StateModel.Input wait = ready();
        wait.appliedRev = 9L;
        T.eq("D3 rev 落后 → WAITING", StateModel.AppliedState.WAITING, r(wait).d3);
        StateModel.Input applied = ready();
        applied.appliedRev = 11L;
        T.eq("D3 rev 超前/相等 → APPLIED", StateModel.AppliedState.APPLIED, r(applied).d3);
        StateModel.Input stale = ready();
        stale.now = 1_000L + StateModel.STALE_MS + 1L;
        T.eq("D3 超 30s → STALE", StateModel.AppliedState.STALE, r(stale).d3);

        // D4：运行态，只有 IN_USE 算确认
        T.eq("D4 完整运行态 → IN_USE", StateModel.RuntimeState.IN_USE, r(ready()).d4);
        T.ok("只有 IN_USE verified", r(ready()).verified());
        StateModel.Input d2ok = ready();
        d2ok.probe = StateModel.Probe.OK;
        d2ok.runtimeKnown = false;
        T.eq("D4 未上报 → UNKNOWN", StateModel.RuntimeState.UNKNOWN, r(d2ok).d4);
        T.ok("D2 成功不等于生效", !r(d2ok).verified());
        StateModel.Input staleRuntime = ready();
        staleRuntime.now = 1_000L + StateModel.STALE_MS + 1L;
        T.eq("D4 超 30s → UNKNOWN", StateModel.RuntimeState.UNKNOWN, r(staleRuntime).d4);
        T.ok("超时不算确认", !r(staleRuntime).verified());
        StateModel.Input disabled = ready();
        disabled.runtimeEnabled = false;
        T.eq("D4 禁用 → DISABLED", StateModel.RuntimeState.DISABLED, r(disabled).d4);
        StateModel.Input runtimeNoKey = ready();
        runtimeNoKey.runtimeHasKey = false;
        T.eq("D4 无密钥 → NO_KEY", StateModel.RuntimeState.NO_KEY, r(runtimeNoKey).d4);
        StateModel.Input emptyRuntimeModel = ready();
        emptyRuntimeModel.runtimeModel = "";
        T.eq("D4 运行模型为空 → MISMATCH", StateModel.RuntimeState.MODEL_MISMATCH,
                r(emptyRuntimeModel).d4);
        StateModel.Input mismatch = ready();
        mismatch.runtimeModel = "gpt-4o";
        T.eq("D4 模型不一致 → MISMATCH", StateModel.RuntimeState.MODEL_MISMATCH,
                r(mismatch).d4);
        StateModel.Input runtimeOnly = ready();
        runtimeOnly.fileModel = "";
        T.eq("D4 文件未选模型但运行时在用 → IN_USE",
                StateModel.RuntimeState.IN_USE, r(runtimeOnly).d4);

        // Hero 文案：模型服务可用 ≠ 生效
        StateModel.Input verified = ready();
        verified.probe = StateModel.Probe.OK;
        StateModel.Result vr = r(verified);
        T.eq("生效标题", "第三方模型已生效", vr.heroTitle());
        StateModel.Result er = r(d2ok);
        T.eq("模型服务可用标题", "模型服务可用", er.heroTitle());
        T.ok("端点可用副行等待上报", er.heroSub().contains("等待"));
        T.eq("暂停标题", "已暂停", r(paused).heroTitle());
        T.ok("暂停可继续编辑", r(paused).heroSub().contains("继续编辑"));
        T.eq("未配置标题", "未配置", r(noKey).heroTitle());
        T.eq("损坏标题", "配置异常", r(invalid).heroTitle());
        T.ok("超时副行不说在线", r(staleRuntime).heroSub().contains("30"));
        T.eq("D2 错误标题", "密钥无效", r(authOnly).heroTitle());
        T.eq("D4 不一致标题", "等待小爱应用", r(mismatch).heroTitle());

        // 状态投影：暂停优先，运行在用才 OK
        T.eq("暂停颜色态", AppStatus.PAUSED, r(paused).status());
        T.eq("生效颜色态", AppStatus.OK, vr.status());
        T.eq("校验错误颜色态", AppStatus.AUTH, r(authOnly).status());
        T.eq("配置未就绪颜色态", AppStatus.NOT_CONFIGURED, r(noKey).status());

        // fresh 边界
        T.ok("29.9s 仍新鲜", StateModel.fresh(1_000L, 1_000L + 29_900L));
        T.ok("30.1s 已过期", !StateModel.fresh(1_000L, 1_000L + 30_100L));
        T.ok("无上报时间不算新鲜", !StateModel.fresh(0L, 1_000L));

        return T.report("StateModelTest");
    }
}
