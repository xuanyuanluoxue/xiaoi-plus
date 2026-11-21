package plus.xiaoi.module;

import java.util.HashMap;
import java.util.Map;

import plus.xiaoi.module.application.runtime.AgentControl;
import plus.xiaoi.module.application.runtime.AppRuntime;
import plus.xiaoi.module.application.runtime.BackgroundTasks;
import plus.xiaoi.module.application.runtime.ConfigWatcher;
import plus.xiaoi.module.application.runtime.ControlLogStore;
import plus.xiaoi.module.application.runtime.MemoryAccess;
import plus.xiaoi.module.application.runtime.ModelProbe;
import plus.xiaoi.module.application.runtime.NetworkInfo;
import plus.xiaoi.module.application.runtime.PermissionReader;
import plus.xiaoi.module.application.runtime.RuntimeConfigImporter;
import plus.xiaoi.module.application.runtime.StatusProbeStore;
import plus.xiaoi.module.application.runtime.UiPrefs;
import plus.xiaoi.module.application.runtime.UsageStats;
import plus.xiaoi.module.core.PermissionSnapshot;
import plus.xiaoi.module.StatusResolver;

/** WP4：AppRuntime 端口集合与 fake 契约。 */
public final class AppRuntimeTest {

    private AppRuntimeTest() {}

    public static int run() {
        FakeAgent agent = new FakeAgent();
        FakeStatus status = new FakeStatus();
        FakePermissions permissions = new FakePermissions();
        FakeImporter importer = new FakeImporter();
        FakeWatcher watcher = new FakeWatcher();
        FakeControlLog controlLog = new FakeControlLog();
        FakeHost host = new FakeHost();
        AppRuntime runtime = new AppRuntime(agent, status, permissions, importer, watcher,
                controlLog, host, host, host, host, host, host);

        runtime.agent.startIfEnabled();
        runtime.agent.startIfEnabled();
        runtime.agent.stop();
        T.eq("agent start 次数", 2, agent.starts);
        T.eq("agent stop 次数", 1, agent.stops);

        runtime.status.save("https://api", "sk", "m1", StatusResolver.Probe.OK);
        T.eq("status 读回命中", StatusResolver.Probe.OK,
                runtime.status.read("https://api", "sk", "m1"));
        T.eq("status 指纹不同 → UNKNOWN", StatusResolver.Probe.UNKNOWN,
                runtime.status.read("https://api", "sk", "m2"));

        T.ok("permissions 返回同一快照", runtime.permissions.capture() == permissions.snapshot);

        T.ok("import 成功", runtime.configImport.importFromRuntime());
        T.eq("import 次数", 1, importer.calls);

        runtime.watcher.start();
        T.eq("watcher start 次数", 1, watcher.starts);

        controlLog.text = "10:00:00.000 bridge|mcp ok";
        T.eq("controlLog 读回镜像", "10:00:00.000 bridge|mcp ok", runtime.controlLog.dump());
        runtime.controlLog.clear();
        T.eq("controlLog 清空后为空", "", runtime.controlLog.dump());
        T.eq("controlLog clear 次数", 1, controlLog.clears);

        T.ok("prefs 默认关玻璃", !runtime.prefs.glassBottomBar());
        runtime.prefs.setGlassBottomBar(true);
        T.ok("prefs 写入后可读", runtime.prefs.glassBottomBar());

        runtime.tasks.io(new Runnable() {
            @Override
            public void run() {
                host.ioRuns++;
            }
        });
        runtime.tasks.main(new Runnable() {
            @Override
            public void run() {
                host.mainRuns++;
            }
        });
        T.eq("tasks.io 走端口", 1, host.ioRuns);
        T.eq("tasks.main 走端口", 1, host.mainRuns);

        T.eq("network 默认桥端口", 8771, runtime.network.defaultBridgePort());
        T.eq("network 默认 Agent 端口", 8770, runtime.network.defaultAgentPort());

        T.eq("usage 走端口", "{\"today\":{}}", runtime.usage.fetch(8771, "t", 1));
        T.ok("probe 走端口", runtime.probe.probe("https://api", "sk", "m1").ok);

        T.ok("memory 走端口",
                runtime.memory.call("append", "长期记忆", "x", 0).contains("\"ok\":true"));
        T.eq("memory op 透传", "append", host.memoryOp);
        T.eq("memory section 透传", "长期记忆", host.memorySection);
        T.eq("memory text 透传", "x", host.memoryText);

        boolean threw = false;
        try {
            new AppRuntime(null, status, permissions, importer, watcher,
                    controlLog, host, host, host, host, host, host);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        T.ok("空端口被拒绝", threw);

        boolean threwLog = false;
        try {
            new AppRuntime(agent, status, permissions, importer, watcher,
                    null, host, host, host, host, host, host);
        } catch (IllegalArgumentException e) {
            threwLog = true;
        }
        T.ok("空日志端口被拒绝", threwLog);

        boolean threwProbe = false;
        try {
            new AppRuntime(agent, status, permissions, importer, watcher,
                    controlLog, host, host, host, host, null, host);
        } catch (IllegalArgumentException e) {
            threwProbe = true;
        }
        T.ok("空探测端口被拒绝", threwProbe);

        boolean threwMemory = false;
        try {
            new AppRuntime(agent, status, permissions, importer, watcher,
                    controlLog, host, host, host, host, host, null);
        } catch (IllegalArgumentException e) {
            threwMemory = true;
        }
        T.ok("空记忆端口被拒绝", threwMemory);

        return T.report("AppRuntimeTest");
    }

    private static final class FakeAgent implements AgentControl {
        int starts;
        int stops;

        @Override
        public void startIfEnabled() {
            starts++;
        }

        @Override
        public void stop() {
            stops++;
        }
    }

    private static final class FakeStatus implements StatusProbeStore {
        final Map<String, StatusResolver.Probe> values = new HashMap<String, StatusResolver.Probe>();

        @Override
        public StatusResolver.Probe read(String baseUrl, String apiKey, String model) {
            StatusResolver.Probe p = values.get(key(baseUrl, apiKey, model));
            return p == null ? StatusResolver.Probe.UNKNOWN : p;
        }

        @Override
        public void save(String baseUrl, String apiKey, String model, StatusResolver.Probe probe) {
            values.put(key(baseUrl, apiKey, model), probe);
        }

        private String key(String a, String b, String c) {
            return (a == null ? "" : a) + "|" + (b == null ? "" : b) + "|" + (c == null ? "" : c);
        }
    }

    private static final class FakePermissions implements PermissionReader {
        final PermissionSnapshot snapshot = PermissionSnapshot.unknown();

        @Override
        public PermissionSnapshot capture() {
            return snapshot;
        }
    }

    private static final class FakeImporter implements RuntimeConfigImporter {
        int calls;

        @Override
        public boolean importFromRuntime() {
            calls++;
            return true;
        }
    }

    private static final class FakeWatcher implements ConfigWatcher {
        int starts;

        @Override
        public void start() {
            starts++;
        }
    }

    private static final class FakeControlLog implements ControlLogStore {
        String text = "";
        String json = "";
        String historyText = "";
        int clears;

        @Override
        public String dump() {
            return text;
        }

        @Override
        public String dumpJson(int limit, long since) {
            return json;
        }

        @Override
        public String history(int limit) {
            return historyText;
        }

        @Override
        public void clear() {
            text = "";
            clears++;
        }
    }

    /** WP11：一次实现 5 个新端口（UI 偏好/线程/网络/用量/探测）；P2 再并入记忆端口。 */
    private static final class FakeHost implements UiPrefs, BackgroundTasks,
            NetworkInfo, UsageStats, ModelProbe, MemoryAccess {
        boolean glass;
        int ioRuns;
        int mainRuns;
        String memoryOp = "";
        String memorySection = "";
        String memoryText = "";
        int memoryLimit = -1;

        @Override
        public boolean glassBottomBar() {
            return glass;
        }

        @Override
        public void setGlassBottomBar(boolean enabled) {
            glass = enabled;
        }

        @Override
        public void io(Runnable task) {
            task.run();
        }

        @Override
        public void main(Runnable task) {
            task.run();
        }

        @Override
        public int defaultBridgePort() {
            return 8771;
        }

        @Override
        public int defaultAgentPort() {
            return 8770;
        }

        @Override
        public String lanIpv4() {
            return "192.168.0.118";
        }

        @Override
        public String fetch(int port, String token, int days) {
            return "{\"today\":{}}";
        }

        @Override
        public ModelProbe.Result probe(String baseUrl, String apiKey, String model) {
            return new ModelProbe.Result(true, "ok", "通过");
        }

        @Override
        public String call(String op, String section, String text, int limit) {
            memoryOp = op;
            memorySection = section;
            memoryText = text;
            memoryLimit = limit;
            return "{\"ok\":true,\"code\":\"OK\"}";
        }
    }
}