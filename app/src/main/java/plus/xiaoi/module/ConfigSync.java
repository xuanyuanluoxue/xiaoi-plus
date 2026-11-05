package plus.xiaoi.module;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;

/**
 * config.json 即事实（v0.8.2）：监听文件改动并自动应用到小爱。
 *
 * 数据流：
 *   config.json（Android/data/plus.xiaoi.module/files/，免 root 可编辑）
 *     → FileObserver 监听（本进程活着时）
 *     → validate → 写模块 prefs 镜像 → POST /config（合并语义）
 *     → 小爱进程 hooks 800ms 热读生效
 *
 * 只在 App 进程内监听：进程被杀后改文件，下次启动时由 {@link #applyIfChanged}
 * 比对内容哈希后补应用。运行时变更（MCP switch_model 等）不自动写回文件——
 * 由 UI 提供「从运行中小爱导入」，文件永远优先、永不被静默覆盖。
 */
public final class ConfigSync {

    private static final String TAG = "xiaoi-sync";
    private static final long DEBOUNCE_MS = 300;

    private static FileObserver sObserver;
    private static Handler sHandler;
    /** UI 订阅：外部改文件并应用成功后回调（主线程）。 */
    private static final java.util.List<Runnable> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<Runnable>();
    private static String sLastAppliedHash;
    private static volatile boolean sStarted;

    /** 推送失败待重试标记（模块 prefs；下次 start() 强制重推一次）。 */
    static final String KEY_SYNC_PENDING = "sync_pending";

    private ConfigSync() {}

    /** 订阅文件变更（主线程回调）；用于让页面在外部编辑后自动刷新。 */
    public static void addListener(Runnable r) {
        if (r != null) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyChanged() {
        final Handler h = sHandler;
        if (h == null) return;
        h.post(new Runnable() {
            public void run() {
                for (Runnable r : LISTENERS) {
                    try { r.run(); } catch (Throwable ignored) {}
                }
            }
        });
    }

    /** 开始监听（幂等）。App 进程任意入口调用即可。 */
    public static synchronized void start(Context ctx) {
        if (sStarted) return;
        sStarted = true;
        sHandler = new Handler(Looper.getMainLooper());
        final Context app = ctx.getApplicationContext();
        File dir = ConfigRepository.get(app).configFile().getParentFile();
        if (dir == null) return;
        try {
            sObserver = new FileObserver(dir.getAbsolutePath()) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null) return;
                    if (!"config.json".equals(path)) return;
                    // 原子写是 tmp → rename，MOVED_TO / CLOSE_WRITE 都收；合并去抖
                    sHandler.removeCallbacksAndMessages(null);
                    sHandler.postDelayed(new Runnable() {
                        public void run() {
                            applyIfChanged(app, true);
                        }
                    }, DEBOUNCE_MS);
                }
            };
            sObserver.startWatching();
            Log.i(TAG, "config.json 监听已启动");
        } catch (Throwable t) {
            Log.w(TAG, "监听启动失败: " + t);
        }
        // 启动引导：首次/升级时从运行中小爱导入真实配置生成 config.json，
        // 绝不用默认骨架覆盖运行时（这是旧版「保存冲掉配置」的同类地雷）。
        AppExecutors.io(new Runnable() {
            public void run() {
                final boolean imported = bootstrapIfNeeded(app);
                final boolean retry = !imported && isPending(app);
                sHandler.postDelayed(new Runnable() {
                    public void run() {
                        if (imported) return;
                        if (retry) {
                            // 上次推送全失败（小爱当时没运行）：文件可能已应用过，强制重推一次
                            applyIfChanged(app, true, true);
                        } else {
                            applyIfChanged(app, true);
                        }
                    }
                }, 150);
            }
        });
    }

    /**
     * 内容哈希与上次应用不同才应用，避免 save() 自己写文件触发回环。
     *
     * @param push 是否推送到小爱（true=改动后应用；false=启动时仅对齐本地镜像）
     */
    private static void applyIfChanged(Context ctx, boolean push) {
        applyIfChanged(ctx, push, false);
    }

    /**
     * @param force true = 忽略哈希去重（pending 重试用；上次推送失败但文件已应用过）
     */
    private static void applyIfChanged(Context ctx, boolean push, boolean force) {
        try {
            ConfigRepository repo = ConfigRepository.get(ctx);
            File f = repo.configFile();
            if (!f.exists()) return;
            String hash = hashOf(f);
            if (hash == null || hash.length() == 0) return;
            if (!force && hash.equals(sLastAppliedHash)) return;

            AppConfig c = repo.load();
            String err = repo.validate(c);
            if (err != null) {
                Log.i(TAG, "config.json 校验未通过，未应用: " + err);
                return;
            }
            sLastAppliedHash = hash;
            if (push) {
                // 无密钥不再整份跳过（R3）：推送载荷由 BridgePusher.payloadFor 按字段分组，
                // 模型组受保护、mcp / 前缀照常生效。
                repo.applyToTarget(c);
                Log.i(TAG, "config.json 变更已应用: model=" + c.model() + " enabled=" + c.enabled
                        + " hasKey=" + (c.apiKeyOfActive().length() > 0)
                        + (force ? " (pending 重试)" : ""));
                notifyChanged();
            } else {
                repo.applyLocal(c);
                Log.i(TAG, "启动对齐完成: model=" + c.model() + " enabled=" + c.enabled);
            }
        } catch (Throwable t) {
            Log.w(TAG, "应用失败: " + t);
        }
    }

    /**
     * 当前 config.json 的内容哈希是否与「上次已应用」的不同。
     *
     * 用于 FileObserver 在部分 ROM 的 /sdcard（FUSE）上静默失效时的兜底：
     * {@code ConfigActivity/HomeActivity.onResume} 各比对一次即可补上漏掉的变更（§5.2 #6）。
     * 基线未知（本进程还没应用过任何文件）时返回 false：启动路径自己会做首次对齐。
     */
    public static boolean fileChanged(Context ctx) {
        if (ctx == null) return false;
        try {
            File f = ConfigRepository.get(ctx).configFile();
            if (!f.exists()) return false;
            String hash = hashOf(f);
            if (hash == null || hash.length() == 0) return false;
            String last = sLastAppliedHash;
            if (last == null) return false;
            return !hash.equals(last);
        } catch (Throwable t) {
            return false;
        }
    }

    /** onResume 兜底：发现文件变了就立刻热应用一次（内部仍按哈希去重，重复调用无副作用）。 */
    public static void syncNow(Context ctx) {
        if (ctx == null) return;
        applyIfChanged(ctx.getApplicationContext(), true);
    }

    // ------------------------------------------------------------- pending 标记

    /** 上一次推送是否全失败（待下次启动重试）。 */
    static boolean isPending(Context ctx) {
        try {
            return ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_SYNC_PENDING, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记录/清除 pending（推送结束由 ConfigRepository 调用）。 */
    static void markPending(Context ctx, boolean pending) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_SYNC_PENDING, pending).apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 首次/升级引导：config.json 不存在，或仍是「默认骨架」时，从运行中小爱导入。
     * 判定骨架 = 无密钥且未启用（真实配置必然有密钥；暂停也有密钥）。
     * @return true = 发生了导入（调用方不再按文件推送）
     */
    private static boolean bootstrapIfNeeded(Context ctx) {
        try {
            ConfigRepository repo = ConfigRepository.get(ctx);
            File f = repo.configFile();
            AppConfig c = f.exists() ? repo.load() : null;
            boolean skeleton = c == null || c.apiKeyOfActive().length() == 0;
            if (!skeleton) return false;
            boolean ok = importFromRuntimeQuiet(ctx);
            if (ok) markApplied(ctx);
            Log.i(TAG, ok ? "已从运行时引导导入 config.json" : "引导导入失败（桥不可达），保留现有文件");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "引导检查失败: " + t);
            return false;
        }
    }

    /** 内部写入成功后回填哈希，防止自触发。 */
    static void markApplied(Context ctx) {
        if (ctx == null) return;
        try {
            File f = ConfigRepository.get(ctx).configFile();
            sLastAppliedHash = f.exists() ? hashOf(f) : null;
        } catch (Throwable ignored) {
        }
    }

    private static String hashOf(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally {
                in.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- 运行时导入

    /**
     * 从运行中的小爱读取真实生效配置，写入 config.json（用户手动触发）。
     * 桥不返回 apiKey（安全考虑），导入后密钥需在 UI 补填；
     * 若取不到 key，写入的配置保持 enabled=false，避免用空 key 上线。
     *
     * @return 是否成功导入（false = 桥不可达/未配置 token）
     */
    public static boolean importFromRuntime(Context ctx) {
        return importFromRuntimeQuiet(ctx);
    }

    /**
     * 只读读取小爱运行时配置（MCP xiaoai_config），不下发、不写文件。
     * UI 用它判断「运行时模型/开关是否与文件一致」。
     *
     * @return 运行时快照；null = MCP 未开启 / 令牌为空 / 桥不可达 / 运行时未配置
     */
    public static JSONObject readRuntimeConfig(Context ctx) {
        if (ctx == null) return null;
        try {
            return fetchRuntimeConfig(Config.forApp(ctx).token());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 经 BridgeClient 取运行时配置。
     * 要求 model 与 baseUrl 均非空：骨架态/未配置的进程不算「在运行」。
     */
    private static JSONObject fetchRuntimeConfig(String token) {
        if (token == null || token.length() == 0) return null;
        int port = BridgeClient.discover(token);
        if (port <= 0) return null;
        String text = BridgeClient.runtimeConfig(port, token);
        if (text == null) return null;
        try {
            JSONObject remote = new JSONObject(text);
            if (remote.optString("model", "").length() == 0
                    || remote.optString("baseUrl", "").length() == 0) return null;
            return remote;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean importFromRuntimeQuiet(Context ctx) {
        try {
            String token = Config.forApp(ctx).token();
            JSONObject remote = fetchRuntimeConfig(token);
            if (remote == null) return false;

            String model = remote.optString("model", "");
            String baseUrl = remote.optString("baseUrl", "");

            ConfigRepository repo = ConfigRepository.get(ctx);
            AppConfig next = repo.load();
            // 合并进当前文件结构：模型/端点用运行时值，密钥沿用文件（桥不回传 key）
            AppConfig.Provider p = next.activeProvider();
            if (p == null) {
                p = new AppConfig.Provider("imported", "Imported", baseUrl, "");
                next.providers.add(p);
                next.activeProviderId = p.id;
            }
            p.baseUrl = baseUrl;
            boolean hasModel = false;
            for (AppConfig.ModelItem m : p.models) {
                if (m.id.equals(model)) { hasModel = true; break; }
            }
            if (!hasModel) p.models.add(new AppConfig.ModelItem(model, model));
            next.activeModelId = model;
            next.mcpToken = token;
            // 桥不返回 apiKey：沿用文件中已有的；没有就保持暂停待补
            if (p.apiKey == null || p.apiKey.trim().length() == 0) {
                next.enabled = false;
            }
            repo.saveQuiet(next);
            markApplied(ctx);
            Log.i(TAG, "已从运行时导入: model=" + model + " baseUrl=" + baseUrl
                    + " enabled=" + next.enabled);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
