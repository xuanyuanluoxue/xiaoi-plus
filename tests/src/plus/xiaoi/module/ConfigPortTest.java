package plus.xiaoi.module;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.application.config.ConfigDefaults;
import plus.xiaoi.module.application.config.ConfigEffectsPort;
import plus.xiaoi.module.application.config.ConfigLogPort;
import plus.xiaoi.module.application.config.ConfigPrefsPort;
import plus.xiaoi.module.application.config.ConfigService;
import plus.xiaoi.module.application.config.ConfigStorePort;
import plus.xiaoi.module.core.config.ConfigBackupInfo;
import plus.xiaoi.module.core.config.ConfigLoadResult;
import plus.xiaoi.module.core.config.ConfigSaveResult;

/**
 * WP7：配置用例只依赖 {@link ConfigStorePort}。
 *
 * <p>用**纯内存**实现跑完整套用例（骨架/保存/冲突/损坏只读/恢复），
 * 证明 {@link ConfigService} 不需要文件系统、也不需要 java.io。</p>
 */
public final class ConfigPortTest {

    private ConfigPortTest() {}

    public static int run() throws Exception {
        emptyIsSkeleton();
        saveAndRevConflict();
        corruptionIsReadOnly();
        restoreKeepsRevMonotonic();
        storageComesFromPort();
        return T.report("ConfigPortTest");
    }

    // ---------------------------------------------------------------- 用例

    private static void emptyIsSkeleton() {
        FakeStore store = new FakeStore();
        ConfigService s = service(store, new FakePrefs(), new FakeEffects());

        ConfigLoadResult r = s.loadState();
        T.ok("空存储：skeleton", r.skeleton);
        T.eq("空存储：rev=0", 0L, r.config.rev);
        T.eq("空存储：不写文件", 0, store.files.size());
        T.ok("空存储：非只读", !r.readOnly);
    }

    private static void saveAndRevConflict() {
        FakeStore store = new FakeStore();
        FakePrefs prefs = new FakePrefs();
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(store, prefs, fx);

        AppConfig c = config();
        ConfigSaveResult first = s.saveIfRev(c, 0L);
        T.ok("保存：成功", first.ok());
        T.eq("保存：rev=2", 2L, first.saved.rev);
        T.ok("保存：写入端口", store.files.containsKey(ConfigStorePort.FILE_NAME));
        T.eq("保存：prefs 收到镜像", 1, prefs.writes);
        T.ok("保存：触发 onApplied", fx.applied == 1);
        T.ok("保存：请求 reload", fx.reloads == 1);

        ConfigSaveResult stale = s.saveIfRev(config(), 1L);
        T.ok("冲突：拒绝旧 rev", !stale.ok());
        T.eq("冲突：回报当前 rev", 2L, stale.conflictRev);
        T.eq("冲突：没有多写一次", 2L, T_lastWriteRev(store));
    }

    private static void corruptionIsReadOnly() throws Exception {
        FakeStore store = new FakeStore();
        store.writeAtomic("{ this is not json");
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(store, new FakePrefs(), fx);

        ConfigLoadResult r = s.loadState();
        T.ok("损坏：只读", r.readOnly);
        T.ok("损坏：返回骨架", r.skeleton);
        T.ok("损坏：记录了错误文案", r.error.length() > 0);
        T.eq("损坏：生成 .corrupt 备份", 1, countPrefix(store, ConfigStorePort.CORRUPT_PREFIX));

        ConfigSaveResult saved = s.saveIfRev(config(), 1L);
        T.ok("损坏：保存被拒绝", !saved.ok());
        T.eq("损坏：拒绝时不触发 reload", 0, fx.reloads);

        AppConfig rebuilt = s.resetToDefaultsAfterCorruption();
        T.ok("损坏：重置后可写", rebuilt != null);
        T.ok("损坏：重置后清除只读", !s.isReadOnly());
        T.ok("损坏：重置写入事实源", store.read() != null);
    }

    private static void restoreKeepsRevMonotonic() throws Exception {
        FakeStore store = new FakeStore();
        store.writeAtomic(plus.xiaoi.module.ConfigCodec.toJson(config(7L)));
        String backupName = ConfigStorePort.LEGACY_PREFIX + "100";
        store.files.put(backupName, plus.xiaoi.module.ConfigCodec.toJson(config(3L)));
        store.mtimes.put(backupName, 200L);

        ConfigService s = service(store, new FakePrefs(), new FakeEffects());
        T.eq("恢复：备份列表只有名字", 1, s.legacyBackupNames().size());

        AppConfig restored = s.restore(new ConfigBackupInfo(backupName, 200L, 1L, true));
        T.ok("恢复：rev 不回退（> 7）", restored.rev > 7L);
    }

    private static void storageComesFromPort() {
        FakeStore store = new FakeStore();
        store.writeAtomic(plus.xiaoi.module.ConfigCodec.toJson(config(1L)));
        ConfigService s = service(store, new FakePrefs(), new FakeEffects());

        T.eq("storage：路径来自端口", "/memory/config.json", s.storage().path);
        T.ok("storage：exists", s.storage().exists);
        T.ok("storage：size > 0", s.storage().size > 0);
        T.ok("storage：mtime > 0", s.storage().lastModified > 0);
    }

    // ---------------------------------------------------------------- 工具

    private static ConfigService service(ConfigStorePort store, FakePrefs prefs, FakeEffects fx) {
        return new ConfigService(store, prefs, fx, ConfigLogPort.NONE);
    }

    private static AppConfig config() {
        return config(0L);
    }

    private static AppConfig config(long rev) {
        AppConfig c = plus.xiaoi.module.ConfigCodec.defaults(
                "https://api.example.com/v1", "m1", "sk-test", true, false,
                "0123456789abcdef0123456789abcdef");
        c.enabled = true;
        c.rev = rev;
        return c;
    }

    private static long T_lastWriteRev(FakeStore store) {
        String raw = store.read();
        if (raw == null) return -1L;
        try {
            return plus.xiaoi.module.ConfigCodec.fromJson(raw).rev;
        } catch (Throwable t) {
            return -2L;
        }
    }

    private static int countPrefix(FakeStore store, String prefix) {
        int n = 0;
        for (String name : store.files.keySet()) {
            if (name.startsWith(prefix)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 假实现

    /** 纯内存存储：没有任何文件系统调用。 */
    private static final class FakeStore implements ConfigStorePort {
        final Map<String, String> files = new LinkedHashMap<String, String>();
        final Map<String, Long> mtimes = new LinkedHashMap<String, Long>();
        long clock = 1000L;

        @Override
        public String path() {
            return "/memory/config.json";
        }

        @Override
        public boolean exists() {
            return files.containsKey(FILE_NAME);
        }

        @Override
        public long size() {
            return size(FILE_NAME);
        }

        @Override
        public long lastModified() {
            return lastModified(FILE_NAME);
        }

        @Override
        public long size(String name) {
            String s = files.get(name);
            return s == null ? 0L : s.getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public long lastModified(String name) {
            Long t = mtimes.get(name);
            return t == null ? 0L : t;
        }

        @Override
        public String read() {
            return files.get(FILE_NAME);
        }

        @Override
        public String readNamed(String name) {
            return files.get(name);
        }

        @Override
        public void writeAtomic(String json) {
            clock += 10L;
            files.put(FILE_NAME, json);
            mtimes.put(FILE_NAME, clock);
        }

        @Override
        public List<String> listNames(String prefix) {
            String p = prefix == null ? "" : prefix;
            List<String> out = new ArrayList<String>();
            for (String name : files.keySet()) {
                if (p.length() == 0 || name.startsWith(p)) out.add(name);
            }
            Collections.sort(out, new Comparator<String>() {
                public int compare(String a, String b) {
                    long ta = lastModified(a);
                    long tb = lastModified(b);
                    if (ta != tb) return ta > tb ? -1 : 1;
                    return b.compareTo(a);
                }
            });
            return out;
        }

        @Override
        public void backupCorrupt(String raw) {
            clock += 10L;
            String name = CORRUPT_PREFIX + clock;
            files.put(name, raw == null ? "" : raw);
            mtimes.put(name, clock);
        }

        @Override
        public void moveCorruptAside() {
            String raw = files.remove(FILE_NAME);
            mtimes.remove(FILE_NAME);
            if (raw != null) backupCorrupt(raw);
        }

        @Override
        public void restoreNamed(String name) {
            String raw = files.get(name);
            if (raw == null) throw new Failure("备份文件不存在");
            writeAtomic(raw);
        }

        @Override
        public boolean migrateLegacy() {
            return false;
        }
    }

    private static final class FakePrefs implements ConfigPrefsPort {
        int writes;

        @Override
        public ConfigDefaults readDefaults() {
            return new ConfigDefaults("https://api.example.com/v1", "m1", "sk-test",
                    true, false, "0123456789abcdef0123456789abcdef", 0L);
        }

        @Override
        public boolean write(AppConfig config) {
            writes++;
            return true;
        }
    }

    private static final class FakeEffects implements ConfigEffectsPort {
        int applied;
        int reloads;

        @Override
        public void onApplied() {
            applied++;
        }

        @Override
        public boolean requestReload() {
            reloads++;
            return true;
        }
    }
}
