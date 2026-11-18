package plus.xiaoi.module.core;

import plus.xiaoi.module.AppStatus;

/**
 * 四维状态模型（纯 JVM，无 android 依赖）。
 *
 * <p>四个维度互不伪造，页面只负责展示，不再各自 if/else：</p>
 * <ul>
 *   <li>D1 config：文件是否完整/暂停/损坏；<b>只要求</b> provider/model/key/base 齐全，
 *       不要求端点可达。</li>
 *   <li>D2 endpoint：只由 {@code ModelValidator} / {@code StatusCache} 的探针结论决定。</li>
 *   <li>D3 applied：控制面 leader 是否已加载 {@code fileRev}；超过 30s 无上报即 STALE。</li>
 *   <li>D4 runtime：控制面上报的小爱运行态；<b>只有 IN_USE 能叫「生效」</b>。</li>
 * </ul>
 *
 * <p>为什么 D2 成功不能改 D4：校验只是向端点发了一次请求，小爱可能仍暂停、
 * 仍用旧配置，甚至没有启动。把「模型服务可用」说成「小爱已生效」是 beta.4 的主要歧义。</p>
 */
public final class StateModel {

    /** 控制面上报超过这个时间未更新，就不再把小爱当成在线/生效。 */
    public static final long STALE_MS = 30_000L;

    private StateModel() {}

    /** D2 的输入枚举；CHECKING 表示 App 正在发探针。 */
    public enum Probe { UNKNOWN, OK, AUTH, MODEL, ENDPOINT, CHECKING }

    /** D1：配置维度。 */
    public enum ConfigState { NOT_CONFIGURED, PAUSED, READY, INVALID }

    /** D2：端点维度。 */
    public enum EndpointState { UNKNOWN, CHECKING, OK, AUTH, MODEL, ENDPOINT }

    /** D3：已应用维度。 */
    public enum AppliedState { UNKNOWN, WAITING, APPLIED, STALE }

    /** D4：运行时维度。 */
    public enum RuntimeState { UNKNOWN, IN_USE, MODEL_MISMATCH, DISABLED, NO_KEY }

    public static final class Input {
        // D1
        public boolean hasProvider;
        public boolean hasModel;
        public boolean hasKey;
        public boolean hasBase;
        public boolean enabled;
        /** 配置文件损坏/只读。 */
        public boolean configInvalid;

        // D2
        public Probe probe = Probe.UNKNOWN;

        // D3 / D4 共享控制面快照
        public boolean bridgeConnected;
        public long fileRev;
        public long appliedRev;
        public long lastReportAt;
        public long now;

        // D4
        /** 收到过 reportState；只有 register 时运行态仍未知。 */
        public boolean runtimeKnown = true;
        public boolean runtimeEnabled;
        public boolean runtimeHasKey;
        public String runtimeModel = "";
        public String fileModel = "";
    }

    public static final class Result {
        public final ConfigState d1;
        public final EndpointState d2;
        public final AppliedState d3;
        public final RuntimeState d4;
        private final boolean connected;
        private final boolean runtimeKnown;
        private final boolean stale;
        private final String runtimeModel;
        private final String fileModel;

        Result(ConfigState d1, EndpointState d2, AppliedState d3, RuntimeState d4,
               boolean connected, boolean runtimeKnown, boolean stale,
               String runtimeModel, String fileModel) {
            this.d1 = d1;
            this.d2 = d2;
            this.d3 = d3;
            this.d4 = d4;
            this.connected = connected;
            this.runtimeKnown = runtimeKnown;
            this.stale = stale;
            this.runtimeModel = runtimeModel == null ? "" : runtimeModel.trim();
            this.fileModel = fileModel == null ? "" : fileModel.trim();
        }

        /** 只有小爱明确上报「正在用」才算生效。 */
        public boolean verified() {
            return d4 == RuntimeState.IN_USE;
        }

        /** 配置本身是否可用（不涉及端点与运行态）。 */
        public boolean configReady() {
            return d1 == ConfigState.READY;
        }

        /** Hero 主标题；D2 的成功文案是「模型服务可用」，绝不写成「生效」。 */
        public String heroTitle() {
            switch (d1) {
                case NOT_CONFIGURED: return "未配置";
                case INVALID: return "配置异常";
                case PAUSED: return "已暂停";
                case READY:
                default:
                    break;
            }
            switch (d4) {
                case IN_USE: return "第三方模型已生效";
                case MODEL_MISMATCH: return "等待小爱应用";
                case DISABLED: return "小爱未启用";
                case NO_KEY: return "缺少密钥";
                case UNKNOWN:
                default:
                    break;
            }
            switch (d2) {
                case CHECKING: return "校验中";
                case OK: return "模型服务可用";
                case AUTH: return "密钥无效";
                case MODEL: return "模型不存在";
                case ENDPOINT: return "端点不可达";
                case UNKNOWN:
                default: return "等待小爱";
            }
        }

        /** Hero 副行；把「等待上报/未连接/超过 30s」讲清楚，不假装在线。 */
        public String heroSub() {
            if (d1 == ConfigState.NOT_CONFIGURED) return "选择服务商、模型并填写密钥后即可启用";
            if (d1 == ConfigState.INVALID) return "配置文件损坏，已保留原文件";
            if (d1 == ConfigState.PAUSED) return "暂停只停重定向，配置可继续编辑";

            switch (d4) {
                case IN_USE:
                    return "小爱正在使用 " + text(runtimeModel, fileModel);
                case MODEL_MISMATCH:
                    if (text(runtimeModel, "").length() == 0) {
                        return "等待小爱上报运行模型 · 文件 " + text(fileModel, "未选择");
                    }
                    return "小爱在用 " + runtimeModel + "（文件 " + text(fileModel, "未选择") + "）";
                case DISABLED:
                    return "小爱侧未启用重定向 · 文件配置已保存";
                case NO_KEY:
                    return "小爱侧缺少密钥 · 填写并保存后再应用";
                case UNKNOWN:
                default:
                    break;
            }

            if (!connected) return "小爱未连接 · 已保存，等待小爱上报";
            if (!runtimeKnown) return "等待小爱上报运行状态";
            if (stale) return "超过 30 秒未上报 · 不确认在线";
            switch (d2) {
                case OK: return "模型服务可用 · 等待小爱使用";
                case CHECKING: return "正在校验端点与模型…";
                case AUTH: return "密钥无效或被拒 · 检查密钥是否属于该服务商";
                case MODEL: return "模型不存在 · 检查模型 ID 是否拼写正确";
                case ENDPOINT: return "端点不可达 · 检查地址、网络或账号配额";
                case UNKNOWN:
                default: return "已配置 · 点按校验端点";
            }
        }

        /** 给现有 UI 颜色令牌用的投影；不参与任何「生效」判断。 */
        public AppStatus status() {
            if (d1 == ConfigState.INVALID) return AppStatus.ENDPOINT;
            if (d1 == ConfigState.NOT_CONFIGURED) return AppStatus.NOT_CONFIGURED;
            if (d1 == ConfigState.PAUSED) return AppStatus.PAUSED;
            if (d4 == RuntimeState.IN_USE) return AppStatus.OK;
            if (d2 == EndpointState.OK) return AppStatus.OK;
            if (d2 == EndpointState.CHECKING) return AppStatus.CHECKING;
            if (d2 == EndpointState.AUTH) return AppStatus.AUTH;
            if (d2 == EndpointState.MODEL) return AppStatus.MODEL;
            if (d2 == EndpointState.ENDPOINT) return AppStatus.ENDPOINT;
            return AppStatus.ENABLED;
        }

    }

    public static Result resolve(Input in) {
        if (in == null) in = new Input();
        long now = in.now > 0 ? in.now : System.currentTimeMillis();

        ConfigState d1 = resolveConfig(in);
        EndpointState d2 = resolveEndpoint(in.probe);
        AppliedState d3 = resolveApplied(in, now);
        RuntimeState d4 = resolveRuntime(in, now);
        boolean stale = in.bridgeConnected && !fresh(in.lastReportAt, now);
        return new Result(d1, d2, d3, d4,
                in.bridgeConnected, in.runtimeKnown, stale,
                in.runtimeModel, in.fileModel);
    }

    public static ConfigState resolveConfig(Input in) {
        if (in == null) return ConfigState.NOT_CONFIGURED;
        if (in.configInvalid) return ConfigState.INVALID;
        if (!in.hasProvider || !in.hasModel || !in.hasBase || !in.hasKey) {
            return ConfigState.NOT_CONFIGURED;
        }
        if (!in.enabled) return ConfigState.PAUSED;
        return ConfigState.READY;
    }

    public static EndpointState resolveEndpoint(Probe probe) {
        if (probe == null) return EndpointState.UNKNOWN;
        switch (probe) {
            case OK: return EndpointState.OK;
            case CHECKING: return EndpointState.CHECKING;
            case AUTH: return EndpointState.AUTH;
            case MODEL: return EndpointState.MODEL;
            case ENDPOINT: return EndpointState.ENDPOINT;
            case UNKNOWN:
            default: return EndpointState.UNKNOWN;
        }
    }

    public static AppliedState resolveApplied(Input in, long now) {
        if (in == null || !in.bridgeConnected) return AppliedState.UNKNOWN;
        if (!fresh(in.lastReportAt, now)) return AppliedState.STALE;
        return in.appliedRev >= in.fileRev ? AppliedState.APPLIED : AppliedState.WAITING;
    }

    public static RuntimeState resolveRuntime(Input in, long now) {
        if (in == null || !in.bridgeConnected || !in.runtimeKnown) return RuntimeState.UNKNOWN;
        if (!fresh(in.lastReportAt, now)) return RuntimeState.UNKNOWN;
        if (!in.runtimeEnabled) return RuntimeState.DISABLED;
        if (!in.runtimeHasKey) return RuntimeState.NO_KEY;
        String runtime = trim(in.runtimeModel);
        if (runtime.length() == 0) return RuntimeState.MODEL_MISMATCH;
        String file = trim(in.fileModel);
        if (file.length() > 0 && !file.equals(runtime)) return RuntimeState.MODEL_MISMATCH;
        return RuntimeState.IN_USE;
    }

    public static boolean fresh(long updatedAt, long now) {
        if (updatedAt <= 0) return false;
        long age = now - updatedAt;
        return age <= STALE_MS;
    }

    private static String text(String value, String fallback) {
        String v = trim(value);
        return v.length() > 0 ? v : fallback;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
