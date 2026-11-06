package plus.xiaoi.module.control;

/**
 * 一个小爱进程在控制面上的实例记录（纯 JVM，无 android 依赖）。
 *
 * 不可变：每次 register/reportState 都产生新对象，registry 负责替换。
 * {@code port} &gt; 0 表示该进程已承担数据面（leader），与 PLAN.md 的 leader 选举一致。
 */
public final class InstanceRecord {

    /** 进程名；拿不到时为空。 */
    public final String process;
    public final int pid;
    public final int port;
    /** 0.11 旧运行时配置快照（含密钥）；只在内存，App 不落盘、不写日志。 */
    public final String legacyJson;
    public final long registeredAt;
    public final long updatedAt;

    public final String model;
    public final boolean enabled;
    public final boolean hasKey;
    public final String version;
    /** 是否已收到过 reportState（区分「刚注册」与「已有运行时状态」）。 */
    public final boolean hasState;

    private InstanceRecord(String process, int pid, int port, String legacyJson,
                           long registeredAt, long updatedAt, String model, boolean enabled,
                           boolean hasKey, String version, boolean hasState) {
        this.process = process == null ? "" : process;
        this.pid = pid;
        this.port = port;
        this.legacyJson = legacyJson == null ? "" : legacyJson;
        this.registeredAt = registeredAt;
        this.updatedAt = updatedAt;
        this.model = model == null ? "" : model;
        this.enabled = enabled;
        this.hasKey = hasKey;
        this.version = version == null ? "" : version;
        this.hasState = hasState;
    }

    public static InstanceRecord registered(String process, int pid, int port,
                                            String legacyJson, long now) {
        return new InstanceRecord(process, pid, port, legacyJson, now, now,
                "", false, false, "", false);
    }

    /**
     * 叠加运行时状态。pid/port 为 0 时保留注册值（桥可能只在 REGISTER 里带端口）。
     */
    public InstanceRecord withState(String model, boolean enabled, boolean hasKey,
                                    String version, int pid, int port, long now) {
        return new InstanceRecord(this.process,
                pid > 0 ? pid : this.pid,
                port > 0 ? port : this.port,
                this.legacyJson,
                this.registeredAt,
                now,
                model,
                enabled,
                hasKey,
                version,
                true);
    }

    /** registry 的键：优先进程名，缺失时用 pid。 */
    public String key() {
        return keyOf(process, pid);
    }

    public static String keyOf(String process, int pid) {
        if (process != null && process.length() > 0) return process;
        return "pid:" + pid;
    }

    /** 只有真正绑定监听的进程才算数据面 leader。 */
    public boolean leader() {
        return port > 0;
    }
}
