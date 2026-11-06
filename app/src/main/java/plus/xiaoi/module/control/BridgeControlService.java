package plus.xiaoi.module.control;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.ConfigRepository;
import plus.xiaoi.module.Hash;
import plus.xiaoi.module.core.ControlKeys;

/**
 * 控制面服务（只存在于设置 App 进程）。
 *
 * 职责：
 *  - 返回受 UID 保护的 Binder；
 *  - 在 HandlerThread 上读 config.json，避免主线程 IO；
 *  - 保存小爱进程的 callback Messenger / 实例表 / 运行时状态；
 *  - 提供 App → 小爱进程的 reload / showIsland / clearSafeMode / flushLogs（WP1 暂无 UI 调用点）。
 *
 * 本服务是**增量**：不替代旧 BridgePusher / HTTP 推送。控制面异常只在 App 日志留一行，
 * 不影响小爱主流程，也不改变旧推送行为。
 */
public final class BridgeControlService extends Service {

    /** App 进程没有 XposedBridge，不能用模块的 Logs（会静默失败）；这里直接写 logcat。 */
    private static final String TAG = "xiaoi-control";

    private static final long CONFIG_TIMEOUT_MS = 4000L;
    private static final long DENY_LOG_THROTTLE_MS = 5000L;
    private static final int MAX_TEXT_CHARS = 64 * 1024;

    private final BridgeRegistry registry = new BridgeRegistry();
    private final ControlLogRing logRing = new ControlLogRing();
    private final Map<String, Messenger> callbacks = new ConcurrentHashMap<String, Messenger>();

    private HandlerThread bgThread;
    private Handler bgHandler;
    private AndroidUidGuard uidGuard;
    private BridgeControlBinder binder;

    private volatile long lastDenyLogAt;

    @Override
    public void onCreate() {
        super.onCreate();
        uidGuard = new AndroidUidGuard(getPackageManager());
        binder = new BridgeControlBinder(this);
        bgThread = new HandlerThread("xiaoi-control-config");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        log("控制面服务已创建");
    }

    @Override
    public IBinder onBind(Intent intent) {
        log("控制面服务收到 bind");
        return binder;
    }

    /** App 被系统显式 startService（非预期）时不做事，避免被第三方拉起后产生副作用。 */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 导出组件可能被第三方 startService；本服务只支持显式 bind，立即释放。
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try {
            if (bgThread != null) bgThread.quitSafely();
        } catch (Throwable ignored) {
        }
        callbacks.clear();
        registry.clear();
        logRing.clear();
        log("控制面服务已销毁");
        super.onDestroy();
    }

    // ------------------------------------------------------------- Binder 入口

    boolean checkUid(int uid) {
        AndroidUidGuard g = uidGuard;
        return g != null && g.isAllowed(uid);
    }

    void recordDenied(int uid) {
        long now = System.currentTimeMillis();
        if (now - lastDenyLogAt < DENY_LOG_THROTTLE_MS) return;
        lastDenyLogAt = now;
        // 只记 UID，不记包名/进程/配置内容。
        log("控制面拒绝调用：uid=" + uid + " 不在白名单");
    }

    static final class LoadResult {
        String json;
        String hash;
        String error;
    }

    /**
     * 在专用 HandlerThread 上读取配置并等待结果；调用方是 Binder 线程，阻塞可接受。
     * 超时/异常返回错误，不抛到 Binder 层（避免客户端收到含糊的 RemoteException）。
     */
    LoadResult loadConfigBlocking() {
        final LoadResult out = new LoadResult();
        final Handler h = bgHandler;
        if (h == null) {
            out.error = "config worker unavailable";
            return out;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        boolean posted = h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ConfigRepository repo = ConfigRepository.get(getApplicationContext());
                    File f = repo.configFile();
                    // WP1 不能在 beta.4 用户还没打开设置 App 时用默认骨架覆盖运行态配置；
                    // 文件不存在就返回错误，让旧推送/旧 prefs 继续生效，等 App 建好文件再拉。
                    if (f == null || !f.exists()) {
                        out.error = "config not initialized";
                        return;
                    }
                    AppConfig c = repo.load();
                    // 损坏/骨架绝不通过控制面下发：否则会用默认配置覆盖小爱运行时。
                    if (repo.isReadOnly()) {
                        out.error = "config is read-only";
                        return;
                    }
                    if (c == null || c.skeleton) {
                        out.error = "config not initialized";
                        return;
                    }
                    String json = ConfigCodec.toJson(c);
                    out.json = json;
                    out.hash = Hash.sha256Hex(json);
                } catch (Throwable t) {
                    out.error = "config unavailable";
                    log("控制面读取配置失败（已忽略）: " + t.getClass().getSimpleName());
                } finally {
                    latch.countDown();
                }
            }
        });
        if (!posted) {
            out.error = "config worker busy";
            return out;
        }
        try {
            if (!latch.await(CONFIG_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                out.error = "config read timeout";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.error = "config read interrupted";
        }
        return out;
    }

    void register(Bundle in) {
        if (in == null) return;
        String process = clean(in.getString(ControlKeys.KEY_PROCESS, ""), 80);
        int pid = in.getInt(ControlKeys.KEY_PID, 0);
        int port = in.getInt(ControlKeys.KEY_PORT, 0);
        String legacyJson = in.getString(ControlKeys.KEY_LEGACY_JSON, "");
        InstanceRecord r = InstanceRecord.registered(process, pid, port, legacyJson,
                System.currentTimeMillis());
        registry.register(r);

        IBinder cb = in.getBinder(ControlKeys.KEY_CALLBACK);
        String key = r.key();
        if (cb != null) {
            callbacks.put(key, new Messenger(cb));
        } else {
            callbacks.remove(key);
        }
        // 只记 pid/process/port/是否有 callback；legacyJson 含密钥，绝不进日志。
        log("控制面注册 pid=" + pid + " process=" + process
                + " port=" + port + " callback=" + (cb != null));
    }

    void reportState(Bundle in) {
        if (in == null) return;
        String process = clean(in.getString(ControlKeys.KEY_PROCESS, ""), 80);
        int pid = in.getInt(ControlKeys.KEY_PID, 0);
        int port = in.getInt(ControlKeys.KEY_PORT, 0);
        String model = clean(in.getString(ControlKeys.KEY_MODEL, ""), 120);
        boolean enabled = in.getBoolean(ControlKeys.KEY_ENABLED, false);
        boolean hasKey = in.getBoolean(ControlKeys.KEY_HAS_KEY, false);
        String version = clean(in.getString(ControlKeys.KEY_VERSION, ""), 40);
        InstanceRecord r = registry.reportState(process, pid, model, enabled, hasKey,
                version, port, System.currentTimeMillis());
        log("控制面状态 process=" + process + " model=" + model
                + " enabled=" + enabled + " hasKey=" + hasKey + " port=" + r.port);
    }

    void appendLogs(String text) {
        if (text == null || text.length() == 0) return;
        // 有界：单次最多 64K 字符，环形缓冲再按行限制；不落盘、不回显内容。
        logRing.append(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
    }

    // ------------------------------------------------------------- App → 小爱 callback

    /** 通知所有已注册进程重新拉配置（内容相同时桥侧会幂等跳过）。 */
    public void requestReload() {
        sendAll(ControlKeys.S2C_RELOAD, null);
    }

    /** 只让数据面 leader 弹一次岛，避免多进程重复弹。 */
    public void showIsland(String text) {
        Bundle data = new Bundle();
        data.putString(ControlKeys.KEY_TEXT, text == null ? "" : text);
        sendToLeader(ControlKeys.S2C_SHOW_ISLAND, data);
    }

    public void clearSafeMode() {
        sendToLeader(ControlKeys.S2C_CLEAR_SAFE_MODE, null);
    }

    public void flushLogs() {
        sendToLeader(ControlKeys.S2C_FLUSH_LOGS, null);
    }

    public List<InstanceRecord> snapshot() {
        return registry.snapshot();
    }

    public String logDump() {
        return logRing.dump();
    }

    private void sendAll(int what, Bundle data) {
        for (Map.Entry<String, Messenger> e : callbacks.entrySet()) {
            sendOne(e.getKey(), e.getValue(), what, data);
        }
    }

    private void sendToLeader(int what, Bundle data) {
        InstanceRecord leader = registry.leader();
        if (leader != null) {
            Messenger m = callbacks.get(leader.key());
            if (m != null) {
                sendOne(leader.key(), m, what, data);
                return;
            }
        }
        // 还没有 leader 时退回广播：至少让在线的进程收到（例如 reload）。
        sendAll(what, data);
    }

    private void sendOne(String key, Messenger m, int what, Bundle data) {
        if (m == null) return;
        try {
            Message msg = Message.obtain(null, what);
            if (data != null) msg.setData(data);
            m.send(msg);
        } catch (RemoteException e) {
            callbacks.remove(key, m);
            log("控制面回调失效（已移除）: " + e.getClass().getSimpleName());
        } catch (Throwable t) {
            callbacks.remove(key, m);
        }
    }

    // ------------------------------------------------------------- 工具

    private static void log(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
