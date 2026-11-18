package plus.xiaoi.module.core;

import java.util.List;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.agent.AgentRuntimeStatus;

/**
 * 把现有只读事实适配成 {@link IssueResolver.Input}（纯 JVM，无 android 依赖）。
 *
 * <p>页面只需传入配置标志、{@link BridgeStatus} 快照、{@link AgentRuntimeStatus}
 * 快照与权限快照；本类不读文件、不联网、不持 Context，也不重新探测端口。
 * 未知输入保持 UNKNOWN/OFF 等安全默认，不伪造"已连接/已启动"。</p>
 *
 * <p>D1~D4 统一交给 {@link StateModel#resolve(StateModel.Input)}，8771/8770
 * 的 {@link IssueResolver.PortState} 分别由两个独立快照推导，绝不互相代替。</p>
 */
public final class IssueInputFactory {

    private IssueInputFactory() {}

    /** 配置侧事实；由调用方从 AppConfig/ConfigRepository 读取后显式填入。 */
    public static final class ConfigFacts {
        /** false = 文件损坏/不可解析（D1 INVALID）；true 不保证服务商齐全。 */
        public boolean configValid = true;
        /** 兼容模式/文件只读；与 D1 分开报告。 */
        public boolean readOnly;
        public boolean mcpEnabled;
        /** 用户是否请求过任一局域网（8771 mcpLan 或 8770 agentLan）。 */
        public boolean lanRequested;

        public boolean hasProvider;
        public boolean hasModel;
        public boolean hasKey;
        public boolean hasBase;
        public boolean enabled;
        public String model = "";
        public long rev;

        /** 令牌原文只用于判空/强度，不会写进 issue 或日志。 */
        public String token = "";
        /** 非 null 时优先于 {@link #token} 推导的强度。 */
        public IssueResolver.TokenStrength tokenStrength;

        /** 端点探针结论；默认 UNKNOWN，调用方校验后填入。 */
        public StateModel.Probe probe = StateModel.Probe.UNKNOWN;

        public ConfigFacts() {}

        /**
         * 从 AppConfig 取显式字段；不读文件、不判断文件是否损坏。
         * {@code configValid=false} 时 D1 直接判 INVALID。
         */
        public static ConfigFacts from(AppConfig c, boolean configValid, boolean readOnly) {
            ConfigFacts f = new ConfigFacts();
            f.configValid = configValid;
            f.readOnly = readOnly;
            if (c == null) {
                f.configValid = false;
                return f;
            }
            f.mcpEnabled = c.mcpEnabled;
            f.lanRequested = c.mcpLan || c.agentLan;
            f.hasProvider = c.activeProvider() != null;
            f.model = c.model() == null ? "" : c.model();
            f.hasModel = f.model.trim().length() > 0;
            f.hasKey = c.apiKeyOfActive().length() > 0;
            String base = c.baseUrl();
            f.hasBase = base != null && base.trim().length() > 0;
            f.enabled = c.enabled;
            f.rev = c.rev;
            f.token = c.mcpToken == null ? "" : c.mcpToken;
            return f;
        }
    }

    /** 全部输入显式传入；null 快照按"没有事实"处理。 */
    public static IssueResolver.Input from(ConfigFacts facts,
                                           BridgeStatus.Snapshot bridge,
                                           AgentRuntimeStatus.Snapshot agent,
                                           PermissionSnapshot permissions,
                                           long now) {
        ConfigFacts f = facts == null ? new ConfigFacts() : facts;
        long at = now > 0 ? now : System.currentTimeMillis();

        boolean connected = bridge != null && bridge.connected && bridge.port > 0;
        StateModel.Input sm = new StateModel.Input();
        sm.hasProvider = f.hasProvider;
        sm.hasModel = f.hasModel;
        sm.hasKey = f.hasKey;
        sm.hasBase = f.hasBase;
        sm.enabled = f.enabled;
        sm.configInvalid = !f.configValid;
        sm.probe = f.probe == null ? StateModel.Probe.UNKNOWN : f.probe;
        sm.bridgeConnected = connected;
        sm.fileRev = f.rev;
        sm.appliedRev = bridge == null ? 0L : bridge.appliedRev;
        sm.lastReportAt = bridge == null ? 0L : bridge.updatedAt;
        sm.now = at;
        sm.runtimeKnown = bridge != null && bridge.runtimeKnown;
        sm.runtimeEnabled = bridge != null && bridge.enabled;
        sm.runtimeHasKey = bridge != null && bridge.hasKey;
        sm.runtimeModel = bridge == null ? "" : bridge.model;
        sm.fileModel = f.model == null ? "" : f.model;

        StateModel.Result r = StateModel.resolve(sm);

        IssueResolver.Input in = new IssueResolver.Input();
        in.d1 = r.d1;
        in.d2 = r.d2;
        in.d3 = r.d3;
        in.d4 = r.d4;
        in.bridge8771 = bridgePort(bridge, f.mcpEnabled, at);
        in.agent8770 = agentPort(agent, f.mcpEnabled);
        in.token = f.tokenStrength == null ? tokenStrength(f.token) : f.tokenStrength;
        in.lanRequested = f.lanRequested;
        in.configReadOnly = f.readOnly;
        in.mcpEnabled = f.mcpEnabled;
        in.permissions = permissions == null ? PermissionSnapshot.unknown() : permissions;
        return in;
    }

    public static IssueResolver.Input from(ConfigFacts facts,
                                           BridgeStatus.Snapshot bridge,
                                           AgentRuntimeStatus.Snapshot agent,
                                           PermissionSnapshot permissions) {
        return from(facts, bridge, agent, permissions, System.currentTimeMillis());
    }

    /** 便捷入口：直接得到按优先级排序的待处理项。 */
    public static List<IssueResolver.Issue> issues(ConfigFacts facts,
                                                   BridgeStatus.Snapshot bridge,
                                                   AgentRuntimeStatus.Snapshot agent,
                                                   PermissionSnapshot permissions,
                                                   long now) {
        return IssueResolver.resolve(from(facts, bridge, agent, permissions, now));
    }

    public static List<IssueResolver.Issue> issues(ConfigFacts facts,
                                                   BridgeStatus.Snapshot bridge,
                                                   AgentRuntimeStatus.Snapshot agent,
                                                   PermissionSnapshot permissions) {
        return issues(facts, bridge, agent, permissions, System.currentTimeMillis());
    }

    /**
     * 8771：有新鲜 leader 才算 RUNNING；连接过但超过 30s 算 WAITING。
     * 没有快照时，MCP 开启为 WAITING（等 leader），关闭为 OFF；不猜 FAILED。
     */
    public static IssueResolver.PortState bridgePort(BridgeStatus.Snapshot bridge,
                                                     boolean mcpEnabled, long now) {
        if (bridge != null && bridge.connected && bridge.port > 0) {
            return bridge.fresh(now)
                    ? IssueResolver.PortState.RUNNING
                    : IssueResolver.PortState.WAITING;
        }
        return mcpEnabled ? IssueResolver.PortState.WAITING : IssueResolver.PortState.OFF;
    }

    /**
     * 8770：运行中 → RUNNING；有错误 → FAILED；已知停止 → OFF；
     * 从未收到事实 → UNKNOWN（MCP 关闭时按用户选择记 OFF）。
     */
    public static IssueResolver.PortState agentPort(AgentRuntimeStatus.Snapshot agent,
                                                    boolean mcpEnabled) {
        if (agent != null && agent.running) return IssueResolver.PortState.RUNNING;
        if (agent != null && agent.lastError.length() > 0) {
            return IssueResolver.PortState.FAILED;
        }
        if (agent != null && agent.updatedAt > 0) return IssueResolver.PortState.OFF;
        return mcpEnabled ? IssueResolver.PortState.UNKNOWN : IssueResolver.PortState.OFF;
    }

    /** 空串/空白 = NONE；32 位十六进制 = STRONG；其它非空 = WEAK。 */
    public static IssueResolver.TokenStrength tokenStrength(String token) {
        String t = BridgePolicy.normalize(token);
        if (t.length() == 0) return IssueResolver.TokenStrength.NONE;
        return BridgePolicy.tokenStrongEnoughForLan(t)
                ? IssueResolver.TokenStrength.STRONG
                : IssueResolver.TokenStrength.WEAK;
    }
}
