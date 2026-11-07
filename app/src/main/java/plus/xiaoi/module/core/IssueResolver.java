package plus.xiaoi.module.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 首页「待处理」的纯函数解析器（无 android 依赖）。
 *
 * <p>输入只接受调用方已经判定好的事实（D1~D4、通道状态、令牌强度、权限、只读），
 * 本类不读配置、不扫端口、不重复 {@link StateModel} 的判断。输出按优先级排列，
 * 不截断；首页用 {@link #limit(List, int)} 取前 3 条，0 条时显示「一切正常」。</p>
 *
 * <p>顺序即用户可行动顺序：配置损坏/只读 → 未配置/暂停 → 端点 → 运行态 →
 * 令牌 → 8771/8770 → 权限。</p>
 */
public final class IssueResolver {

    public enum Severity { INFO, WARN, ERROR }

    /** 通道状态；UNKNOWN 表示还没探测到，不生成 issue。 */
    public enum PortState { UNKNOWN, RUNNING, OFF, WAITING, FAILED }

    /** 令牌强度：NONE 未设置，WEAK 不足 32 hex，STRONG 可用。 */
    public enum TokenStrength { NONE, WEAK, STRONG }

    /** 一条待处理事项；route 使用 SCREENS.md 的路由名，不携带任何参数。 */
    public static final class Issue {
        public final String id;
        public final String title;
        public final String actionText;
        public final Severity severity;
        public final String route;

        Issue(String id, String title, String actionText, Severity severity, String route) {
            this.id = text(id);
            this.title = text(title);
            this.actionText = text(actionText);
            this.severity = severity == null ? Severity.INFO : severity;
            this.route = text(route);
        }
    }

    /** 调用方填写的输入；缺省值等于「未配置/未知」，不会伪造健康。 */
    public static final class Input {
        public StateModel.ConfigState d1 = StateModel.ConfigState.NOT_CONFIGURED;
        public StateModel.EndpointState d2 = StateModel.EndpointState.UNKNOWN;
        public StateModel.AppliedState d3 = StateModel.AppliedState.UNKNOWN;
        public StateModel.RuntimeState d4 = StateModel.RuntimeState.UNKNOWN;

        public PortState bridge8771 = PortState.UNKNOWN;
        public PortState agent8770 = PortState.UNKNOWN;

        public TokenStrength token = TokenStrength.NONE;
        /** 用户是否开启了任一侧局域网；弱令牌 + 开启会回退仅本机。 */
        public boolean lanRequested;

        public boolean configReadOnly;
        /** 权限探针；null 等同全 UNKNOWN。 */
        public PermissionSnapshot permissions;
    }

    private IssueResolver() {}

    public static List<Issue> resolve(Input input) {
        Input in = input == null ? new Input() : input;
        StateModel.ConfigState d1 = in.d1 == null
                ? StateModel.ConfigState.NOT_CONFIGURED : in.d1;
        StateModel.EndpointState d2 = in.d2 == null
                ? StateModel.EndpointState.UNKNOWN : in.d2;
        StateModel.AppliedState d3 = in.d3 == null
                ? StateModel.AppliedState.UNKNOWN : in.d3;
        StateModel.RuntimeState d4 = in.d4 == null
                ? StateModel.RuntimeState.UNKNOWN : in.d4;

        ArrayList<Issue> out = new ArrayList<>();

        // 1. 配置层：损坏/只读/未配置/暂停
        boolean ready = d1 == StateModel.ConfigState.READY;
        if (d1 == StateModel.ConfigState.INVALID) {
            add(out, "config.invalid", "配置文件损坏", "恢复或重建配置",
                    Severity.ERROR, "ConfigManage");
        } else if (in.configReadOnly) {
            add(out, "config.readonly", "配置当前只读", "查看兼容模式",
                    Severity.ERROR, "Legacy");
        }
        if (d1 == StateModel.ConfigState.NOT_CONFIGURED) {
            add(out, "config.not_configured", "尚未配置模型", "去配置服务商与密钥",
                    Severity.WARN, "Model");
        } else if (d1 == StateModel.ConfigState.PAUSED) {
            add(out, "config.paused", "第三方模型已暂停", "去小爱页启用",
                    Severity.INFO, "Xiaoai");
        }

        // 2. 端点：只展示探针结论，不改写 D4
        if (ready) {
            switch (d2) {
                case AUTH:
                    add(out, "endpoint.auth", "密钥无效或被拒", "检查密钥",
                            Severity.ERROR, "Model");
                    break;
                case MODEL:
                    add(out, "endpoint.model", "模型不存在", "检查模型 ID",
                            Severity.ERROR, "Model");
                    break;
                case ENDPOINT:
                    add(out, "endpoint.offline", "端点不可达", "检查地址与网络",
                            Severity.ERROR, "Model");
                    break;
                case UNKNOWN:
                case CHECKING:
                case OK:
                default:
                    break;
            }

            // 3. 运行态：只有 D4 能说明小爱是否在用
            switch (d4) {
                case DISABLED:
                    add(out, "runtime.disabled", "小爱侧未启用重定向", "重新启用并通知",
                            Severity.WARN, "Xiaoai");
                    break;
                case NO_KEY:
                    add(out, "runtime.no_key", "小爱侧缺少密钥", "填写密钥并保存",
                            Severity.WARN, "Model");
                    break;
                case MODEL_MISMATCH:
                    add(out, "runtime.mismatch", "运行模型与文件不一致", "重新应用配置",
                            Severity.WARN, "Xiaoai");
                    break;
                case IN_USE:
                case UNKNOWN:
                default:
                    break;
            }

            if (d3 == StateModel.AppliedState.STALE) {
                add(out, "bridge.stale", "控制面超过 30 秒未上报", "重新通知或重启小爱",
                        Severity.WARN, "Xiaoai");
            } else if (d3 == StateModel.AppliedState.WAITING) {
                add(out, "runtime.not_applied", "配置等待小爱应用", "重新通知小爱",
                        Severity.WARN, "Xiaoai");
            } else if (d4 == StateModel.RuntimeState.UNKNOWN
                    && in.bridge8771 == PortState.RUNNING) {
                add(out, "bridge.no_report", "等待小爱上报运行态", "稍后刷新或重新通知",
                        Severity.INFO, "Xiaoai");
            }
        }

        // 4. 令牌与通道
        if (in.token == TokenStrength.NONE) {
            add(out, "token.missing", "未设置 MCP 令牌", "生成并保存令牌",
                    Severity.WARN, "Token");
        } else if (in.token == TokenStrength.WEAK && in.lanRequested) {
            add(out, "token.weak_lan", "局域网已回退仅本机", "换用 32 位十六进制令牌",
                    Severity.WARN, "Token");
        }

        if (in.bridge8771 == PortState.WAITING) {
            add(out, "xiaoi.waiting", "小爱未运行或未上报", "打开小爱并重新通知",
                    Severity.INFO, "Xiaoai");
        } else if (in.bridge8771 == PortState.FAILED) {
            add(out, "bridge.mcp_failed", "能力桥 8771 启动失败", "查看端口与服务",
                    Severity.ERROR, "Bridge");
        } else if (in.bridge8771 == PortState.OFF) {
            add(out, "bridge.mcp_off", "能力桥未开启", "到接入页开启",
                    Severity.WARN, "Access");
        }

        if (in.agent8770 == PortState.FAILED) {
            add(out, "agent.failed", "动作面 8770 启动失败", "查看端口与服务",
                    Severity.ERROR, "Bridge");
        } else if (in.agent8770 == PortState.OFF) {
            add(out, "agent.off", "动作面 8770 未启动", "查看端口与服务",
                    Severity.WARN, "Bridge");
        }

        // 5. 权限：行内 warning，不阻塞浏览
        PermissionSnapshot p = in.permissions;
        if (p != null) {
            if (p.notifications == PermissionSnapshot.State.DENIED) {
                add(out, "perm.notification", "通知权限未开启", "去授权",
                        Severity.WARN, "Diagnostics");
            }
            if (p.exactAlarm == PermissionSnapshot.State.DENIED) {
                add(out, "perm.exact_alarm", "精确闹钟未授权", "去授权",
                        Severity.WARN, "Diagnostics");
            }
            if (p.fullScreenIntent == PermissionSnapshot.State.DENIED) {
                add(out, "perm.full_screen", "全屏通知未授权", "去授权",
                        Severity.WARN, "Diagnostics");
            }
            if (p.batteryOptimization == PermissionSnapshot.State.DENIED) {
                add(out, "perm.battery", "电池优化未关闭", "去设置",
                        Severity.INFO, "Diagnostics");
            }
        }

        return Collections.unmodifiableList(out);
    }

    /** 首页截断 helper：max &lt;= 0 返回空，超出部分丢弃，顺序不变。 */
    public static List<Issue> limit(List<Issue> issues, int max) {
        if (issues == null || issues.isEmpty() || max <= 0) {
            return Collections.emptyList();
        }
        if (issues.size() <= max) {
            return Collections.unmodifiableList(new ArrayList<>(issues));
        }
        return Collections.unmodifiableList(new ArrayList<>(issues.subList(0, max)));
    }

    private static void add(List<Issue> out, String id, String title, String action,
                            Severity severity, String route) {
        out.add(new Issue(id, title, action, severity, route));
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
