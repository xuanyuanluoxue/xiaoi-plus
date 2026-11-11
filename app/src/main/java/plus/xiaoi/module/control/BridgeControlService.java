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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.ConfigRepository;
import plus.xiaoi.module.Hash;
import plus.xiaoi.module.core.ActivityFeed;
import plus.xiaoi.module.core.ActivityHub;
import plus.xiaoi.module.core.BridgeEndpoint;
import plus.xiaoi.module.core.LogLevel;
import plus.xiaoi.module.core.LogSettings;
import plus.xiaoi.module.core.BridgeStatus;
import plus.xiaoi.module.core.ControlKeys;

/**
 * 控制面服务（只存在于设置 App 进程）。
 *
 * 职责：
 *  - 返回受 UID 保护的 Binder；
 *  - 在 HandlerThread 上读 config.json，避免主线程 IO；
 *  - 保存小爱进程的 callback Messenger / 实例表 / 运行时状态；
 *  - 把 leader 上报的端口登记到 {@link BridgeEndpoint}（App 不再扫端口）；
 *  - 提供 App → 小爱进程的 reload / showIsland / clearSafeMode / flushLogs。
 *
 * WP4 起它是配置变更的唯一通知通道：App 保存后调用
 * {@link BridgeEndpoint#requestReload()}，由本服务广播 reload；旧 HTTP 推送已删除。
 */
public final class BridgeControlService extends Service {

    /** App 进程没有 XposedBridge，不能用模块的 Logs（会静默失败）；这里直接写 logcat。 */
    private static final String TAG = "xiaoi-control";

    private static final long CONFIG_TIMEOUT_MS = 4000L;
    private static final long DENY_LOG_THROTTLE_MS = 5000L;
    private static final int MAX_TEXT_CHARS = 64 * 1024;

    private final BridgeRegistry registry = new BridgeRegistry();
    /** 静态是为了让未创建 Service 的页面也能安全读取；onDestroy 清空。 */
    private static final ControlLogRing LOG_RING = new ControlLogRing();
    private final Map<String, Messenger> callbacks = new ConcurrentHashMap<String, Messenger>();

    private HandlerThread bgThread;
    private Handler bgHandler;
    private AndroidUidGuard uidGuard;
    private BridgeControlBinder binder;

    private volatile long lastDenyLogAt;

    /** leader 绑定元数据；只由 register/reportState 写入，未知时为空/null。 */
    private final Object leaderMetaLock = new Object();
    private boolean leaderSeen;
    private int lastLeaderPort;
    private long leaderSince;
    private long lastDisconnectedAt;
    private String leaderBindHost = "";
    private Boolean leaderLanEnabled = null;

    @Override
    public void onCreate() {
        super.onCreate();
        uidGuard = new AndroidUidGuard(getPackageManager());
        binder = new BridgeControlBinder(this);
        bgThread = new HandlerThread("xiaoi-control-config");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        // 配置保存后由 ConfigRepository/ConfigSync 调 BridgeEndpoint.requestReload()。
        BridgeEndpoint.setReloadAction(new Runnable() {
            @Override
            public void run() {
                requestReload();
            }
        });
        log("控制面服务已创建");
        ActivityHub.info(ActivityFeed.Domain.XIAOAI, "控制面服务已创建");
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
        LOG_RING.clear();
        // 先清端口/快照再清回调：服务销毁后 App 不应再访问旧 leader 端口或显示旧运行态。
        long disconnectedAt;
        synchronized (leaderMetaLock) {
            if (leaderSeen) {
                lastDisconnectedAt = System.currentTimeMillis();
                leaderSeen = false;
            }
            lastLeaderPort = 0;
            disconnectedAt = lastDisconnectedAt;
        }
        BridgeEndpoint.setPort(0);
        BridgeStatus.clear(disconnectedAt);
        BridgeEndpoint.clearReloadAction();
        log("控制面服务已销毁");
        ActivityHub.info(ActivityFeed.Domain.XIAOAI, "控制面服务已销毁");
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
        ActivityHub.warn(ActivityFeed.Domain.ACCESS, "控制面拒绝调用 uid=" + uid);
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
                    ActivityHub.warn(ActivityFeed.Domain.ACCESS,
                            "控制面读取配置失败：" + t.getClass().getSimpleName());
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
        long cfgRev = in.getLong(ControlKeys.KEY_CFG_REV, 0L);
        String bindHost = clean(in.getString(ControlKeys.KEY_BIND_HOST, ""), 40);
        Boolean lanEnabled = in.containsKey(ControlKeys.KEY_LAN_ENABLED)
                ? Boolean.valueOf(in.getBoolean(ControlKeys.KEY_LAN_ENABLED, false)) : null;
        InstanceRecord r = InstanceRecord.registered(process, pid, port, legacyJson,
                System.currentTimeMillis(), cfgRev);
        registry.register(r);
        syncEndpoint(port > 0 ? bindHost : "", port > 0 ? lanEnabled : null);

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
        long cfgRev = in.getLong(ControlKeys.KEY_CFG_REV, 0L);
        String bindHost = clean(in.getString(ControlKeys.KEY_BIND_HOST, ""), 40);
        Boolean lanEnabled = in.containsKey(ControlKeys.KEY_LAN_ENABLED)
                ? Boolean.valueOf(in.getBoolean(ControlKeys.KEY_LAN_ENABLED, false)) : null;
        InstanceRecord r = registry.reportState(process, pid, model, enabled, hasKey,
                version, port, System.currentTimeMillis(), cfgRev);
        syncEndpoint(port > 0 ? bindHost : "", port > 0 ? lanEnabled : null);
        log("控制面状态 process=" + process + " model=" + model
                + " enabled=" + enabled + " hasKey=" + hasKey + " port=" + r.port
                + " rev=" + r.cfgRev);
    }

    /**
     * 把当前 leader 端口/运行态同步给 App 进程内的 BridgeClient 与页面。
     *
     * @param bindHost    本次上报的实际 bind 地址；空串 = 本次没有/未知
     * @param lanEnabled  本次上报的实际 LAN 状态；null = 本次没有/未知
     */
    private void syncEndpoint(String bindHost, Boolean lanEnabled) {
        InstanceRecord leader = registry.leader();
        long now = System.currentTimeMillis();
        String host;
        Boolean lan;
        long connectedAt;
        long disconnectedAt;
        boolean leaderChanged;
        boolean becameDisconnected;
        String previousHost;

        synchronized (leaderMetaLock) {
            if (leader == null) {
                becameDisconnected = leaderSeen;
                if (leaderSeen) {
                    lastDisconnectedAt = now;
                    leaderSeen = false;
                }
                lastLeaderPort = 0;
                leaderChanged = false;
                previousHost = "";
                host = "";
                lan = null;
                connectedAt = 0L;
                disconnectedAt = lastDisconnectedAt;
            } else {
                leaderChanged = !leaderSeen || leader.port != lastLeaderPort;
                becameDisconnected = false;
                if (leaderChanged) {
                    leaderSeen = true;
                    lastLeaderPort = leader.port;
                    leaderSince = now;
                    leaderBindHost = "";
                    leaderLanEnabled = null;
                }
                previousHost = leaderBindHost;
                if (bindHost != null && bindHost.length() > 0) leaderBindHost = bindHost;
                if (lanEnabled != null) leaderLanEnabled = lanEnabled;
                host = leaderBindHost;
                lan = leaderLanEnabled;
                connectedAt = leaderSince;
                disconnectedAt = lastDisconnectedAt;
            }
        }

        if (leader == null) {
            BridgeEndpoint.setPort(0);
            BridgeStatus.set(0, "", false, false, 0L, 0L, false,
                    "", null, connectedAt, disconnectedAt);
            if (becameDisconnected) {
                ActivityHub.warn(ActivityFeed.Domain.XIAOAI, "8771 leader 已断开");
            }
            return;
        }

        BridgeEndpoint.setPort(leader.port);
        BridgeStatus.set(leader.port, leader.model, leader.enabled, leader.hasKey,
                leader.cfgRev, leader.updatedAt, leader.hasState,
                host, lan, connectedAt, disconnectedAt);

        if (leaderChanged) {
            ActivityHub.info(ActivityFeed.Domain.XIAOAI,
                    "8771 leader 已连接 port=" + leader.port
                            + (host.length() > 0 ? " bind=" + host : ""));
        } else if (host.length() > 0 && !host.equals(previousHost)) {
            ActivityHub.info(ActivityFeed.Domain.XIAOAI, "8771 绑定地址变更 bind=" + host);
        }
    }

    void appendLogs(String text) {
        if (text == null || text.length() == 0) return;
        // 有界：单次最多 64K 字符，环形缓冲再按行限制；不落盘、不回显内容。
        LOG_RING.append(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
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

    /**
     * 最近日志文本；服务未创建/已销毁时为空串。
     * 格式来自 {@code BridgeControlClient.pushLogs}：{@code HH:mm:ss.SSS I msg}。
     */
    public static String logDump() {
        String bridge = LOG_RING.dump();
        String agent = plus.xiaoi.module.agent.AgentCallLog.dump();
        if (agent.length() == 0) return bridge;
        if (bridge.length() == 0) return agent;
        // 两段都是 HH:mm:ss.SSS 开头，按字符串排序即可保持当天时间顺序。
        List<String> all = new ArrayList<String>();
        for (String line : bridge.split("\n")) if (line.length() > 0) all.add(line);
        for (String line : agent.split("\n")) if (line.length() > 0) all.add(line);
        java.util.Collections.sort(all);
        StringBuilder sb = new StringBuilder();
        for (String line : all) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 最近一条错误日志的可读摘要；没有错误返回空串。
     * 只认「时间 + E + 内容」的行，控制面自身的调试行不会被误报成错误。
     */
    public static String lastError() {
        List<String> lines = LOG_RING.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line == null || line.length() < 16) continue;
            if (line.charAt(12) != ' ') continue;
            String time = line.substring(0, 12);
            if (!time.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) continue;
            if (line.charAt(13) != 'E') continue;
            String msg = line.length() > 15 ? line.substring(15) : "";
            return "最后错误 · " + time + " · " + msg;
        }
        return "";
    }

    /** 清空 App 内存里的日志镜像；不触碰小爱进程的落盘日志。 */
    public static void clearLogs() {
        LOG_RING.clear();
        plus.xiaoi.module.agent.AgentCallLog.clear();
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
            ActivityHub.warn(ActivityFeed.Domain.XIAOAI, "控制面回调失效（已移除）");
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
