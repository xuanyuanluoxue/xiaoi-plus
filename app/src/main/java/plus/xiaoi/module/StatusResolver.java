package plus.xiaoi.module;

/**
 * 状态推导：把「配置是否完整 / 是否暂停 / 上次探针结果」收敛成一个
 * {@link AppStatus} + 一句副文案（纯 JVM，可单测）。
 *
 * 规则顺序固定，页面不再各自写 if/else：
 *   1. 缺服务商、模型或接口地址 → 未配置
 *   2. 暂停：无密钥 → 未配置（引导补密钥）；有密钥 → 已暂停
 *   3. 启用但无密钥 → 未配置（缺密钥不等于已启用）
 *   4. 校验中 → 校验中
 *   5. 探针结果 → 可用 / 密钥无效 / 模型不存在 / 端点不可达
 *   6. 还没校验过 → 已启用（副文案「待校验」）
 */
public final class StatusResolver {

    public enum Probe { UNKNOWN, OK, AUTH, MODEL, ENDPOINT }

    public static final class Input {
        public boolean hasProvider;
        public boolean hasModel;
        /** 默认 true：旧调用点没有这个字段时不改变既有行为。 */
        public boolean hasBase = true;
        public boolean keySet;
        public boolean enabled;
        public boolean checking;
        public Probe probe = Probe.UNKNOWN;
    }

    public static final class Result {
        public final AppStatus status;
        public final String detail;

        public Result(AppStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private StatusResolver() {}

    public static Result resolve(Input in) {
        if (in == null) in = new Input();
        if (!in.hasProvider || !in.hasModel || !in.hasBase) {
            return new Result(AppStatus.NOT_CONFIGURED, "填写接口地址并选择服务商与模型后即可启用");
        }
        if (!in.enabled) {
            if (!in.keySet) {
                return new Result(AppStatus.NOT_CONFIGURED, "填写密钥并打开上方开关即可启用");
            }
            return new Result(AppStatus.PAUSED, "配置已保留，打开上方开关即恢复");
        }
        if (!in.keySet) {
            return new Result(AppStatus.NOT_CONFIGURED, "缺少密钥 · 填写后才能启用");
        }
        if (in.checking) {
            return new Result(AppStatus.CHECKING, "正在校验端点与模型…");
        }
        switch (in.probe) {
            case OK:
                return new Result(AppStatus.OK, "响应正常 · 点按重新校验");
            case AUTH:
                return new Result(AppStatus.AUTH, "密钥无效或被拒 · 检查密钥是否属于该服务商");
            case MODEL:
                return new Result(AppStatus.MODEL, "模型不存在 · 检查模型 ID 是否拼写正确");
            case ENDPOINT:
                return new Result(AppStatus.ENDPOINT, "端点不可达 · 检查地址、网络或账号配额");
            case UNKNOWN:
            default:
                return new Result(AppStatus.ENABLED, "待校验 · 点按校验");
        }
    }

    /** 把当前状态投影回探针结果，供「重新推导」时保持结论不丢。 */
    public static Probe probeOf(AppStatus status) {
        if (status == null) return Probe.UNKNOWN;
        switch (status) {
            case OK: return Probe.OK;
            case AUTH: return Probe.AUTH;
            case MODEL: return Probe.MODEL;
            case ENDPOINT: return Probe.ENDPOINT;
            default: return Probe.UNKNOWN;
        }
    }
}
