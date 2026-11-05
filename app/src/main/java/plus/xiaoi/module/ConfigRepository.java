package plus.xiaoi.module;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * v2 配置的唯一读写入口。
 * - config.json 是事实源（原子写：tmp + rename）
 * - 生效快照写进模块 prefs（Config.KEY_*），再由桥推给小爱
 * - 所有写入先 validate()
 */
public final class ConfigRepository {

    private static final String FILE = "config.json";
    private static ConfigRepository sInstance;

    private final Context ctx;

    private ConfigRepository(Context c) { this.ctx = c.getApplicationContext(); }

    public static synchronized ConfigRepository get(Context c) {
        if (sInstance == null) sInstance = new ConfigRepository(c);
        return sInstance;
    }

    public File configFile() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, FILE);
    }

    public AppConfig load() {
        try {
            File f = configFile();
            if (!f.exists()) {
                AppConfig d = defaults();
                write(d);
                return d;
            }
            FileInputStream in = new FileInputStream(f);
            try {
                return parse(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            AppConfig d = defaults();
            return d;
        }
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

    /** 解析（委托 ConfigCodec：含 normalize / 版本保留 / 未知字段原样保留）。 */
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

    public void save(AppConfig c) throws Exception {
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        write(c);
        writePrefs(c);
        ConfigSync.markApplied(ctx);
        pushAsync(c);
    }

    /** 只写文件不推送（导入场景，随后手动/自动应用）。 */
    public void saveQuiet(AppConfig c) throws Exception {
        String err = validate(c);
        if (err != null) throw new IllegalStateException(err);
        write(c);
        writePrefs(c);
    }

    /** 只同步本地镜像（启动对齐，不推送）。 */
    public void applyLocal(AppConfig c) {
        writePrefs(c);
    }

    /** 写 prefs 并推送到小爱（config.json 变更后的热应用）。 */
    public void applyToTarget(AppConfig c) {
        writePrefs(c);
        pushAsync(c);
    }

    /** 同步推送一次（供需要结果对象的调用方/UI 使用）；调用方自己保证不在主线程。 */
    public BridgePusher.Result pushSync(AppConfig c) {
        BridgePusher.Result r = BridgePusher.pushAll(
                c.mcpToken, BridgePusher.payloadFor(c));
        boolean pending = r.pushed == 0;
        ConfigSync.markPending(ctx, pending);
        if (pending) {
            android.util.Log.w("xiaoi-sync", "推送失败（下次启动重试）: " + r.summary());
        } else {
            android.util.Log.i("xiaoi-sync", "推送完成: " + r.summary());
        }
        return r;
    }

    private void write(AppConfig c) throws Exception {
        File f = configFile();
        File dir = f.getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(ConfigCodec.toJson(ConfigCodec.normalize(c)).getBytes("UTF-8"));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (f.exists() && !f.delete()) {
            // 删除失败仍尝试覆盖
        }
        if (!tmp.renameTo(f)) {
            FileOutputStream o2 = new FileOutputStream(f);
            try {
                FileInputStream i2 = new FileInputStream(tmp);
                byte[] b = new byte[8192];
                int n;
                while ((n = i2.read(b)) > 0) o2.write(b, 0, n);
                i2.close();
            } finally {
                o2.close();
            }
            tmp.delete();
        }
    }

    private void writePrefs(AppConfig c) {
        ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                .putString(Config.KEY_BASE_URL, c.baseUrl())
                .putString(Config.KEY_API_KEY, c.apiKey())
                .putString(Config.KEY_MODEL, c.model())
                .putBoolean(Config.KEY_MARKER, c.marker)
                .putString(Config.KEY_MARKER_TPL, c.markerTemplate == null ? "" : c.markerTemplate)
                .putString(Config.KEY_ENABLED, c.enabled ? "1" : "0")
                .putBoolean(Config.KEY_BRIDGE, c.mcpEnabled)
                .putString(Config.KEY_TOKEN, c.mcpToken)
                .apply();
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
