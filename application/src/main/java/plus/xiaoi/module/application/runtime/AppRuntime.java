package plus.xiaoi.module.application.runtime;

/**
 * UI 运行时端口集合（WP4；WP11 补齐到 11 个，P2 增加记忆读写共 12 个）。
 *
 * <p>只放「界面需要、但不该认识实现」的能力；每个字段对应一个窄端口，
 * 由 {@code :platform} 的 {@code AndroidAppRuntime} 组装。{@code :ui} 只依赖本类，
 * 因此不 import 任何 `bridge` / `control` / `agent` / infra 实现。</p> */
public final class AppRuntime {

    /** 8770 前台服务开关。 */
    public final AgentControl agent;
    /** 模型校验结果缓存。 */
    public final StatusProbeStore status;
    /** 权限矩阵快照。 */
    public final PermissionReader permissions;
    /** 从运行中的小爱导入配置。 */
    public final RuntimeConfigImporter configImport;
    /** config.json 文件监听。 */
    public final ConfigWatcher watcher;
    /** 控制面日志镜像（读/清空）。 */
    public final ControlLogStore controlLog;
    /** UI 偏好（玻璃底栏等）。 */
    public final UiPrefs prefs;
    /** 后台线程。 */
    public final BackgroundTasks tasks;
    /** 默认端口与本机 IPv4。 */
    public final NetworkInfo network;
    /** 8771 /stats 用量。 */
    public final UsageStats usage;
    /** 模型端点探测。 */
    public final ModelProbe probe;
    /** 记忆读写（经 8771 xiaoai_memory）。 */
    public final MemoryAccess memory;

    public AppRuntime(AgentControl agent, StatusProbeStore status,
                      PermissionReader permissions, RuntimeConfigImporter configImport,
                      ConfigWatcher watcher, ControlLogStore controlLog,
                      UiPrefs prefs, BackgroundTasks tasks,
                      NetworkInfo network, UsageStats usage, ModelProbe probe,
                      MemoryAccess memory) {
        if (agent == null || status == null || permissions == null
                || configImport == null || watcher == null || controlLog == null
                || prefs == null || tasks == null || network == null
                || usage == null || probe == null || memory == null) {
            throw new IllegalArgumentException("AppRuntime 端口不能为空");
        }
        this.agent = agent;
        this.status = status;
        this.permissions = permissions;
        this.configImport = configImport;
        this.watcher = watcher;
        this.controlLog = controlLog;
        this.prefs = prefs;
        this.tasks = tasks;
        this.network = network;
        this.usage = usage;
        this.probe = probe;
        this.memory = memory;
    }
}
