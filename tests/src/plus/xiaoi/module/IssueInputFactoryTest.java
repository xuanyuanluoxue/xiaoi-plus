package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.agent.AgentRuntimeStatus;
import plus.xiaoi.module.core.BridgeStatus;
import plus.xiaoi.module.core.IssueInputFactory;
import plus.xiaoi.module.core.IssueResolver;
import plus.xiaoi.module.core.PermissionSnapshot;
import plus.xiaoi.module.core.StateModel;

/** IssueInputFactory：四维状态、端口推导、令牌强度、权限与未知输入。 */
public final class IssueInputFactoryTest {

    private static final long NOW = 100_000L;
    private static final String STRONG =
            "0123456789abcdef0123456789abcdef";

    private IssueInputFactoryTest() {}

    private static IssueInputFactory.ConfigFacts healthyFacts() {
        IssueInputFactory.ConfigFacts f = new IssueInputFactory.ConfigFacts();
        f.configValid = true;
        f.readOnly = false;
        f.mcpEnabled = true;
        f.lanRequested = false;
        f.hasProvider = true;
        f.hasModel = true;
        f.hasKey = true;
        f.hasBase = true;
        f.enabled = true;
        f.model = "deepseek-chat";
        f.rev = 5L;
        f.token = STRONG;
        f.probe = StateModel.Probe.OK;
        return f;
    }

    private static BridgeStatus.Snapshot healthyBridge() {
        BridgeStatus.set(8771, "deepseek-chat", true, true, 5L, NOW, true,
                "127.0.0.1", Boolean.FALSE, NOW, 0L);
        return BridgeStatus.get();
    }

    private static List<String> ids(List<IssueResolver.Issue> issues) {
        List<String> out = new ArrayList<String>();
        for (IssueResolver.Issue i : issues) out.add(i.id);
        return out;
    }

    public static int run() {
        AgentRuntimeStatus.clear();

        IssueInputFactory.ConfigFacts f = healthyFacts();
        BridgeStatus.Snapshot bridge = healthyBridge();
        AgentRuntimeStatus.setRunning(8770, "127.0.0.1", false);
        AgentRuntimeStatus.Snapshot agent = AgentRuntimeStatus.get();

        IssueResolver.Input in = IssueInputFactory.from(
                f, bridge, agent, PermissionSnapshot.unknown(), NOW);
        T.eq("READY", StateModel.ConfigState.READY, in.d1);
        T.eq("端点 OK", StateModel.EndpointState.OK, in.d2);
        T.eq("已应用", StateModel.AppliedState.APPLIED, in.d3);
        T.eq("运行态 IN_USE", StateModel.RuntimeState.IN_USE, in.d4);
        T.eq("8771 RUNNING", IssueResolver.PortState.RUNNING, in.bridge8771);
        T.eq("8770 RUNNING", IssueResolver.PortState.RUNNING, in.agent8770);
        T.eq("强令牌", IssueResolver.TokenStrength.STRONG, in.token);
        T.ok("健康输入无待处理", IssueInputFactory.issues(
                f, bridge, agent, PermissionSnapshot.unknown(), NOW).isEmpty());

        // 暂停：D1 PAUSED，端点/运行态不再生成 issue。
        IssueInputFactory.ConfigFacts paused = healthyFacts();
        paused.enabled = false;
        IssueResolver.Input pausedIn = IssueInputFactory.from(
                paused, bridge, agent, PermissionSnapshot.unknown(), NOW);
        T.eq("PAUSED", StateModel.ConfigState.PAUSED, pausedIn.d1);
        T.eq("暂停首条", "[config.paused]", ids(IssueInputFactory.issues(
                paused, bridge, agent, PermissionSnapshot.unknown(), NOW)).toString());

        // 损坏：D1 INVALID。
        IssueInputFactory.ConfigFacts invalid = healthyFacts();
        invalid.configValid = false;
        IssueResolver.Input invalidIn = IssueInputFactory.from(
                invalid, bridge, agent, PermissionSnapshot.unknown(), NOW);
        T.eq("INVALID", StateModel.ConfigState.INVALID, invalidIn.d1);
        T.eq("损坏首条", "[config.invalid]", ids(IssueInputFactory.issues(
                invalid, bridge, agent, PermissionSnapshot.unknown(), NOW)).toString());

        // 弱令牌 + 请求 LAN：提示回退，不放行局域网。
        IssueInputFactory.ConfigFacts weak = healthyFacts();
        weak.token = "short-token";
        weak.lanRequested = true;
        IssueResolver.Input weakIn = IssueInputFactory.from(
                weak, bridge, agent, PermissionSnapshot.unknown(), NOW);
        T.eq("弱令牌", IssueResolver.TokenStrength.WEAK, weakIn.token);
        T.eq("弱令牌 + LAN", "[token.weak_lan]", ids(IssueInputFactory.issues(
                weak, bridge, agent, PermissionSnapshot.unknown(), NOW)).toString());

        // 8770 绑定失败：FAILED 只来自 AgentRuntimeStatus，不污染 8771。
        AgentRuntimeStatus.setError("绑定 127.0.0.1:8770 失败：BindException");
        IssueResolver.Input failedIn = IssueInputFactory.from(
                f, bridge, AgentRuntimeStatus.get(), PermissionSnapshot.unknown(), NOW);
        T.eq("8770 FAILED", IssueResolver.PortState.FAILED, failedIn.agent8770);
        T.eq("8771 仍 RUNNING", IssueResolver.PortState.RUNNING, failedIn.bridge8771);
        T.eq("8770 失败 issue", "[agent.failed]", ids(IssueInputFactory.issues(
                f, bridge, AgentRuntimeStatus.get(),
                PermissionSnapshot.unknown(), NOW)).toString());

        // 权限：只有明确 DENIED 才提示。
        PermissionSnapshot denied = PermissionSnapshot.builder()
                .notifications(PermissionSnapshot.State.DENIED)
                .build();
        T.eq("通知拒绝 issue", "[perm.notification]", ids(
                IssueInputFactory.issues(f, bridge, agent, denied, NOW)).toString());

        // 未知输入：不崩溃、不伪造健康。
        IssueResolver.Input nullIn = IssueInputFactory.from(null, null, null, null, NOW);
        T.eq("null 输入 D1 未配置", StateModel.ConfigState.NOT_CONFIGURED, nullIn.d1);
        T.eq("null 输入 8771 OFF", IssueResolver.PortState.OFF, nullIn.bridge8771);
        T.eq("null 输入 8770 OFF", IssueResolver.PortState.OFF, nullIn.agent8770);
        T.eq("null 输入 token NONE", IssueResolver.TokenStrength.NONE, nullIn.token);
        T.ok("null 输入不崩溃", IssueInputFactory.issues(null, null, null, null, NOW).size() > 0);

        // 8771 陈旧：连接过但超过 30s = WAITING，不假装 RUNNING。
        BridgeStatus.set(8771, "deepseek-chat", true, true, 5L,
                NOW - StateModel.STALE_MS - 1L, true);
        T.eq("8771 陈旧为 WAITING", IssueResolver.PortState.WAITING,
                IssueInputFactory.bridgePort(BridgeStatus.get(), true, NOW));
        T.eq("MCP 关闭且无快照为 OFF", IssueResolver.PortState.OFF,
                IssueInputFactory.bridgePort(null, false, NOW));

        // 8770：从未写入 = UNKNOWN；已知停止 = OFF。
        AgentRuntimeStatus.clear();
        T.eq("8770 未知", IssueResolver.PortState.UNKNOWN,
                IssueInputFactory.agentPort(AgentRuntimeStatus.get(), true));
        AgentRuntimeStatus.setStopped();
        T.eq("8770 已知停止", IssueResolver.PortState.OFF,
                IssueInputFactory.agentPort(AgentRuntimeStatus.get(), true));

        // 令牌强度：空/弱/32 hex 强/32 非 hex 弱。
        T.eq("空令牌 NONE", IssueResolver.TokenStrength.NONE,
                IssueInputFactory.tokenStrength("  "));
        T.eq("短令牌 WEAK", IssueResolver.TokenStrength.WEAK,
                IssueInputFactory.tokenStrength("abc"));
        T.eq("32 hex STRONG", IssueResolver.TokenStrength.STRONG,
                IssueInputFactory.tokenStrength(STRONG.toUpperCase()));
        T.eq("32 非 hex WEAK", IssueResolver.TokenStrength.WEAK,
                IssueInputFactory.tokenStrength("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"));

        // ConfigFacts.from(AppConfig)：只读字段映射，不读文件。
        AppConfig c = new AppConfig();
        c.enabled = true;
        c.mcpEnabled = true;
        c.mcpLan = true;
        c.mcpToken = STRONG;
        c.rev = 7L;
        AppConfig.Provider p = new AppConfig.Provider(
                "p1", "P1", "https://example.test/v1", "sk-test");
        p.models.add(new AppConfig.ModelItem("model-1", "Model 1"));
        c.providers.add(p);
        c.activeProviderId = "p1";
        c.activeModelId = "model-1";
        IssueInputFactory.ConfigFacts mapped =
                IssueInputFactory.ConfigFacts.from(c, true, false);
        T.ok("映射服务商", mapped.hasProvider);
        T.ok("映射模型", mapped.hasModel);
        T.ok("映射密钥", mapped.hasKey);
        T.ok("映射 base", mapped.hasBase);
        T.ok("映射 LAN 请求", mapped.lanRequested);
        T.eq("映射模型 ID", "model-1", mapped.model);
        T.eq("映射 rev", 7L, mapped.rev);
        T.eq("映射强令牌", IssueResolver.TokenStrength.STRONG,
                IssueInputFactory.tokenStrength(mapped.token));
        IssueInputFactory.ConfigFacts broken =
                IssueInputFactory.ConfigFacts.from(c, false, true);
        T.ok("configValid=false 映射为 INVALID 输入", !broken.configValid);
        T.ok("readOnly 映射", broken.readOnly);

        AgentRuntimeStatus.clear();
        BridgeStatus.clear();
        return T.report("IssueInputFactoryTest");
    }
}
