package plus.xiaoi.module;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;

import plus.xiaoi.module.core.ConfigStore;

/**
 * 配置的唯一读写入口（WP2）。
 *
 * - 事实源：App **私有**目录 {@code getFilesDir()/config.json}（不再写 Android/data）
 * - 原子写：委托 {@link ConfigStore}（tmp + fsync + rename，不先删目标）
 * - 解析失败：保留原文件 + 写 {@code config.json.corrupt.<ts>}，进入只读态，绝不回默认覆盖
 * - 旧外部文件（beta.4 的 Android/data/.../config.json）：首次加载时一次性迁移
 * - 生效快照写进模块 prefs（Config.KEY_* + cfg_rev），target prefs 只做降级缓存
 *
 * 本包仍保留旧 HTTP 推送（{@link BridgePusher}）；控制面切换在 WP3。
 */
public final class ConfigRepository {

    private static final String FILE = ConfigStore.FILE_NAME;
    private static ConfigRepository sInstance;
    /**
     * 写入前模块 prefs 里的旧 token。token 轮换时先用它认领桥，再用新 token 推送；
     * 推成功后桥的内存 token 已切换。只保留最近一次，推完即清。
     */
    private volatile String sPreviousToken = "";
    /** 最近一次推送成功的 token；进程重启后丢失，但正常轮换路径已足够。 */
    private volatile String sLastPushedToken = "";

    private final Context ctx;
    private final ConfigStore store;

    /** 解析失败后为 true：save/saveQuiet 必须拒绝写入，直到 UI 走恢复/重置流程。 */
    private volatile boolean readOnly;
    private volatile String loadError = "";
    private volatile boolean skeletonFlag;
    /** 上一次已备份的损坏文件指纹，避免每次 load 都生成一份含密钥的 .corrupt。 */
    private volatile long corruptStamp = Long.MIN_VALUE;
    private volatile boolean corruptBackedUp;

    private ConfigRepository(Context c) {
        this.ctx = c.getApplicationContext();
        this.store = new ConfigStore(ctx.getFilesDir());
    }

    public static synchronized ConfigRepository get(Context c) {
        if (sInstance == null) sInstance = new ConfigRepository(c);
        return sInstance;
    }

    /** 事实源：App 私有目录下的 config.json。 */
    public File configFile() {
        return store.file();
    }

    /**
     * beta.4 的外部配置位置，仅用于一次性迁移。
     * 外部存储不可用时返回 null。
     */
    public File legacyExternalFile() {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, FILE);
    }

    // ------------------------------------------------------------- 加载 / 迁移 / 损坏

    /**
     * 读取事实源。
     *
     * - 私有文件存在：解析；失败 → 备份 .corrupt、readOnly=true、返回内存骨架（不写文件）
     * - 私有不存在、外部 legacy 存在：迁移（成功后删除外部）→ 解析 → rev=1 写回私有
     * - 都不存在：返回默认骨架（skeleton=true，rev=0），**不写文件**
     */
    public synchronized AppConfig load() {
        File f = configFile();
        boolean existed = f.isFile();
        File legacy = legacyExternalFile();
        if (legacy != null && legacy.isFile()) {
            // 外部旧文件只要还在就处理：私有已存在时归档为 .legacy，绝不覆盖私有。
            try {
                store.migrateFrom(legacy);
            } catch (Throwable t) {
                android.util.Log.w("xiaoi-sync", "旧配置迁移失败（已忽略）: "
                        + t.getClass().getSimpleName());
            }
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

        // 私有目录原本没有文件：如果刚完成迁移，解析后统一到当前 schema 并写回（rev=1）。
        if (configFile().isFile()) {
            try {
                String raw = store.read();
                if (raw != null) {
                    AppConfig c = parseExisting(raw);
                    if (c.skeleton) return c;
                    // v2 无 rev 归一为 1；已有更高 rev 则保留。
                    c.rev = Math.max(c.rev, 1L);
                    try {
                        writeFile(c);
                    } catch (Throwable t) {
                        // 写回失败也先把解析结果返回给 UI；下次加载会再次尝试升级。
                        android.util.Log.w("xiaoi-sync", "迁移后写回失败（已忽略）: "
                                + t.getClass().getSimpleName());
                    }
                    return c;
                }
            } catch (Throwable t) {
                android.util.Log.w("xiaoi-sync", "迁移后解析失败: "
                        + t.getClass().getSimpleName());
            }
        }
        return skeleton();
    }

    /** 供 UI 读取（与 load 等价；调用方通过 isReadOnly()/loadError() 决定提示）。 */
    public AppConfig loadForUi() {
        return load();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** 损坏时的用户可读原因；空串 = 正常。不包含配置内容/密钥。 */
    public String loadError() {
        return loadError == null ? "" : loadError;
    }

    /** 当前内存配置是否是默认骨架（文件不存在 / 损坏后）。 */
    public boolean isSkeleton() {
        return skeletonFlag;
    }

    /**
     * 用户完成恢复/重置后清除只读标志。
     * 本方法**不碰文件**：调用方必须先恢复 .bak、重命名损坏文件或写入新配置，
     * 否则下一次 load() 仍会重新识别为损坏并回到只读态。
     */
    public void clearReadOnlyAfterUserReset() {
        readOnly = false;
        loadError = "";
        corruptStamp = Long.MIN_VALUE;
        corruptBackedUp = false;
    }

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
            File f = configFile();
            long stamp = f.isFile() ? (f.lastModified() * 31L + f.length()) : 0L;
            if (corruptBackedUp && stamp == corruptStamp) return;
            store.backupCorrupt(raw == null ? "" : raw);
            corruptStamp = stamp;
            corruptBackedUp = true;
            android.util.Log.w("xiaoi-sync", "config.json 解析失败，已备份为 .corrupt（私密目录）");
        } catch (Throwable t) {
            android.util.Log.w("xiaoi-sync", "损坏备份失败（已保留原文件）: "
                    + t.getClass().getSimpleName());
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
        android.content.SharedPreferences p = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        return ConfigCodec.defaults(
                p.getString(Config.KEY_BASE_URL, ""),
                p.getString(Config.KEY_MODEL, ""),
                p.getString(Config.KEY_API_KEY, ""),
                p.getBoolean(Config.KEY_MARKER, true),
                p.getBoolean(Config.KEY_BRIDGE, false),
                p.getString(Config.KEY_TOKEN, ""));
    }

    /** 解析（委托 ConfigCodec：含 normalize / 版本升级 / 未知字段原样保留）。 */
    public static AppConfig parse(InputStream in) throws Exception {
        return ConfigCodec.fromStream(in);
    }

    /** 序列化（委托 ConfigCodec；保留 JSONObject 返回以便导出时缩进）。 */
    public static JSONObject toJson(AppConfig c) throws Exception {
        return new JSONObject(ConfigCodec.toJson(c));
    }

    /** 返回错误文案；null 表示通过（委托 ConfigCodec）。 */
    public String validate(AppConfig c) {
        return ConfigCodec.validate(c);
    }

    // ------------------------------------------------------------- 保存

    public void save(AppConfig c) throws Exception {
        guardWritable();
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        capturePreviousToken();
        writeWithRev(c);
        writePrefs(c);
        ConfigSync.markApplied(ctx);
        pushAsync(c);
    }

    /** 只写文件不推送（导入场景，随后手动/自动应用）。 */
    public void saveQuiet(AppConfig c) throws Exception {
        guardWritable();
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        capturePreviousToken();
        writeWithRev(c);
        writePrefs(c);
    }

    /** 只同步本地镜像（启动对齐，不推送，也不改 rev）。 */
    public void applyLocal(AppConfig c) {
        if (readOnly) return;
        writePrefs(c);
    }

    /** 写 prefs 并推送到小爱（config.json 变更后的热应用；不改 rev）。 */
    public void applyToTarget(AppConfig c) {
        if (readOnly) return;
        capturePreviousToken();
        writePrefs(c);
        pushAsync(c);
    }

    private void guardWritable() {
        if (readOnly) {
            throw new IllegalStateException("config is read-only after corruption");
        }
    }

    /** 每次 App 保存：rev = max(内存 rev, 文件 rev, 1) + 1。 */
    private void writeWithRev(AppConfig c) throws Exception {
        long fileRev = readFileRev();
        long base = Math.max(Math.max(c.rev, fileRev), 1L);
        c.rev = base + 1;
        writeFile(c);
    }

    private long readFileRev() throws Exception {
        String raw = store.read();
        if (raw == null) return 0L;
        try {
            return ConfigCodec.fromJson(raw).rev;
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
        // 读回后 rev 已更新；调用方持有同一对象即可看到新值。
        readOnly = false;
    }

    /** 记录写入前模块 prefs 的 token（用于 token 轮换时兼容旧桥）。 */
    private void capturePreviousToken() {
        try {
            sPreviousToken = Config.forApp(ctx).token();
        } catch (Throwable ignored) {
        }
    }

    /** 同步推送一次（供需要结果对象的调用方/UI 使用）；调用方自己保证不在主线程。 */
    public BridgePusher.Result pushSync(AppConfig c) {
        String previous = sPreviousToken;
        String lastPushed = sLastPushedToken;
        sPreviousToken = "";
        BridgePusher.Result r = BridgePusher.pushAll(
                new String[]{c.mcpToken, previous, lastPushed}, BridgePusher.payloadFor(c));
        if (r.pushed > 0) sLastPushedToken = c.mcpToken == null ? "" : c.mcpToken;
        boolean pending = r.pushed == 0;
        ConfigSync.markPending(ctx, pending);
        if (pending) {
            android.util.Log.w("xiaoi-sync", "推送失败（下次启动重试）: " + r.summary());
        } else {
            android.util.Log.i("xiaoi-sync", "推送完成: " + r.summary());
        }
        return r;
    }

    private void writePrefs(AppConfig c) {
        try {
            // 用 commit 而不是 apply：token 轮换时紧接着要推送，必须保证 prefs 已落盘，
            // 否则 /health 仍看到旧 token，推新 token 会被 401（v0.8 的轮换死锁）。
            boolean ok = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(Config.KEY_BASE_URL, c.baseUrl())
                    .putString(Config.KEY_API_KEY, c.apiKey())
                    .putString(Config.KEY_MODEL, c.model())
                    .putBoolean(Config.KEY_MARKER, c.marker)
                    .putString(Config.KEY_MARKER_TPL, c.markerTemplate == null ? "" : c.markerTemplate)
                    .putString(Config.KEY_ENABLED, c.enabled ? "1" : "0")
                    .putBoolean(Config.KEY_BRIDGE, c.mcpEnabled)
                    .putBoolean(Config.KEY_MCP_LAN, c.mcpLan)
                    .putString(Config.KEY_TOKEN, c.mcpToken)
                    .putLong(Config.KEY_CFG_REV, c.rev)
                    .commit();
            if (!ok) android.util.Log.w("xiaoi-sync", "prefs 写入返回 false");
        } catch (Throwable t) {
            android.util.Log.w("xiaoi-sync", "prefs 写入失败: " + t);
        }
    }

    /**
     * 异步推给小爱进程（设置页保存用）：认端口 + 全端口聚合，失败不阻塞 UI。
     * 全失败时写 pending 标记，下次 {@link ConfigSync#start} 强制重试一次。
     */
    private void pushAsync(final AppConfig c) {
        AppExecutors.io(new Runnable() {
            public void run() {
                try {
                    pushSync(c);
                } catch (Throwable t) {
                    android.util.Log.w("xiaoi-sync", "推送异常（已忽略）: " + t);
                }
            }
        });
    }
}
