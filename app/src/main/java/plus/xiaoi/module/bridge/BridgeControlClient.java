package plus.xiaoi.module.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.Bridge;
import plus.xiaoi.module.Config;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.Hash;
import plus.xiaoi.module.Hook;
import plus.xiaoi.module.Island;
import plus.xiaoi.module.Json;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.core.ControlKeys;

/**
 * 小爱进程侧的控制面客户端（WP1）。
 *
 * 流程：显式 ComponentName bind 设置 App 的 BridgeControlService → 拉配置 →
 * {@link Hook#applyConfig} + {@link Bridge#updateAccess} → 注册 callback/实例信息 →
 * 上报运行时状态。绑定失败/服务死亡按 2s→5s→10s→20s→30s 退避重试，绝不抛到小爱主流程。
 *
 * 幂等：同一份配置 JSON（SHA-256 相同）不会重复 applyConfig。
 * 日志只写 pid/process/port/model/是否有 key，绝不写 apiKey/token/legacyJson。
 */
public final class BridgeControlClient {

    private static final long[] BACKOFF_MS = {2000L, 5000L, 10000L, 20000L, 30000L};
    private static final long CONNECT_TIMEOUT_MS = 8000L;
    private static final long LOG_THROTTLE_MS = 60000L;

    private static final Object LOCK = new Object();

    private static ScheduledExecutorService sScheduler;
    private static volatile Context sApp;

    private static volatile boolean sStarted;
    /** 已发出 bindService 且尚未断开。 */
    private static volatile boolean sBound;
    private static volatile boolean sConnected;
    private static volatile boolean sRetryScheduled;
    /** UID 被服务端明确拒绝后停止重试，避免刷屏（旧推送路径仍可用）。 */
    private static volatile boolean sFatal;
    private static volatile boolean sPullInFlight;

    private static volatile int sAttempt;
    private static volatile int sBindToken;
    private static volatile long sLastLogAt;

    private static volatile IBinder sRemote;
    private static volatile Messenger sCallback;
    private static volatile String sAppliedHash = "";
    private static volatile String sProcessName = "";
    private static volatile String sVersionName = "";

    private BridgeControlClient() {}

    /**
     * 幂等启动：只创建一次调度器/绑定一次。Context 为空时由内部退避任务继续等待。
     */
    public static void start(Context ctx) {
        if (ctx != null) sApp = ctx.getApplicationContext();
        boolean first;
        synchronized (LOCK) {
            first = !sStarted;
            if (first) {
                sStarted = true;
                sScheduler = newDaemonScheduler();
            }
        }
        if (first) Logs.i("控制面客户端启动");
        ensureBind();
    }

    /** Bridge 开始监听后调用（可能早于控制面连接，未连接时静默忽略）。 */
    public static void onBridgeStarted() {
        final IBinder b = sRemote;
        if (b == null) return;
        runAsync(new Runnable() {
            @Override
            public void run() {
                reportState(b, Config.current());
            }
        });
    }

    // ------------------------------------------------------------- 绑定与重连

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null) {
                handleDisconnect("null binder");
                return;
            }
            synchronized (LOCK) {
                sRemote = binder;
                sConnected = true;
                sBound = true;
                sAttempt = 0;
            }
            Logs.i("控制面已连接 pid=" + Process.myPid() + " port=" + Bridge.port());
            runAsync(PULL);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleDisconnect("service disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            handleDisconnect("binding died");
        }
    };

    private static void ensureBind() {
        Context ctx;
        synchronized (LOCK) {
            if (!sStarted || sFatal || sConnected || sBound) return;
            ctx = sApp;
        }
        if (ctx == null) {
            // Bridge.install 早期可能还拿不到 Application；每次重试都再试一次，
            // 不能只等调用方再次 start()。
            ctx = Bridge.appContext();
            if (ctx != null) {
                ctx = ctx.getApplicationContext();
                sApp = ctx;
            }
        }
        if (ctx == null) {
            scheduleRetry();
            return;
        }

        final int token;
        synchronized (LOCK) {
            if (sConnected || sBound) return;
            token = ++sBindToken;
            sBound = true;
        }

        boolean ok = false;
        try {
            Intent it = new Intent();
            it.setComponent(new ComponentName(ControlKeys.SERVICE_PACKAGE, ControlKeys.SERVICE_CLASS));
            ok = ctx.bindService(it, CONNECTION, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            logThrottled("控制面 bind 异常: " + t.getClass().getSimpleName());
        }
        if (!ok) {
            synchronized (LOCK) {
                if (sBindToken == token) sBound = false;
            }
            scheduleRetry();
            return;
        }
        armWatchdog(token);
    }

    /** bindService 返回 true 但服务一直不回调（force-stop/组件被禁用）时的兜底。 */
    private static void armWatchdog(final int token) {
        ScheduledExecutorService s = sScheduler;
        if (s == null) return;
        try {
            s.schedule(new Runnable() {
                @Override
                public void run() {
                    boolean lost;
                    synchronized (LOCK) {
                        lost = sStarted && sBound && !sConnected && sBindToken == token;
                        if (lost) sBound = false;
                    }
                    if (!lost) return;
                    safeUnbind();
                    scheduleRetry();
                }
            }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
    }

    private static void handleDisconnect(String why) {
        synchronized (LOCK) {
            sConnected = false;
            sRemote = null;
            sBound = false;
        }
        Logs.i("控制面断开（" + why + "），准备重连");
        safeUnbind();
        scheduleRetry();
    }

    private static void scheduleRetry() {
        final long delay;
        synchronized (LOCK) {
            if (!sStarted || sFatal || sRetryScheduled) return;
            sRetryScheduled = true;
            int idx = Math.min(sAttempt, BACKOFF_MS.length - 1);
            delay = BACKOFF_MS[idx];
            if (sAttempt < 1000) sAttempt++;
        }
        logThrottled("控制面未连接，" + delay + "ms 后重试");
        ScheduledExecutorService s = sScheduler;
        if (s == null) {
            synchronized (LOCK) {
                sRetryScheduled = false;
            }
            return;
        }
        try {
            s.schedule(new Runnable() {
                @Override
                public void run() {
                    synchronized (LOCK) {
                        sRetryScheduled = false;
                    }
                    ensureBind();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            synchronized (LOCK) {
                sRetryScheduled = false;
            }
        }
    }

    private static void safeUnbind() {
        Context ctx = sApp;
        if (ctx == null) return;
        try {
            ctx.unbindService(CONNECTION);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- 拉配置 / 注册 / 上报

    private static final Runnable PULL = new Runnable() {
        @Override
        public void run() {
            pull();
        }
    };

    private static void pull() {
        IBinder b;
        synchronized (LOCK) {
            if (sPullInFlight) return;
            sPullInFlight = true;
            b = sRemote;
        }
        if (b == null) {
            synchronized (LOCK) {
                sPullInFlight = false;
            }
            return;
        }
        try {
            Bundle req = new Bundle();
            req.putInt(ControlKeys.KEY_PROTOCOL, ControlKeys.PROTOCOL_VERSION);
            req.putInt(ControlKeys.KEY_PID, Process.myPid());
            req.putString(ControlKeys.KEY_PROCESS, processName());

            Bundle reply = transact(b, ControlKeys.C2S_PULL_CONFIG, req);
            if (reply == null) {
                handleDisconnect("pull 无响应");
                return;
            }
            if (!reply.getBoolean(ControlKeys.KEY_OK, false)) {
                String err = reply.getString(ControlKeys.KEY_ERROR, "");
                Logs.i("控制面拉取配置失败: " + safe(err, 80));
                // 配置读失败不影响注册/状态上报；下次 reload 再拉。
                if (!register(b)) {
                    handleDisconnect("register 无响应");
                    return;
                }
                reportState(b, Config.current());
                return;
            }
            String json = reply.getString(ControlKeys.KEY_CONFIG_JSON, "");
            if (json == null || json.length() == 0) {
                Logs.i("控制面返回空配置（已忽略）");
                return;
            }
            applyIfChanged(json);
            if (!register(b)) {
                handleDisconnect("register 无响应");
                return;
            }
            reportState(b, Config.current());
        } catch (SecurityException e) {
            sFatal = true;
            synchronized (LOCK) {
                sConnected = false;
                sRemote = null;
                sBound = false;
            }
            safeUnbind();
            Logs.e("控制面拒绝当前 UID，停止重试（旧推送路径不受影响）");
        } catch (Throwable t) {
            Logs.i("控制面拉取异常（已忽略）: " + t.getClass().getSimpleName());
            handleDisconnect("pull exception");
        } finally {
            synchronized (LOCK) {
                sPullInFlight = false;
            }
        }
    }

    /** 同一 JSON 只应用一次；Hook.applyConfig 内部已捕获异常。 */
    private static void applyIfChanged(String json) {
        String hash = Hash.sha256Hex(json);
        if (hash.length() == 0) hash = json;
        if (hash.equals(sAppliedHash)) return;

        try {
            AppConfig ac = ConfigCodec.fromJson(json);
            Config c = Config.create(ac.baseUrl(), ac.apiKey(), ac.model(), ac.marker,
                    ac.mcpEnabled, ac.mcpToken, ac.markerTemplate, ac.enabled, ac.mcpLan);
            Hook.applyConfig(c);
            Bridge.updateAccess(c.lan(), c.bridge());
            sAppliedHash = hash;
            Logs.i("控制面已应用配置 model=" + c.model()
                    + " enabled=" + c.enabled()
                    + " bridge=" + c.bridge()
                    + " hasKey=" + (c.apiKey().length() > 0));
        } catch (Throwable t) {
            // 配置解析失败只影响本次应用；连接保留，等待下一次 reload。
            Logs.e("控制面配置解析失败（已忽略）: " + t.getClass().getSimpleName());
        }
    }

    private static boolean register(IBinder b) {
        Bundle info = new Bundle();
        info.putBinder(ControlKeys.KEY_CALLBACK, callback().getBinder());
        info.putInt(ControlKeys.KEY_PID, Process.myPid());
        info.putString(ControlKeys.KEY_PROCESS, processName());
        info.putInt(ControlKeys.KEY_PORT, Bridge.port());
        info.putString(ControlKeys.KEY_LEGACY_JSON, legacyJson());
        return transact(b, ControlKeys.C2S_REGISTER, info) != null;
    }

    private static void reportState(IBinder b, Config c) {
        if (c == null) return;
        Bundle st = new Bundle();
        st.putInt(ControlKeys.KEY_PROTOCOL, ControlKeys.PROTOCOL_VERSION);
        st.putString(ControlKeys.KEY_PROCESS, processName());
        st.putInt(ControlKeys.KEY_PID, Process.myPid());
        st.putString(ControlKeys.KEY_MODEL, c.model());
        st.putBoolean(ControlKeys.KEY_ENABLED, c.enabled());
        st.putBoolean(ControlKeys.KEY_HAS_KEY, c.apiKey().length() > 0);
        st.putInt(ControlKeys.KEY_PORT, Bridge.port());
        st.putString(ControlKeys.KEY_VERSION, versionName());
        // 状态回包为空 Bundle；失败不重连（状态不是关键路径）。
        transact(b, ControlKeys.C2S_REPORT_STATE, st);
    }

    // ------------------------------------------------------------- callback

    private static Messenger callback() {
        Messenger m = sCallback;
        if (m != null) return m;
        synchronized (LOCK) {
            if (sCallback == null) {
                Handler h = new Handler(Looper.getMainLooper(), new Handler.Callback() {
                    @Override
                    public boolean handleMessage(Message msg) {
                        switch (msg.what) {
                            case ControlKeys.S2C_RELOAD:
                                runAsync(PULL);
                                return true;
                            case ControlKeys.S2C_SHOW_ISLAND:
                                Island.show(Config.current().model(), true);
                                return true;
                            case ControlKeys.S2C_CLEAR_SAFE_MODE:
                                Logs.i("控制面 clearSafeMode：WP1 尚未实现安全模式");
                                return true;
                            case ControlKeys.S2C_FLUSH_LOGS:
                                Logs.i("控制面 flushLogs：WP1 日志只在内存，无需刷新");
                                return true;
                            default:
                                return false;
                        }
                    }
                });
                sCallback = new Messenger(h);
            }
            return sCallback;
        }
    }

    // ------------------------------------------------------------- 协议 / 工具

    private static Bundle transact(IBinder b, int code, Bundle req) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ControlKeys.DESCRIPTOR);
            data.writeBundle(req);
            if (!b.transact(code, data, reply, 0)) return null;
            reply.readException();
            return reply.readBundle(BridgeControlClient.class.getClassLoader());
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable t) {
            Logs.i("控制面调用失败 code=" + code
                    + "（" + t.getClass().getSimpleName() + "）");
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** 0.11 旧运行时快照（只经 Binder 传给 App，内存保存；绝不写日志）。 */
    private static String legacyJson() {
        try {
            Config t = Config.forTarget();
            Map<String, Object> o = Json.obj();
            o.put("baseUrl", t.baseUrl());
            o.put("model", t.model());
            o.put("apiKey", t.apiKey());
            o.put("token", t.token());
            o.put("marker", t.marker());
            o.put("markerTemplate", t.markerTemplate());
            o.put("enabled", t.enabledFlag());
            o.put("lan", t.lan());
            o.put("bridge", t.bridge());
            return Json.write(o);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String processName() {
        String cached = sProcessName;
        if (cached.length() > 0) return cached;
        String name = "";
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentProcessName");
            Object v = m.invoke(null);
            if (v instanceof String) name = (String) v;
        } catch (Throwable ignored) {
        }
        if (name.length() == 0) {
            Context c = sApp;
            name = c == null ? "" : c.getPackageName();
        }
        sProcessName = name;
        return name;
    }

    private static String versionName() {
        String cached = sVersionName;
        if (cached.length() > 0) return cached;
        try {
            String v = Bridge.versionName();
            if (v != null && v.length() > 0) sVersionName = v;
        } catch (Throwable ignored) {
        }
        return sVersionName;
    }

    private static void runAsync(Runnable r) {
        ScheduledExecutorService s = sScheduler;
        if (s == null || r == null) return;
        try {
            s.execute(r);
        } catch (Throwable ignored) {
        }
    }

    private static void logThrottled(String msg) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - sLastLogAt < LOG_THROTTLE_MS) return;
            sLastLogAt = now;
        }
        Logs.i(msg);
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        String v = s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static ScheduledExecutorService newDaemonScheduler() {
        final ThreadFactory base = Executors.defaultThreadFactory();
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private int n;

            @Override
            public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setDaemon(true);
                t.setName("xiaoi-control-" + (n++));
                return t;
            }
        });
    }
}
