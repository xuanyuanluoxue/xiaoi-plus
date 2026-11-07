package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import plus.xiaoi.module.core.IssueResolver;
import plus.xiaoi.module.core.PermissionSnapshot;
import plus.xiaoi.module.core.StateModel;

/**
 * IssueResolver 纯逻辑：优先级、截断、0 条、离线/弱令牌/权限缺失、只读/损坏。
 * 只验证解析器本身，不复制 StateModel 的 D1~D4 判断。
 */
public final class IssueResolverTest {

    private IssueResolverTest() {}

    private static IssueResolver.Input healthy() {
        IssueResolver.Input in = new IssueResolver.Input();
        in.d1 = StateModel.ConfigState.READY;
        in.d2 = StateModel.EndpointState.OK;
        in.d3 = StateModel.AppliedState.APPLIED;
        in.d4 = StateModel.RuntimeState.IN_USE;
        in.bridge8771 = IssueResolver.PortState.RUNNING;
        in.agent8770 = IssueResolver.PortState.RUNNING;
        in.token = IssueResolver.TokenStrength.STRONG;
        in.lanRequested = false;
        in.configReadOnly = false;
        in.mcpEnabled = true;
        in.permissions = PermissionSnapshot.unknown();
        return in;
    }

    private static List<String> ids(List<IssueResolver.Issue> issues) {
        List<String> out = new ArrayList<>();
        for (IssueResolver.Issue i : issues) out.add(i.id);
        return out;
    }

    public static int run() {
        // 0 条：全部健康
        List<IssueResolver.Issue> none = IssueResolver.resolve(healthy());
        T.eq("健康输入 0 条", 0, none.size());

        // 配置层优先：未配置时不再报端点/运行态错误
        IssueResolver.Input un = healthy();
        un.d1 = StateModel.ConfigState.NOT_CONFIGURED;
        un.d2 = StateModel.EndpointState.AUTH;
        un.d4 = StateModel.RuntimeState.MODEL_MISMATCH;
        List<IssueResolver.Issue> unIssues = IssueResolver.resolve(un);
        T.eq("未配置首条", "config.not_configured", unIssues.get(0).id);
        T.eq("未配置不重复端点错误", 1, unIssues.size());
        T.eq("未配置 CTA 去模型页", "Model", unIssues.get(0).route);

        IssueResolver.Input paused = healthy();
        paused.d1 = StateModel.ConfigState.PAUSED;
        List<IssueResolver.Issue> pauseIssues = IssueResolver.resolve(paused);
        T.eq("暂停首条", "config.paused", pauseIssues.get(0).id);
        T.eq("暂停 severity INFO", IssueResolver.Severity.INFO,
                pauseIssues.get(0).severity);
        T.eq("暂停只一条", 1, pauseIssues.size());

        IssueResolver.Input broken = healthy();
        broken.d1 = StateModel.ConfigState.INVALID;
        broken.configReadOnly = true;
        List<IssueResolver.Issue> brokenIssues = IssueResolver.resolve(broken);
        T.eq("损坏首条", "config.invalid", brokenIssues.get(0).id);
        T.eq("损坏吞掉只读重复", 1, brokenIssues.size());

        IssueResolver.Input readOnly = healthy();
        readOnly.configReadOnly = true;
        List<IssueResolver.Issue> roIssues = IssueResolver.resolve(readOnly);
        T.eq("只读首条", "config.readonly", roIssues.get(0).id);
        T.eq("只读 severity ERROR", IssueResolver.Severity.ERROR,
                roIssues.get(0).severity);
        T.eq("只读 CTA 兼容模式", "Legacy", roIssues.get(0).route);

        // 优先级：端点 → 运行态 → 令牌 → 8771 → 8770 → 权限
        IssueResolver.Input many = healthy();
        many.d2 = StateModel.EndpointState.AUTH;
        many.d4 = StateModel.RuntimeState.MODEL_MISMATCH;
        many.token = IssueResolver.TokenStrength.WEAK;
        many.lanRequested = true;
        many.bridge8771 = IssueResolver.PortState.FAILED;
        many.agent8770 = IssueResolver.PortState.FAILED;
        many.permissions = PermissionSnapshot.builder()
                .notifications(PermissionSnapshot.State.DENIED)
                .build();
        List<IssueResolver.Issue> all = IssueResolver.resolve(many);
        T.eq("优先级完整顺序",
                "[endpoint.auth, runtime.mismatch, token.weak_lan, bridge.mcp_failed, agent.failed, perm.notification]",
                ids(all).toString());
        T.eq("端点错误 ERROR", IssueResolver.Severity.ERROR, all.get(0).severity);
        T.eq("令牌回退 WARN", IssueResolver.Severity.WARN, all.get(2).severity);

        // 最多条数：调用方截断，不改变顺序
        List<IssueResolver.Issue> top3 = IssueResolver.limit(all, 3);
        T.eq("截断到 3 条", 3, top3.size());
        T.eq("截断保留顺序", "[endpoint.auth, runtime.mismatch, token.weak_lan]",
                ids(top3).toString());
        T.eq("max=0 返回空", 0, IssueResolver.limit(all, 0).size());
        T.eq("max<0 返回空", 0, IssueResolver.limit(all, -2).size());
        T.eq("上限大于条数原样返回", all.size(),
                IssueResolver.limit(all, 99).size());
        T.eq("null 列表返回空", 0, IssueResolver.limit(null, 3).size());

        // 离线：8771 等待小爱、8770 启动失败，两者分开
        IssueResolver.Input offline = healthy();
        offline.d3 = StateModel.AppliedState.UNKNOWN;
        offline.d4 = StateModel.RuntimeState.UNKNOWN;
        offline.bridge8771 = IssueResolver.PortState.WAITING;
        offline.agent8770 = IssueResolver.PortState.FAILED;
        List<IssueResolver.Issue> offIssues = IssueResolver.resolve(offline);
        T.eq("离线小爱 + 动作面失败", "[xiaoi.waiting, agent.failed]",
                ids(offIssues).toString());
        T.eq("8771 等待不等于 8770 失败", "Xiaoai", offIssues.get(0).route);
        T.eq("8770 失败去端口页", "Bridge", offIssues.get(1).route);

        IssueResolver.Input stale = healthy();
        stale.d3 = StateModel.AppliedState.STALE;
        List<IssueResolver.Issue> staleIssues = IssueResolver.resolve(stale);
        T.eq("超过 30 秒上报", "bridge.stale", staleIssues.get(0).id);

        IssueResolver.Input noReport = healthy();
        noReport.d3 = StateModel.AppliedState.UNKNOWN;
        noReport.d4 = StateModel.RuntimeState.UNKNOWN;
        List<IssueResolver.Issue> nrIssues = IssueResolver.resolve(noReport);
        T.eq("控制面已连但未上报运行态", "bridge.no_report", nrIssues.get(0).id);
        T.eq("未上报是 INFO", IssueResolver.Severity.INFO, nrIssues.get(0).severity);

        IssueResolver.Input bridgeOff = healthy();
        bridgeOff.bridge8771 = IssueResolver.PortState.OFF;
        T.eq("8771 未开启", "bridge.mcp_off",
                IssueResolver.resolve(bridgeOff).get(0).id);

        // MCP 关闭：可选通道不生成待处理
        IssueResolver.Input mcpOff = healthy();
        mcpOff.mcpEnabled = false;
        mcpOff.token = IssueResolver.TokenStrength.NONE;
        mcpOff.bridge8771 = IssueResolver.PortState.OFF;
        mcpOff.agent8770 = IssueResolver.PortState.OFF;
        T.eq("MCP 关闭不报令牌/通道", 0, IssueResolver.resolve(mcpOff).size());

        // 弱令牌：只有开启 LAN 才提示回退
        IssueResolver.Input weakNoLan = healthy();
        weakNoLan.token = IssueResolver.TokenStrength.WEAK;
        weakNoLan.lanRequested = false;
        T.eq("弱令牌未开 LAN 不提示", 0,
                IssueResolver.resolve(weakNoLan).size());

        IssueResolver.Input weakLan = healthy();
        weakLan.token = IssueResolver.TokenStrength.WEAK;
        weakLan.lanRequested = true;
        List<IssueResolver.Issue> weakIssues = IssueResolver.resolve(weakLan);
        T.eq("弱令牌 + LAN 提示回退", "token.weak_lan", weakIssues.get(0).id);
        T.eq("弱令牌 CTA 去令牌页", "Token", weakIssues.get(0).route);
        T.eq("弱令牌说明 32 位", true,
                weakIssues.get(0).actionText.contains("32"));

        IssueResolver.Input noToken = healthy();
        noToken.token = IssueResolver.TokenStrength.NONE;
        noToken.lanRequested = true;
        List<IssueResolver.Issue> ntIssues = IssueResolver.resolve(noToken);
        T.eq("无令牌优先于弱令牌", "token.missing", ntIssues.get(0).id);

        // 权限：拒绝才提示；UNKNOWN/GRANTED 不制造假问题
        IssueResolver.Input denied = healthy();
        denied.permissions = PermissionSnapshot.builder()
                .notifications(PermissionSnapshot.State.DENIED)
                .exactAlarm(PermissionSnapshot.State.DENIED)
                .fullScreenIntent(PermissionSnapshot.State.DENIED)
                .batteryOptimization(PermissionSnapshot.State.DENIED)
                .usageAccess(PermissionSnapshot.State.DENIED)
                .notificationListener(PermissionSnapshot.State.GRANTED)
                .build();
        List<IssueResolver.Issue> permIssues = IssueResolver.resolve(denied);
        T.eq("权限缺失四条", 4, permIssues.size());
        T.eq("权限提示顺序",
                "[perm.notification, perm.exact_alarm, perm.full_screen, perm.battery]",
                ids(permIssues).toString());
        T.eq("权限 CTA 去诊断页", "Diagnostics", permIssues.get(0).route);
        T.eq("权限快照 missingCount", 5, denied.permissions.missingCount());

        IssueResolver.Input granted = healthy();
        granted.permissions = PermissionSnapshot.builder()
                .notifications(PermissionSnapshot.State.GRANTED)
                .exactAlarm(PermissionSnapshot.State.NOT_APPLICABLE)
                .fullScreenIntent(PermissionSnapshot.State.UNKNOWN)
                .build();
        T.eq("已授权/不适用不提示", 0, IssueResolver.resolve(granted).size());

        // null 输入：按未配置处理，不崩溃
        List<IssueResolver.Issue> nullIssues = IssueResolver.resolve(null);
        T.eq("null 输入首条未配置", "config.not_configured",
                nullIssues.get(0).id);
        T.ok("null 输入不崩溃", nullIssues.size() >= 1);

        // 结构不变式：所有 issue 都有 id/title/action/route
        boolean fieldsOk = true;
        boolean routesOk = true;
        Set<String> knownRoutes = plus.xiaoi.module.core.CapabilityRegistry.ROUTES;
        for (IssueResolver.Issue i : all) {
            if (i.id.isEmpty() || i.title.isEmpty()
                    || i.actionText.isEmpty() || i.route.isEmpty()) {
                fieldsOk = false;
            }
            if (!knownRoutes.contains(i.route)) routesOk = false;
        }
        T.ok("issue 字段完整", fieldsOk);
        T.ok("issue 路由在白名单内", routesOk);

        return T.report("IssueResolverTest");
    }
}
