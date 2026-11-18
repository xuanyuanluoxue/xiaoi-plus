package plus.xiaoi.module.application.runtime;

/** UI 运行时端口集合（WP4）。 */
public final class AppRuntime {

    public final AgentControl agent;
    public final StatusProbeStore status;
    public final PermissionReader permissions;
    public final RuntimeConfigImporter configImport;
    public final ConfigWatcher watcher;

    public AppRuntime(AgentControl agent, StatusProbeStore status,
                      PermissionReader permissions, RuntimeConfigImporter configImport,
                      ConfigWatcher watcher) {
        if (agent == null || status == null || permissions == null
                || configImport == null || watcher == null) {
            throw new IllegalArgumentException("AppRuntime 端口不能为空");
        }
        this.agent = agent;
        this.status = status;
        this.permissions = permissions;
        this.configImport = configImport;
        this.watcher = watcher;
    }
}