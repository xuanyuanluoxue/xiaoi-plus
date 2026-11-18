package plus.xiaoi.module;

import java.util.HashMap;
import java.util.Map;

import plus.xiaoi.module.application.runtime.AgentControl;
import plus.xiaoi.module.application.runtime.AppRuntime;
import plus.xiaoi.module.application.runtime.ConfigWatcher;
import plus.xiaoi.module.application.runtime.PermissionReader;
import plus.xiaoi.module.application.runtime.RuntimeConfigImporter;
import plus.xiaoi.module.application.runtime.StatusProbeStore;
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
        AppRuntime runtime = new AppRuntime(agent, status, permissions, importer, watcher);

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

        boolean threw = false;
        try {
            new AppRuntime(null, status, permissions, importer, watcher);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        T.ok("空端口被拒绝", threw);

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
}