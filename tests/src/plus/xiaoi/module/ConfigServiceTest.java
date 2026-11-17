package plus.xiaoi.module;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.application.config.ConfigDefaults;
import plus.xiaoi.module.application.config.ConfigEffectsPort;
import plus.xiaoi.module.application.config.ConfigLogPort;
import plus.xiaoi.module.application.config.ConfigPrefsPort;
import plus.xiaoi.module.application.config.ConfigService;
import plus.xiaoi.module.core.ConfigStore;
import plus.xiaoi.module.core.config.ConfigBackupInfo;
import plus.xiaoi.module.core.config.ConfigLoadResult;
import plus.xiaoi.module.core.config.ConfigSaveResult;
import plus.xiaoi.module.core.config.ConfigStorageInfo;

/**
 * WP1b：配置用例纯 JVM 测试。
 *
 * <p>用临时目录 + fake prefs/effects 覆盖：骨架、保存 rev、冲突、损坏只读、
 * 旧文件迁移、恢复、重置 rev 单调、prefs 镜像与外部效应。</p>
 */
public final class ConfigServiceTest {

    private ConfigServiceTest() {}

    public static int run() throws Exception {
        File base = Files.createTempDirectory("xiaoi-configservice-").toFile();
        try {
            skeleton(base);
            saveAndConflict(base);
            corrupt(base);
            migration(base);
            restoreAndReset(base);
            applyModes(base);
            storageAndBackups(base);
            v2RevZero(base);
        } finally {
            deleteRecursive(base);
        }
        return T.report("ConfigServiceTest");
    }

    // ---------------------------------------------------------------- 场景

    private static void skeleton(File base) throws Exception {
        File dir = new File(base, "skeleton");
        FakePrefs prefs = new FakePrefs();
        prefs.defaults = defaults(0L);
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, null);

        ConfigLoadResult r = s.loadState();
        T.ok("骨架：文件不存在", r.skeleton);
        T.ok("骨架：非只读", !r.readOnly);
        T.eq("骨架：rev=0", 0L, r.config.rev);
        T.ok("骨架：不写文件", !new File(dir, "config.json").exists());

        r.config.enabled = true;
        ConfigSaveResult saved = s.saveIfRev(r.config, 0L);
        T.ok("骨架保存：成功", saved.ok());
        T.eq("骨架保存：rev=2", 2L, saved.saved.rev);
        T.ok("骨架保存：写文件", new File(dir, "config.json").isFile());
        T.eq("骨架保存：prefs 写 1 次", 1, prefs.writes);
        T.eq("骨架保存：applied 1 次", 1, fx.applied);
        T.eq("骨架保存：reload 1 次", 1, fx.reloads);
    }

    private static void saveAndConflict(File base) throws Exception {
        File dir = new File(base, "save");
        FakePrefs prefs = new FakePrefs();
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, null);

        ConfigLoadResult r = s.loadState();
        r.config.enabled = true;
        ConfigSaveResult first = s.saveIfRev(r.config, 0L);
        T.ok("保存：首次成功", first.ok());
        T.eq("保存：rev=2", 2L, first.saved.rev);

        AppConfig stale = config(2L);
        ConfigSaveResult conflict = s.saveIfRev(stale, 1L);
        T.ok("冲突：不成功", !conflict.ok());
        T.ok("冲突：标记冲突", conflict.conflict());
        T.eq("冲突：返回当前 rev", 2L, conflict.conflictRev);
        T.eq("冲突：不写 prefs", 1, prefs.writes);

        ConfigSaveResult force = s.saveIfRev(stale, -1L);
        T.ok("强制覆盖：成功", force.ok());
        T.eq("强制覆盖：rev=3", 3L, force.saved.rev);

        int applied = fx.applied;
        int reloads = fx.reloads;
        ConfigSaveResult quiet = s.saveIfRevQuiet(stale, 3L);
        T.ok("静默保存：成功", quiet.ok());
        T.eq("静默保存：不回填 applied", applied, fx.applied);
        T.eq("静默保存：仍请求 reload", reloads + 1, fx.reloads);
    }

    private static void corrupt(File base) throws Exception {
        File dir = new File(base, "corrupt");
        write(new File(dir, "config.json"), "{not-json");
        FakePrefs prefs = new FakePrefs();
        prefs.defaults = defaults(4L);
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, null);

        ConfigLoadResult r = s.loadState();
        T.ok("损坏：只读", r.readOnly);
        T.ok("损坏：骨架", r.skeleton);
        T.ok("损坏：有错误文案", r.error.length() > 0);
        T.ok("损坏：生成 .corrupt", hasPrefix(dir, ConfigStore.CORRUPT_PREFIX));
        T.ok("损坏：不覆盖原文件", read(new File(dir, "config.json")).startsWith("{not-json"));

        ConfigSaveResult rejected = s.saveIfRev(config(0L), -1L);
        T.ok("损坏：保存被拒", !rejected.ok());
        T.eq("损坏：未写 prefs", 0, prefs.writes);
    }

    private static void migration(File base) throws Exception {
        File dir = new File(base, "migrate");
        File legacy = new File(base, "legacy.json");
        write(legacy, plus.xiaoi.module.ConfigCodec.toJson(config(7L)));
        FakePrefs prefs = new FakePrefs();
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, legacy);

        ConfigLoadResult r = s.loadState();
        T.ok("迁移：解析成功", !r.skeleton);
        T.ok("迁移：私有文件已创建", new File(dir, "config.json").isFile());
        T.ok("迁移：外部文件已删除", !legacy.exists());
        T.ok("迁移：rev 不为 0", r.config.rev >= 1L);
    }

    private static void restoreAndReset(File base) throws Exception {
        File dir = new File(base, "restore");
        write(new File(dir, "config.json"), plus.xiaoi.module.ConfigCodec.toJson(config(5L)));
        File backup = new File(dir, "config.json.legacy.100");
        write(backup, plus.xiaoi.module.ConfigCodec.toJson(config(3L)));
        FakePrefs prefs = new FakePrefs();
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, null);

        List<ConfigBackupInfo> infos = s.backupInfos();
        T.eq("备份：列出 1 份", 1, infos.size());
        T.ok("备份：可恢复", infos.get(0).restorable);

        int applied = fx.applied;
        AppConfig restored = s.restore(infos.get(0));
        T.ok("恢复：rev 前进", restored.rev > 5L);
        T.eq("恢复：applied 增加", applied + 1, fx.applied);

        // 损坏后重置：rev 必须从 prefs 记录的上一次 rev 前进
        File resetDir = new File(base, "reset");
        write(new File(resetDir, "config.json"), "{bad");
        FakePrefs rp = new FakePrefs();
        rp.defaults = defaults(9L);
        FakeEffects rfx = new FakeEffects();
        ConfigService rs = service(resetDir, rp, rfx, null);
        T.ok("重置前：只读", rs.loadState().readOnly);
        AppConfig reset = rs.resetAfterCorruption();
        T.eq("重置：rev 单调前进", 10L, reset.rev);
        T.ok("重置：生成 .corrupt", hasPrefix(resetDir, ConfigStore.CORRUPT_PREFIX));
        T.ok("重置：清除只读", !rs.loadState().readOnly);
        T.eq("重置：applied 增加", 1, rfx.applied);
    }

    private static void applyModes(File base) throws Exception {
        File dir = new File(base, "apply");
        write(new File(dir, "config.json"), plus.xiaoi.module.ConfigCodec.toJson(config(2L)));
        FakePrefs prefs = new FakePrefs();
        FakeEffects fx = new FakeEffects();
        ConfigService s = service(dir, prefs, fx, null);

        AppConfig c = s.loadState().config;
        prefs.writes = 0;
        fx.reloads = 0;
        s.applyLocal(c);
        T.eq("applyLocal：写 prefs", 1, prefs.writes);
        T.eq("applyLocal：不 reload", 0, fx.reloads);

        s.applyToTarget(c);
        T.eq("applyToTarget：写 prefs", 2, prefs.writes);
        T.eq("applyToTarget：reload 1 次", 1, fx.reloads);
    }

    private static void storageAndBackups(File base) throws Exception {
        File dir = new File(base, "storage");
        write(new File(dir, "config.json"), plus.xiaoi.module.ConfigCodec.toJson(config(2L)));
        write(new File(dir, "config.json.corrupt.1"), "{bad}");
        write(new File(dir, "config.json.legacy.2"), plus.xiaoi.module.ConfigCodec.toJson(config(1L)));
        ConfigService s = service(dir, new FakePrefs(), new FakeEffects(), null);

        ConfigStorageInfo info = s.storage();
        T.ok("storage：exists", info.exists);
        T.ok("storage：size > 0", info.size > 0);
        T.eq("corruptInfos：1 份", 1, s.corruptInfos().size());
        T.ok("corruptInfos：不可恢复", !s.corruptInfos().get(0).restorable);
    }

    private static void v2RevZero(File base) throws Exception {
        File dir = new File(base, "v2");
        AppConfig v2 = config(0L);
        write(new File(dir, "config.json"), plus.xiaoi.module.ConfigCodec.toJson(v2));
        FakePrefs prefs = new FakePrefs();
        ConfigService s = service(dir, prefs, new FakeEffects(), null);

        ConfigLoadResult r = s.loadState();
        T.eq("v2：载入 rev 归一为 1", 1L, r.config.rev);
        ConfigSaveResult saved = s.saveIfRev(r.config, r.config.rev);
        T.ok("v2：rev=1 可直接保存", saved.ok());
        T.eq("v2：保存后 rev=2", 2L, saved.saved.rev);
    }

    // ---------------------------------------------------------------- 工具

    private static ConfigService service(File dir, FakePrefs prefs, FakeEffects fx, File legacy) {
        return new ConfigService(new ConfigStore(dir), prefs, fx, new FakeLog(), legacy);
    }

    private static ConfigDefaults defaults(long rev) {
        return new ConfigDefaults("https://api.example.com/v1", "m1", "sk-test", true, false,
                "0123456789abcdef0123456789abcdef", rev);
    }

    private static AppConfig config(long rev) {
        AppConfig c = plus.xiaoi.module.ConfigCodec.defaults(
                "https://api.example.com/v1", "m1", "sk-test", true, false,
                "0123456789abcdef0123456789abcdef");
        c.enabled = true;
        c.rev = rev;
        return c;
    }

    private static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String read(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int n = in.read(buf);
            return new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static boolean hasPrefix(File dir, String prefix) {
        String[] names = dir.list();
        if (names == null) return false;
        for (String n : names) if (n.startsWith(prefix)) return true;
        return false;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }

    private static final class FakePrefs implements ConfigPrefsPort {
        ConfigDefaults defaults = ConfigServiceTest.defaults(0L);
        AppConfig last;
        int writes;

        @Override
        public ConfigDefaults readDefaults() {
            return defaults;
        }

        @Override
        public boolean write(AppConfig c) {
            last = c;
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

    private static final class FakeLog implements ConfigLogPort {
        final List<String> lines = new ArrayList<String>();

        @Override
        public void warn(String tag, String message) {
            lines.add(tag + ": " + message);
        }
    }
}