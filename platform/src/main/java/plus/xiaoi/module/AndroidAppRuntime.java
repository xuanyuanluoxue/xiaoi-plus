package plus.xiaoi.module;

import android.content.Context;

import plus.xiaoi.module.agent.AgentService;
import plus.xiaoi.module.application.runtime.AgentControl;
import plus.xiaoi.module.application.runtime.AppRuntime;
import plus.xiaoi.module.application.runtime.ConfigWatcher;
import plus.xiaoi.module.application.runtime.PermissionReader;
import plus.xiaoi.module.application.runtime.RuntimeConfigImporter;
import plus.xiaoi.module.application.runtime.StatusProbeStore;
import plus.xiaoi.module.core.PermissionProbe;
import plus.xiaoi.module.core.PermissionSnapshot;
import plus.xiaoi.module.StatusResolver;

/**
 * UI 运行时端口的 Android 实现（WP4）。
 *
 * <p>所有 adapter 统一持有 applicationContext；Compose 页面只通过
 * {@link AppRuntime} 调用，不再直接依赖 AgentService/PermissionProbe/StatusCache/ConfigSync。</p>
 */
public final class AndroidAppRuntime {

    private AndroidAppRuntime() {}

    public static AppRuntime create(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        return new AppRuntime(
                new Agent(app),
                new Status(app),
                new Permissions(app),
                new Importer(app),
                new Watcher(app));
    }

    private static final class Agent implements AgentControl {
        private final Context ctx;

        private Agent(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void startIfEnabled() {
            AgentService.startIfEnabled(ctx);
        }

        @Override
        public void stop() {
            AgentService.stop(ctx);
        }
    }

    private static final class Status implements StatusProbeStore {
        private final Context ctx;

        private Status(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public StatusResolver.Probe read(String baseUrl, String apiKey, String model) {
            return StatusCache.probe(ctx, StatusCache.fingerprint(baseUrl, apiKey, model));
        }

        @Override
        public void save(String baseUrl, String apiKey, String model,
                         StatusResolver.Probe probe) {
            StatusCache.saveProbe(ctx, StatusCache.fingerprint(baseUrl, apiKey, model), probe);
        }
    }

    private static final class Permissions implements PermissionReader {
        private final Context ctx;

        private Permissions(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public PermissionSnapshot capture() {
            return PermissionProbe.capture(ctx);
        }
    }

    private static final class Importer implements RuntimeConfigImporter {
        private final Context ctx;

        private Importer(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean importFromRuntime() {
            return ConfigSync.importFromRuntime(ctx);
        }
    }

    private static final class Watcher implements ConfigWatcher {
        private final Context ctx;

        private Watcher(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void start() {
            ConfigSync.start(ctx);
        }
    }
}