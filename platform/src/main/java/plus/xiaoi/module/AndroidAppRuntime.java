package plus.xiaoi.module;

import android.content.Context;

import plus.xiaoi.module.agent.AgentService;
import plus.xiaoi.module.application.runtime.AgentControl;
import plus.xiaoi.module.application.runtime.AppRuntime;
import plus.xiaoi.module.application.runtime.BackgroundTasks;
import plus.xiaoi.module.application.runtime.ConfigWatcher;
import plus.xiaoi.module.application.runtime.ControlLogStore;
import plus.xiaoi.module.application.runtime.ModelProbe;
import plus.xiaoi.module.application.runtime.NetworkInfo;
import plus.xiaoi.module.application.runtime.PermissionReader;
import plus.xiaoi.module.application.runtime.RuntimeConfigImporter;
import plus.xiaoi.module.application.runtime.StatusProbeStore;
import plus.xiaoi.module.application.runtime.UiPrefs;
import plus.xiaoi.module.application.runtime.UsageStats;
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
                new Watcher(app),
                new ControlLog(),
                new PrefsUi(app),
                new Tasks(),
                new Network(),
                new Usage(),
                new Probe());
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

    /** 控制面日志：只读内存镜像 / 清空，UI 不 import control 包。 */
    private static final class ControlLog implements ControlLogStore {
        @Override
        public String dump() {
            return plus.xiaoi.module.control.BridgeControlService.logDump();
        }

        @Override
        public void clear() {
            plus.xiaoi.module.control.BridgeControlService.clearLogs();
        }
    }

    /** UI 偏好：玻璃底栏开关（键与历史版本一致，升级不丢设置）。 */
    private static final class PrefsUi implements UiPrefs {
        private final Context ctx;

        private PrefsUi(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public boolean glassBottomBar() {
            return ctx.getSharedPreferences(Prefs.UI_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.KEY_GLASS_BOTTOM_BAR, false);
        }

        @Override
        public void setGlassBottomBar(boolean enabled) {
            ctx.getSharedPreferences(Prefs.UI_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(Prefs.KEY_GLASS_BOTTOM_BAR, enabled).apply();
        }
    }

    /** 后台线程：App 进程唯一的 AppExecutors。 */
    private static final class Tasks implements BackgroundTasks {
        @Override
        public void io(Runnable task) {
            AppExecutors.io(task);
        }

        @Override
        public void main(Runnable task) {
            AppExecutors.ui(task);
        }
    }

    /** 默认端口与本机 IPv4。 */
    private static final class Network implements NetworkInfo {
        @Override
        public int defaultBridgePort() {
            return Bridge.PORT_START;
        }

        @Override
        public int defaultAgentPort() {
            return plus.xiaoi.module.agent.AgentHttpServer.DEFAULT_PORT;
        }

        @Override
        public String lanIpv4() {
            return Lan.ipv4();
        }
    }

    /** 8771 /stats 用量。 */
    private static final class Usage implements UsageStats {
        @Override
        public String fetch(int port, String token, int days) {
            return BridgeClient.stats(port, token, days);
        }
    }

    /** 模型端点探测。 */
    private static final class Probe implements ModelProbe {
        @Override
        public ModelProbe.Result probe(String baseUrl, String apiKey, String model) {
            ModelValidator.Result r = ModelValidator.check(baseUrl, apiKey, model);
            return new ModelProbe.Result(r.ok, r.code, r.message);
        }
    }
}