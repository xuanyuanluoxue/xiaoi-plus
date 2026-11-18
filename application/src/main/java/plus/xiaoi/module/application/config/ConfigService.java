package plus.xiaoi.module.application.config;

import java.util.ArrayList;
import java.util.List;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.core.LogSettings;
import plus.xiaoi.module.core.config.ConfigBackupInfo;
import plus.xiaoi.module.core.config.ConfigGateway;
import plus.xiaoi.module.core.config.ConfigLoadResult;
import plus.xiaoi.module.core.config.ConfigRevRules;
import plus.xiaoi.module.core.config.ConfigSaveResult;
import plus.xiaoi.module.core.config.ConfigStorageInfo;

/**
 * 配置用例（WP1b，纯 JVM；WP7 起彻底零 IO）。
 *
 * <p>承接原来散在 {@code ConfigRepository} 里的规则：加载/迁移、损坏只读、
 * 骨架拒绝、rev CAS、原子写、prefs 镜像、恢复/重置。字节读写全部经
 * {@link ConfigStorePort}，因此本类不 import {@code java.io}，也没有
 * {@code File}/{@code InputStream} 出现在签名里。</p>
 *
 * <p>不依赖 Android；Android 适配器（{@code ConfigRepository}）只负责组装。</p>
 */
public final class ConfigService implements ConfigGateway {

    private final ConfigStorePort store;
    private final ConfigPrefsPort prefs;
    private final ConfigEffectsPort effects;
    private final ConfigLogPort log;

    /** 解析失败后为 true：save/saveQuiet 必须拒绝写入，直到恢复/重置。 */
    private volatile boolean readOnly;
    private volatile String loadError = "";
    private volatile boolean skeletonFlag;
    /** 上一次已备份的损坏文件指纹，避免每次 load 都生成一份含密钥的 .corrupt。 */
    private volatile long corruptStamp = Long.MIN_VALUE;
    private volatile boolean corruptBackedUp;

    public ConfigService(ConfigStorePort store, ConfigPrefsPort prefs,
                         ConfigEffectsPort effects, ConfigLogPort log) {
        if (store == null) throw new IllegalArgumentException("store == null");
        if (prefs == null) throw new IllegalArgumentException("prefs == null");
        this.store = store;
        this.prefs = prefs;
        this.effects = effects == null ? new ConfigEffectsPort() {
            @Override
            public void onApplied() {
            }

            @Override
            public boolean requestReload() {
                return false;
            }
        } : effects;
        this.log = log == null ? ConfigLogPort.NONE : log;
    }

    // ------------------------------------------------------------- 读取

    /**
     * 读取事实源。
     *
     * - 私有文件存在：解析；失败 → 备份 .corrupt、readOnly=true、返回内存骨架（不写文件）
     * - 私有不存在、外部 legacy 存在：迁移（成功后删除外部）→ 解析
     * - 都不存在：返回默认骨架（skeleton=true，rev=0），**不写文件**
     */
    public synchronized AppConfig load() {
        boolean existed = store.exists();
        try {
            // 外部旧文件只要还在就处理：私有已存在时归档为 .legacy，绝不覆盖私有。
            store.migrateLegacy();
        } catch (Throwable t) {
            log.warn("xiaoi-sync", "旧配置迁移失败（已忽略）: " + t.getClass().getSimpleName());
        }
        if (existed) {
            String raw;
            try {
                raw = store.read();
            } catch (Throwable t) {
                return corrupt(null, "配置文件读取失败");
            }
            if (raw == null) return skeleton();
            return parseExisting(raw);
        }

        // 私有目录原本没有文件：如果刚完成迁移，解析后统一到当前 schema 并写回。
        if (store.exists()) {
            try {
                String raw = store.read();
                if (raw != null) {
                    AppConfig c = parseExisting(raw);
                    if (c.skeleton) return c;
                    c.rev = Math.max(c.rev, 1L);
                    try {
                        writeFile(c);
                    } catch (Throwable t) {
                        // 写回失败也先把解析结果返回给 UI；下次加载会再次尝试升级。
                        log.warn("xiaoi-sync", "迁移后写回失败（已忽略）: " + t.getClass().getSimpleName());
                    }
                    return c;
                }
            } catch (Throwable t) {
                log.warn("xiaoi-sync", "迁移后解析失败: " + t.getClass().getSimpleName());
            }
        }
        return skeleton();
    }

    /** 兼容旧调用方；新代码应使用 {@link #loadState()}。 */
    public AppConfig loadForUi() {
        return load();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String loadError() {
        return loadError == null ? "" : loadError;
    }

    public boolean isSkeleton() {
        return skeletonFlag;
    }

    @Override
    public synchronized ConfigLoadResult loadState() {
        AppConfig c = load();
        return new ConfigLoadResult(c, readOnly, loadError(), skeletonFlag);
    }

    // ------------------------------------------------------------- 保存

    @Override
    public ConfigSaveResult saveIfRev(AppConfig next, long expectedRev) {
        return saveChecked(next, expectedRev, true);
    }

    @Override
    public ConfigSaveResult saveIfRevQuiet(AppConfig next, long expectedRev) {
        return saveChecked(next, expectedRev, false);
    }

    /**
     * 先比对文件 rev 再写入。整个检查+写入在同一把锁内，避免 UI 的“先 load 再 save”
     * 在两步之间被同进程其他写者插队（跨进程写者仍由 App 单写者约束）。
     */
    private synchronized ConfigSaveResult saveChecked(AppConfig next, long expectedRev, boolean mark) {
        if (next == null) return ConfigSaveResult.failure("配置为空");
        if (readOnly) return ConfigSaveResult.failure("配置处于只读态，已拒绝写入");
        try {
            long current = readFileRev();
            if (ConfigRevRules.conflict(expectedRev, current)) {
                return ConfigSaveResult.conflict(current);
            }
            if (mark) {
                save(next);
            } else {
                saveQuiet(next);
            }
            return ConfigSaveResult.success(next);
        } catch (Throwable t) {
            String msg = t.getMessage();
            return ConfigSaveResult.failure(msg == null || msg.length() == 0 ? "保存失败" : msg);
        }
    }

    public void save(AppConfig c) throws Exception {
        guardWritable();
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        writeWithRev(c);
        writePrefs(c);
        effects.onApplied();
        effects.requestReload();
    }

    /** 只写文件 + 请求 reload（导入场景，随后手动/自动应用）。 */
    public void saveQuiet(AppConfig c) throws Exception {
        guardWritable();
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        writeWithRev(c);
        writePrefs(c);
        effects.requestReload();
    }

    /** 只同步本地镜像（启动对齐，不触发 reload，也不改 rev）。 */
    public void applyLocal(AppConfig c) {
        if (readOnly) return;
        writePrefs(c);
    }

    /** 写 prefs 并请求小爱重新拉配置（config.json 变更后的热应用；不改 rev）。 */
    public void applyToTarget(AppConfig c) {
        if (readOnly) return;
        writePrefs(c);
        effects.requestReload();
    }

    private void guardWritable() {
        if (readOnly) {
            throw new IllegalStateException("config is read-only after corruption");
        }
    }

    /** 每次 App 保存：rev = max(内存 rev, 文件 rev, 1) + 1。 */
    private void writeWithRev(AppConfig c) throws Exception {
        long fileRev = readFileRev();
        c.rev = ConfigRevRules.nextRev(c.rev, fileRev);
        writeFile(c);
    }

    private long readFileRev() throws Exception {
        String raw = store.read();
        if (raw == null) return ConfigRevRules.normalizeFileRev(0L, false);
        try {
            return ConfigRevRules.normalizeFileRev(ConfigCodec.fromJson(raw).rev, true);
        } catch (Throwable t) {
            // 文件存在但无法解析：绝不能当成 0 然后覆盖。
            readOnly = true;
            loadError = "配置文件损坏（无法解析）";
            throw new IllegalStateException("config is read-only after corruption");
        }
    }

    private void writeFile(AppConfig c) throws Exception {
        ConfigCodec.normalize(c);
        store.writeAtomic(ConfigCodec.toJson(c));
        skeletonFlag = false;
        c.skeleton = false;
        readOnly = false;
    }

    private void writePrefs(AppConfig c) {
        try {
            LogSettings.setWire(c.logLevel);
            if (!prefs.write(c)) {
                log.warn("xiaoi-sync", "prefs 写入返回 false");
            }
        } catch (Throwable t) {
            log.warn("xiaoi-sync", "prefs 写入失败: " + t);
        }
    }

    // ------------------------------------------------------------- 备份 / 恢复

    @Override
    public List<ConfigBackupInfo> backupInfos() {
        return toBackupInfos(legacyBackupNames(), true);
    }

    @Override
    public List<ConfigBackupInfo> corruptInfos() {
        return toBackupInfos(store.listNames(ConfigStorePort.CORRUPT_PREFIX), false);
    }

    @Override
    public ConfigStorageInfo storage() {
        boolean exists = store.exists();
        return new ConfigStorageInfo(
                store.path(),
                exists,
                exists ? store.size() : 0L,
                exists ? store.lastModified() : 0L);
    }

    @Override
    public AppConfig restore(ConfigBackupInfo backup) throws Exception {
        if (backup == null || !backup.restorable) {
            throw new IllegalArgumentException("该备份不可恢复");
        }
        if (!backup.name.startsWith(ConfigStorePort.LEGACY_PREFIX)) {
            throw new IllegalArgumentException("不是 legacy 备份");
        }
        return restoreLegacyName(backup.name);
    }

    @Override
    public AppConfig resetAfterCorruption() throws Exception {
        return resetToDefaultsAfterCorruption();
    }

    /** 列出可恢复的 legacy 备份（时间倒序，只有名字，不含路径）。 */
    public List<String> legacyBackupNames() {
        return store.listNames(ConfigStorePort.LEGACY_PREFIX);
    }

    /**
     * 用一份 legacy 备份恢复事实源：解析 → 校验 → 原子写回 → 写 prefs → 清只读。
     * 任何一步失败都抛异常，且不删除/不截断原文件。
     */
    public synchronized AppConfig restoreLegacyName(String name) throws Exception {
        if (name == null || !name.startsWith(ConfigStorePort.LEGACY_PREFIX)) {
            throw new IllegalArgumentException("不是 legacy 备份");
        }
        String raw = store.readNamed(name);
        if (raw == null) throw new IllegalStateException("备份文件不存在");
        if (raw.length() == 0) throw new IllegalStateException("备份文件为空");
        AppConfig c = ConfigCodec.fromJson(raw);
        String err = ConfigCodec.validate(c);
        if (err != null) throw new IllegalStateException("备份配置无效：" + err);

        // 先原样原子恢复（失败不动目标），再更新 rev 写一次。
        long priorRev = prefs.readDefaults().rev;
        try {
            priorRev = Math.max(priorRev, readFileRev());
        } catch (Throwable ignored) {
            // 当前文件可能已损坏；以 prefs 记录的上一次 rev 作为下限。
        }
        store.restoreNamed(name);
        String restored = store.read();
        if (restored == null) throw new IllegalStateException("恢复后文件不可读");
        AppConfig written = ConfigCodec.fromJson(restored);
        written.rev = ConfigRevRules.nextRev(
                Math.max(Math.max(written.rev, c.rev), priorRev), 0L);
        written.skeleton = false;
        writeFile(written);
        writePrefs(written);
        clearReadOnlyAfterUserReset();
        skeletonFlag = false;
        effects.onApplied();
        effects.requestReload();
        return written;
    }

    /**
     * 损坏后重建默认配置：把当前 config.json 移到 {@code .corrupt.<ts>}，再写入
     * 基于当前 module prefs 的默认配置。
     *
     * <p>rev 从 prefs 记录的上一次 rev 前进，而不是硬编码 1，保持单调性。</p>
     */
    public synchronized AppConfig resetToDefaultsAfterCorruption() throws Exception {
        long previousRev = prefs.readDefaults().rev;
        store.moveCorruptAside();
        AppConfig c = defaults();
        c.rev = ConfigRevRules.nextRev(previousRev, 0L);
        c.skeleton = false;
        String err = ConfigCodec.validate(c);
        if (err != null) throw new IllegalStateException("默认配置无效：" + err);
        writeFile(c);
        writePrefs(c);
        clearReadOnlyAfterUserReset();
        skeletonFlag = false;
        effects.onApplied();
        effects.requestReload();
        return c;
    }

    /**
     * 用户完成恢复/重置后清除只读标志。本方法不碰文件；调用方必须先恢复/重建。
     */
    public void clearReadOnlyAfterUserReset() {
        readOnly = false;
        loadError = "";
        corruptStamp = Long.MIN_VALUE;
        corruptBackedUp = false;
    }

    // ------------------------------------------------------------- 解析 / 默认值

    private AppConfig parseExisting(String raw) {
        try {
            AppConfig c = ConfigCodec.fromJson(raw);
            if (c.rev <= 0) c.rev = 1; // 已存在的文件 = 已初始化
            c.skeleton = false;
            readOnly = false;
            loadError = "";
            corruptStamp = Long.MIN_VALUE;
            corruptBackedUp = false;
            skeletonFlag = false;
            LogSettings.setWire(c.logLevel);
            return c;
        } catch (Throwable t) {
            return corrupt(raw, "配置文件损坏（无法解析）");
        }
    }

    private AppConfig corrupt(String raw, String reason) {
        readOnly = true;
        loadError = reason;
        // 读不到原文（IO 失败）时不生成空的 .corrupt，避免制造无意义证据。
        if (raw != null) backupCorruptOnce(raw);
        return skeleton();
    }

    private void backupCorruptOnce(String raw) {
        try {
            long stamp = store.exists() ? (store.lastModified() * 31L + store.size()) : 0L;
            if (corruptBackedUp && stamp == corruptStamp) return;
            store.backupCorrupt(raw == null ? "" : raw);
            corruptStamp = stamp;
            corruptBackedUp = true;
            log.warn("xiaoi-sync", "config.json 解析失败，已备份为 .corrupt（私密目录）");
        } catch (Throwable t) {
            log.warn("xiaoi-sync", "损坏备份失败（已保留原文件）: " + t.getClass().getSimpleName());
        }
    }

    private AppConfig skeleton() {
        AppConfig d = defaults();
        d.rev = 0;
        d.skeleton = true;
        skeletonFlag = true;
        return d;
    }

    private AppConfig defaults() {
        ConfigDefaults base = prefs.readDefaults();
        return ConfigCodec.defaults(
                base.baseUrl, base.model, base.apiKey,
                base.marker, base.bridge, base.token);
    }

    @Override
    public String validate(AppConfig c) {
        return ConfigCodec.validate(c);
    }

    private List<ConfigBackupInfo> toBackupInfos(List<String> names, boolean restorable) {
        List<ConfigBackupInfo> out = new ArrayList<ConfigBackupInfo>();
        for (String name : names) {
            if (name == null) continue;
            out.add(new ConfigBackupInfo(name, store.lastModified(name), store.size(name), restorable));
        }
        return out;
    }
}
