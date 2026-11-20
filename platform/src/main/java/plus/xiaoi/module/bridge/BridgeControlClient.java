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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import plus.xiaoi.module.AppConfig;
import plus.xiaoi.module.Config;
import plus.xiaoi.module.ConfigCodec;
import plus.xiaoi.module.Hash;
import plus.xiaoi.module.Json;
import plus.xiaoi.module.Logs;
import plus.xiaoi.module.XiaoiProcess;
import plus.xiaoi.module.XiaoiProcessHost;
import plus.xiaoi.module.application.log.LogSettings;
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
    /** WP2：进程宿主；控制面不直接依赖 Hook/Bridge/Island。 */
    private static volatile XiaoiProcessHost sHost;

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
    /** 已成功送到 App 的日志序号；重连/进程重启时归零重发。 */
    private static volatile long sLogSeq;
    /** 日志尾部监听已安装（每进程一次）。 */
    private static volatile boolean sTailInstalled;
    /** 已排队的合并视图推送（合并突发写入，避免每行都发一次 Binder）。 */
    private static volatile boolean sPushScheduled;

    private BridgeControlClient() {}

    /**
     * 幂等启动：只创建一次调度器/绑定一次。Context 为空时由内部退避任务继续等待。
     */
    public static void start(Context ctx) {
        start(ctx, XiaoiProcess.host());
    }

    /** WP2：由进程宿主注入；host 为空时只记日志，不绑定服务。 */
    public static void start(Context ctx, XiaoiProcessHost host) {
        if (host == null) {
            Logs.i("控制面未启动：进程宿主为空");
            return;
        }
        sHost = host;
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
        installLogTail();
        ensureBind();
    }

    /**
     * 安装「有新日志就推」的尾部监听（每进程一次）。
     *
     * <p>合并视图的即时性来源：进程内 ring 一有新增行，1 秒后（合并突发）把增量推给
     * 控制面。没有新日志时完全没有 Binder 流量；监听器只做排队，不做 I/O。</p>
     */
    private static void installLogTail() {
        if (sTailInstalled) return;
        sTailInstalled = true;
        Logs.setTailListener(new Runnable() {
            @Override
            public void run() {
                scheduleLogPush();
            }
        });
    }

    private static void scheduleLogPush() {
        if (!sStarted || sFatal || sRemote == null) return;
        if (sPushScheduled) return;
        ScheduledExecutorService ex = sScheduler;
        if (ex == null) return;
        sPushScheduled = true;
        try {
            ex.schedule(new Runnable() {
                @Override
                public void run() {
                    sPushScheduled = false;
                    try {
                        pushLogs(sRemote);
                    } catch (Throwable ignored) {
                    }
                }
            }, 1, TimeUnit.SECONDS);
        } catch (Throwable t) {
            sPushScheduled = false;
        }
    }

    /** Bridge 开始监听后调用（可能早于控制面连接，未连接时静默忽略）。 */
    public static void onBridgeStarted() {
        final IBinder b = sRemote;
        if (b == null || sHost == null) return;
        runAsync(new Runnable() {
            @Override
            public void run() {
                reportState(b, Config.current());
            }
        });
    }

    /**
     * 主动拉一次配置（生命周期每 10s 巡检调用）。
     *
     * 这是一条兜底同步：App 保存后即使忘了发 reload，MCP 关闭/重新开启
     * 也能在没有 HTTP 监听的情况下通过控制面生效。未连接时静默跳过，
     * 由既有退避逻辑负责重连。
     */
    public static void refresh() {
        if (!sStarted || sFatal || sRemote == null) return;
        runAsync(PULL);
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
                sLogSeq = 0;
            }
            Logs.i("控制面已连接 pid=" + Process.myPid() + " port="
                    + (sHost == null ? 0 : sHost.port()));
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
            // 宿主安装早期可能还拿不到 Application；每次重试都再试一次，
            // 不能只等调用方再次 start()。
            ctx = hostContext();
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
                pushLogs(b);
                return;
            }
            String json = reply.getString(ControlKeys.KEY_CONFIG_JSON, "");
            if (json == null || json.length() == 0) {
                Logs.i("控制面返回空配置（已忽略）");
                pushLogs(b);
                return;
            }
            applyIfChanged(json);
            if (!register(b)) {
                handleDisconnect("register 无响应");
                return;
            }
            reportState(b, Config.current());
            pushLogs(b);
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

    /** 同一 JSON 只应用一次；host.onConfigReload 内部已捕获异常。 */
    private static void applyIfChanged(String json) {
        String hash = Hash.sha256Hex(json);
        if (hash.length() == 0) hash = json;
        if (hash.equals(sAppliedHash)) return;
        XiaoiProcessHost host = sHost;
        if (host == null) {
            Logs.e("控制面配置应用失败：进程宿主为空");
            return;
        }

        try {
            AppConfig ac = ConfigCodec.fromJson(json);
            Config c = Config.create(ac.baseUrl(), ac.apiKey(), ac.model(), ac.marker,
                    ac.mcpEnabled, ac.mcpToken, ac.markerTemplate, ac.enabled, ac.mcpLan,
                    ac.rev, ac.mcpNoAuth, ac.mcpWeakToken);
            LogSettings.setWire(ac.logLevel);
            Logs.setLevel(LogSettings.get());
            host.onConfigReload(c);
            host.updateAccess(c.lan(), c.bridge());
            host.updateNoAuth(c.noAuth());
            host.updateWeakToken(c.weakToken());
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
        int port = sHost == null ? 0 : sHost.port();
        info.putInt(ControlKeys.KEY_PORT, port);
        putBindInfo(info, port);
        info.putString(ControlKeys.KEY_LEGACY_JSON, legacyJson());
        Config current = Config.current();
        info.putLong(ControlKeys.KEY_CFG_REV, current == null ? 0L : current.cfgRev());
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
        int port = sHost == null ? 0 : sHost.port();
        st.putInt(ControlKeys.KEY_PORT, port);
        putBindInfo(st, port);
        st.putString(ControlKeys.KEY_VERSION, versionName());
        st.putLong(ControlKeys.KEY_CFG_REV, c.cfgRev());
        // 状态回包为空 Bundle；失败不重连（状态不是关键路径）。
        transact(b, ControlKeys.C2S_REPORT_STATE, st);
    }

    /**
     * 增量上报日志到控制面（最多 80 行）。
     *
     * <p>日志不是关键路径：取增量/解析/发送失败都静默跳过，绝不影响配置拉取或状态上报。
     * 发送成功才推进 {@link #sLogSeq}，避免丢日志；进程重启导致序号回退时归零重来。</p>
     */
    private static void pushLogs(IBinder b) {
        if (b == null) return;
        long since = sLogSeq;
        String dump = Logs.dump(80, since);
        if (dump == null || dump.length() == 0) return;
        long remoteLast = since;
        String json = null;
        try {
            Object rootObj = Json.read(dump);
            if (!(rootObj instanceof Map)) return;
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) rootObj;
            Object linesObj = root.get("lines");
            if (!(linesObj instanceof List) || ((List<?>) linesObj).isEmpty()) {
                // 没有新行：只推进游标，不发 Binder。
                sLogSeq = Json.optLong(root, "lastSeq", since);
                return;
            }
            remoteLast = Json.optLong(root, "lastSeq", since);
            // 来源进程名只发一次（行级已有 pid），App 用它给每条打 proc。
            root.put("proc", processName());
            json = Json.write(root);
        } catch (Throwable t) {
            return;
        }
        if (json == null) return;
        Bundle data = new Bundle();
        data.putString(ControlKeys.KEY_LOGS_JSON, json);
        if (sendNoReply(b, ControlKeys.C2S_APPEND_LOGS, data)) {
            sLogSeq = remoteLast;
        }
    }

    /**
     * 拉取 App 侧的合并视图（三个小爱进程 + App 自身都汇总在那里）。
     *
     * <p>未连接、App 重启、协议不匹配时返回 null，由调用方回落到本进程 ring。
     * 这是一次同步 Binder 调用，只在人工查询日志时发生。</p>
     */
    public static String pullMergedLogs(int limit, long since) {
        IBinder b = sRemote;
        if (b == null) return null;
        try {
            Bundle req = new Bundle();
            req.putString(ControlKeys.KEY_PROTOCOL, String.valueOf(ControlKeys.PROTOCOL_VERSION));
            req.putInt(ControlKeys.KEY_LOG_LIMIT, limit);
            req.putLong(ControlKeys.KEY_LOG_SINCE, since);
            Bundle reply = transact(b, ControlKeys.C2S_PULL_LOGS, req);
            if (reply == null || !reply.getBoolean(ControlKeys.KEY_OK, false)) return null;
            String json = reply.getString(ControlKeys.KEY_LOGS_JSON, "");
            return json == null || json.length() == 0 ? null : json;
        } catch (Throwable t) {
            return null;
        }
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
                                XiaoiProcessHost host = sHost;
                                if (host != null) host.showIsland(Config.current().model(), true);
                                return true;
                            case ControlKeys.S2C_CLEAR_SAFE_MODE:
                                Logs.i("控制面 clearSafeMode：WP1 尚未实现安全模式");
                                return true;
                            case ControlKeys.S2C_FLUSH_LOGS:
                                Logs.i("控制面 flushLogs：立即落盘");
                                Logs.flush();
                                return true;
                            case ControlKeys.S2C_PUSH_LOGS:
                                runAsync(new Runnable() {
                                    @Override
                                    public void run() {
                                        pushLogs(sRemote);
                                    }
                                });
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

    // ------------------------------------------------------------- 单端口：动作面 IPC

    /** 控制面是否已连上 App 进程（动作面工具是否能被转发过去）。 */
    public static boolean isConnected() {
        return sConnected && sRemote != null;
    }

    /** 取动作面工具表（JSON 数组文本）；App 未就绪返回空串。 */
    public static String agentToolsJson() {
        IBinder b = sRemote;
        if (b == null) return "";
        Bundle out = transact(b, ControlKeys.C2S_AGENT_TOOLS, new Bundle());
        if (out == null || !out.getBoolean(ControlKeys.KEY_OK, false)) return "";
        String json = out.getString(ControlKeys.KEY_AGENT_TOOLS_JSON, "");
        return json == null ? "" : json;
    }

    /**
     * 执行一个动作面工具（JSON 入 / JSON 出）；App 未就绪返回空串。
     *
     * <p>结果与 MCP {@code tools/call} 的 result 同构，桥可直接放进 JSON-RPC 响应。</p>
     */
    public static String callAgentTool(String tool, String argsJson) {
        IBinder b = sRemote;
        if (b == null) return "";
        Bundle req = new Bundle();
        req.putString(ControlKeys.KEY_AGENT_TOOL, tool == null ? "" : tool);
        req.putString(ControlKeys.KEY_AGENT_ARGS_JSON, argsJson == null ? "" : argsJson);
        Bundle out = transact(b, ControlKeys.C2S_AGENT_CALL, req);
        if (out == null || !out.getBoolean(ControlKeys.KEY_OK, false)) return "";
        String json = out.getString(ControlKeys.KEY_AGENT_RESULT_JSON, "");
        return json == null ? "" : json;
    }

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

    /**
     * 只关心是否送达的调用（日志）。失败静默：日志链路不能污染配置/状态链路。
     */
    private static boolean sendNoReply(IBinder b, int code, Bundle req) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ControlKeys.DESCRIPTOR);
            data.writeBundle(req);
            if (!b.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (SecurityException e) {
            // 日志失败不影响配置/状态链路，也不触发控制面重连/熔断。
            return false;
        } catch (Throwable t) {
            return false;
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
            o.put("noAuth", t.noAuth());
            o.put("weakToken", t.weakToken());
            return Json.write(o);
        } catch (Throwable t) {
            return "{}";
        }
    }

    /**
     * 只上报实际绑定的结果（弱令牌回退 loopback 时 lan=false），不发送令牌。
     * 端口为 0 表示本进程不是 leader，两项都不写 = App 侧保持未知。
     */
    private static void putBindInfo(Bundle b, int port) {
        if (b == null || port <= 0) return;
        boolean lan = sHost != null && sHost.isBoundLan();
        b.putString(ControlKeys.KEY_BIND_HOST, lan ? "0.0.0.0" : "127.0.0.1");
        b.putBoolean(ControlKeys.KEY_LAN_ENABLED, lan);
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

    /** 从宿主取 Application；宿主或早期 Context 不可用时返回 null。 */
    private static Context hostContext() {
        XiaoiProcessHost host = sHost;
        if (host == null) return null;
        try {
            Object ctx = host.appContext();
            return ctx instanceof Context ? (Context) ctx : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String versionName() {
        String cached = sVersionName;
        if (cached.length() > 0) return cached;
        try {
            String v = sHost == null ? "" : sHost.versionName();
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
