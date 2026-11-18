package plus.xiaoi.module;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.List;

import plus.xiaoi.module.application.config.ConfigDefaults;
import plus.xiaoi.module.application.config.ConfigEffectsPort;
import plus.xiaoi.module.application.config.ConfigLogPort;
import plus.xiaoi.module.application.config.ConfigPrefsPort;
import plus.xiaoi.module.application.config.ConfigService;
import plus.xiaoi.module.core.BridgeEndpoint;
import plus.xiaoi.module.core.ConfigStore;
import plus.xiaoi.module.core.FileConfigStore;
import plus.xiaoi.module.core.config.ConfigBackupInfo;
import plus.xiaoi.module.core.config.ConfigGateway;
import plus.xiaoi.module.core.config.ConfigLoadResult;
import plus.xiaoi.module.core.config.ConfigSaveResult;
import plus.xiaoi.module.core.config.ConfigStorageInfo;

/**
 * 配置的 Android 组装点（WP1b）。
 *
 * <p>规则（加载/迁移/损坏只读/rev CAS/恢复/重置）已上移到纯 JVM
 * {@link ConfigService}；本类只负责：</p>
 * <ul>
 *   <li>进程单例与 App 私有目录 {@code getFilesDir()/config.json}</li>
 *   <li>SharedPreferences 镜像适配器</li>
 *   <li>保存后的 {@link ConfigSync#markApplied} / {@link BridgeEndpoint#requestReload}</li>
 *   <li>旧 beta.4 外部文件位置（一次性迁移）</li>
 * </ul>
 *
 * <p>过渡期调用方（ConfigSync / BridgeControlService / AgentService）
 * 的 public 方法签名保持不变；旧 Java 页面已于 0.15.1 删除。</p>
 */
public final class ConfigRepository implements ConfigGateway {

    private static ConfigRepository sInstance;

    private final Context ctx;
    private final File legacyExternalFile;
    private final ConfigStore store;
    private final ConfigService service;

    private ConfigRepository(Context c) {
        this.ctx = c.getApplicationContext();
        this.legacyExternalFile = resolveLegacyExternalFile(ctx);
        this.store = new ConfigStore(ctx.getFilesDir());
        this.service = new ConfigService(
                new FileConfigStore(store, legacyExternalFile),
                new Prefs(ctx),
                new Effects(ctx),
                new Log());
    }

    public static synchronized ConfigRepository get(Context c) {
        if (sInstance == null) sInstance = new ConfigRepository(c);
        return sInstance;
    }

    private static File resolveLegacyExternalFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, ConfigStore.FILE_NAME);
    }

    /** 事实源：App 私有目录下的 config.json。 */
    public File configFile() {
        return store.file();
    }

    /** beta.4 的外部配置位置，仅用于一次性迁移；外部存储不可用时返回 null。 */
    public File legacyExternalFile() {
        return legacyExternalFile;
    }

    // ------------------------------------------------------------- 读取 / 保存（旧签名）

    public AppConfig load() {
        return service.load();
    }

    public AppConfig loadForUi() {
        return service.loadForUi();
    }

    public boolean isReadOnly() {
        return service.isReadOnly();
    }

    public String loadError() {
        return service.loadError();
    }

    public boolean isSkeleton() {
        return service.isSkeleton();
    }

    public void save(AppConfig c) throws Exception {
        service.save(c);
    }

    /** 只写文件 + 请求 reload（导入场景，随后手动/自动应用）。 */
    public void saveQuiet(AppConfig c) throws Exception {
        service.saveQuiet(c);
    }

    public void applyLocal(AppConfig c) {
        service.applyLocal(c);
    }

    public void applyToTarget(AppConfig c) {
        service.applyToTarget(c);
    }

    public List<File> legacyBackups() {
        return store.list(ConfigStore.LEGACY_PREFIX);
    }

    public AppConfig restoreLegacy(File backup) throws Exception {
        if (backup == null) throw new IllegalArgumentException("备份为空");
        return service.restoreLegacyName(backup.getName());
    }

    public AppConfig resetToDefaultsAfterCorruption() throws Exception {
        return service.resetAfterCorruption();
    }

    public void clearReadOnlyAfterUserReset() {
        service.clearReadOnlyAfterUserReset();
    }

    public String validate(AppConfig c) {
        return service.validate(c);
    }

    // ------------------------------------------------------------- ConfigGateway

    @Override
    public ConfigLoadResult loadState() {
        return service.loadState();
    }

    @Override
    public ConfigSaveResult saveIfRev(AppConfig next, long expectedRev) {
        return service.saveIfRev(next, expectedRev);
    }

    @Override
    public ConfigSaveResult saveIfRevQuiet(AppConfig next, long expectedRev) {
        return service.saveIfRevQuiet(next, expectedRev);
    }

    @Override
    public List<ConfigBackupInfo> backupInfos() {
        return service.backupInfos();
    }

    @Override
    public List<ConfigBackupInfo> corruptInfos() {
        return service.corruptInfos();
    }

    @Override
    public ConfigStorageInfo storage() {
        return service.storage();
    }

    @Override
    public AppConfig restore(ConfigBackupInfo backup) throws Exception {
        return service.restore(backup);
    }

    @Override
    public AppConfig resetAfterCorruption() throws Exception {
        return service.resetAfterCorruption();
    }

    // ------------------------------------------------------------- Android 适配器

    /** module prefs 镜像：默认值 + 保存后同步落盘。 */
    private static final class Prefs implements ConfigPrefsPort {
        private final Context ctx;

        private Prefs(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public ConfigDefaults readDefaults() {
            SharedPreferences p = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
            return new ConfigDefaults(
                    p.getString(Config.KEY_BASE_URL, ""),
                    p.getString(Config.KEY_MODEL, ""),
                    p.getString(Config.KEY_API_KEY, ""),
                    p.getBoolean(Config.KEY_MARKER, true),
                    p.getBoolean(Config.KEY_BRIDGE, false),
                    p.getString(Config.KEY_TOKEN, ""),
                    p.getLong(Config.KEY_CFG_REV, 0L));
        }

        @Override
        public boolean write(AppConfig c) {
            // commit 而不是 apply：写完紧接着通知控制面 reload，prefs 必须已落盘。
            return ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(Config.KEY_BASE_URL, c.baseUrl())
                    .putString(Config.KEY_API_KEY, c.apiKey())
                    .putString(Config.KEY_MODEL, c.model())
                    .putBoolean(Config.KEY_MARKER, c.marker)
                    .putString(Config.KEY_MARKER_TPL, c.markerTemplate == null ? "" : c.markerTemplate)
                    .putString(Config.KEY_ENABLED, c.enabled ? "1" : "0")
                    .putBoolean(Config.KEY_BRIDGE, c.mcpEnabled)
                    .putBoolean(Config.KEY_MCP_LAN, c.mcpLan)
                    .putBoolean(Config.KEY_MCP_NO_AUTH, c.mcpNoAuth)
                    .putBoolean(Config.KEY_MCP_WEAK_TOKEN, c.mcpWeakToken)
                    .putString(Config.KEY_TOKEN, c.mcpToken)
                    .putLong(Config.KEY_CFG_REV, c.rev)
                    .commit();
        }
    }

    /** 保存后的外部效应：watcher 去环 + 控制面 reload。 */
    private static final class Effects implements ConfigEffectsPort {
        private final Context ctx;

        private Effects(Context ctx) {
            this.ctx = ctx;
        }

        @Override
        public void onApplied() {
            ConfigSync.markApplied(ctx);
        }

        @Override
        public boolean requestReload() {
            return BridgeEndpoint.requestReload();
        }
    }

    private static final class Log implements ConfigLogPort {
        @Override
        public void warn(String tag, String message) {
            android.util.Log.w(tag, message);
        }
    }
}