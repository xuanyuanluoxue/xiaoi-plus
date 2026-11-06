package plus.xiaoi.module;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import plus.xiaoi.module.bridge.BridgeControlClient;
import plus.xiaoi.module.bridge.BridgeLifecycle;
import plus.xiaoi.module.core.BridgePolicy;
import plus.xiaoi.module.core.RateLimiter;

/**
 * 本地能力桥（可选）：默认只绑 127.0.0.1，开启「局域网访问」且 token 强到
 * 32 hex 时改为监听 0.0.0.0，让同一 Wi-Fi 下的电脑/手机用 Bearer token 直连。
 * 端口从 8771 起依次尝试 8771~8775，但**只有 leader 进程**会绑定：
 * 由 {@link BridgeLifecycle} 通过 {@code files/xiaoi_bridge.lock} 选出全机唯一进程，
 * 其他进程只 hook / 拉配置，{@link #port()} 返回 0。
 *
 * v0.8 起协议逻辑拆到纯 JVM 层：HTTP 线协议在 {@link Http}，
 * MCP/JSON-RPC 与工具表在 {@link Mcp}（含各自的桌面单测）；
 * 本类只保留 Android 侧接线：Xposed hook、CLI binder 反射、
 * prefs 写入、socket 生命周期与鉴权策略。
 *
 * WP4 起数据面只服务外部 MCP/CLI：
 * - {@code POST /config} 固定 410 Gone，不解析 body、不写 prefs；
 * - 除 {@code POST /island}（仅 loopback，无副作用）外，所有请求必须带
 *   {@code Authorization: Bearer <token>}；token 为空时一律 401；
 * - {@code /health} 只接受 Bearer，不再接受模块 prefs 里的旧 token；
 * - 有界线程池 + 每源 IP 限流（/health、/island）。
 *
 * 全部网络/反射操作 fail-soft：异常只写日志，绝不影响模型重定向与标记注入。
 */
public final class Bridge {

    public static final int PORT_START = 8771;
    public static final int PORT_END = 8775;
    /** 首次尝试端口；实际端口以 {@link #port()} 为准。 */
    public static final int PORT = PORT_START;

    /** 小爱的 CLI 每次调用都会新建会话集；共享一个集合，跨请求才能复用会话。 */
    private static final Set<String> SHARED_SESSIONS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final Object LOCK = new Object();

    private static volatile boolean sTried;
    /** 本进程的数据面 hook 是否已安装（每个进程一次）；CLI 绑定可重试。 */
    private static volatile boolean sDataPlaneHooks;
    /** 本进程首次拿到的 ClassLoader，供 CLI 绑定重试使用。 */
    private static volatile ClassLoader sLoader;
    private static volatile boolean sCliBinding;
    private static volatile boolean sStarted;
    private static volatile int sPort;
    private static volatile String sToken = "";
    /** 用户配置：是否允许局域网（还需 token 非空且 MCP 开关开启才真正绑 0.0.0.0）。 */
    private static volatile boolean sLan;
    /** 用户配置：MCP 桥开关；关闭时即使 lan=true 也只绑 loopback。 */
    private static volatile boolean sBridge;
    /** 当前 socket 实际绑定的模式：true = 0.0.0.0，false = 127.0.0.1。 */
    private static volatile boolean sBoundLan;
    private static volatile ServerSocket sServer;
    private static volatile Object sFacade;
    private static volatile Object sCli;
    private static volatile ThreadPoolExecutor sPool;
    /** /health、/island 每源 IP 限流；/mcp 有 Bearer，不参与。 */
    private static final RateLimiter LOW_TRUST_RATE = new RateLimiter(30, 10_000L);

    /** 工具执行的 Android 侧后端；Mcp 层通过它访问一切非纯逻辑能力。 */
    private static final Mcp.Host HOST = new Mcp.Host() {
        @Override
        public boolean facadeReady() { return sFacade != null; }

        @Override
        public boolean cliReady() { return sCli != null; }

        @Override
        public int port() { return Bridge.port(); }

        @Override
        public String versionName() { return Bridge.versionName(); }

        @Override
        public String callCli(String cmd) { return Bridge.callCli(cmd); }

        @Override
        public Mcp.Snapshot config() {
            Config c = Config.current();
            return new Mcp.Snapshot(c.baseUrl(), c.apiKey(), c.model(),
                    c.marker(), c.bridge(), c.token(), c.markerTemplate(), c.enabledFlag(),
                    c.lan());
        }

        @Override
        public boolean writeConfig(Mcp.Snapshot n) {
            // 远程 MCP 只能改 model/marker；bridge/lan/token/enabled 一律以当前配置为准，
            // 防止远程把数据面开关或端点/密钥改掉（WP4 D10）。
            Config cur = Config.current();
            Config c = Config.create(n.baseUrl, n.apiKey, n.model, n.marker,
                    cur.bridge(), cur.token(), n.markerTemplate, n.enabled, cur.lan(),
                    cur.cfgRev());
            if (!writeTargetPrefs(c)) return false;
            Config.setCurrent(c);
            Hook.applyConfig(c);
            updateAccess(cur.lan(), cur.bridge());
            return true;
        }

        @Override
        public boolean showIsland(String text, boolean force) {
            return Island.show(text, force);
        }

        @Override
        public String logs(int limit, long since) {
            return Logs.dump(limit, since);
        }

        @Override
        public String stats(int days) {
            return Usage.stats(days);
        }
    };

    private Bridge() {}

    /**
     * 由 Hook 在抓到 OsbotFacadeImpl 实例后调用；幂等，可重复调用。
     *
     * WP3 起这里不再无条件 {@code startServer}：先保留 WP1 控制面（拉配置/上报），
     * 再由 {@link BridgeLifecycle} 决定本进程是否能成为数据面 leader。
     */
    public static void install(ClassLoader cl, Object facade) {
        if (cl == null) return;
        sFacade = facade;
        boolean first;
        synchronized (LOCK) {
            first = !sTried;
            sTried = true;
        }
        try {
            ensureDataPlaneHooks(cl);
            // WP1 控制面先起（拉配置/上报），再由 WP3 生命周期决定是否监听。
            onProcessStart(null, cl);
            if (first && sBridge && sToken.length() == 0) {
                log("MCP 开启但未配置 token，数据面保持关闭（控制面仍可拉取配置）");
            }
        } catch (Throwable t) {
            log("能力桥启动失败（已忽略）: " + t);
        }
    }

    /**
     * 每个小爱进程的 Hook 加载后（以及每次 install 时）调用：
     * 同步 Bridge 策略字段 → 启动 WP1 控制面 → 启动数据面生命周期。
     *
     * 非 leader 进程仍然 hook、仍然拉配置；只有 leader 会监听端口。
     * Context 可能为空：控制面与生命周期都会在后台重试，不会抛到主流程。
     */
    public static void onProcessStart(Config config) {
        onProcessStart(config, null);
    }

    public static void onProcessStart(Config config, ClassLoader cl) {
        try {
            if (cl != null) ensureDataPlaneHooks(cl);
            if (config == null) {
                // install 时以「控制面内存配置 vs target prefs」按 rev 取新，避免把旧值写回。
                refreshPolicyFromConfig();
            } else {
                sToken = BridgePolicy.normalize(config.token());
                sLan = config.lan();
                sBridge = config.bridge();
            }
            startControlPlane();
            BridgeLifecycle.start(appContext());
            log("能力桥进程已就绪 pid=" + android.os.Process.myPid()
                    + " process=" + processName()
                    + " mcp=" + sBridge
                    + " hasToken=" + (sToken.length() > 0)
                    + " shouldListen=" + BridgePolicy.shouldListen(sBridge, sToken)
                    + " leader=" + BridgeLifecycle.isLeader()
                    + " port=" + port());
        } catch (Throwable t) {
            log("能力桥进程初始化失败（已忽略）: " + t);
        }
    }

    /** 本进程是否已经拿到 OsbotFacadeImpl（leader 优先选有 facade 的进程）。 */
    public static boolean hasFacade() {
        return sFacade != null;
    }

    /**
     * 本进程是否已绑定小爱 CLI。WP4 起 leader 资格 = facade 或 CLI 至少有一个：
     * 两者都没有的进程（典型是只加载了 hook 的 :core）不再接管数据面，
     * 避免「端口能连、工具全不可用」。
     */
    public static boolean hasCli() {
        return sCli != null;
    }

    /**
     * 数据面所需的最小 hook/CLI 绑定：hook 每个进程只装一次；
     * CLI 绑定允许重试（Hook 早期 Context 可能还没就绪）。
     */
    private static void ensureDataPlaneHooks(ClassLoader cl) {
        if (cl == null) return;
        sLoader = cl;
        synchronized (LOCK) {
            if (!sDataPlaneHooks) {
                sDataPlaneHooks = true;
                hookSessions(cl);
                hookServiceBinding(cl);
            }
        }
        bindCli(cl);
    }

    /** 生命周期每 10s 调用：Context 晚就绪时补绑 CLI。 */
    public static void ensureCliBinding() {
        ClassLoader cl = sLoader;
        if (cl != null) bindCli(cl);
    }

    /**
     * 实际监听端口；只有 leader 且 server 在运行时才返回非 0。
     * 非 leader 进程恒为 0（它们从不绑定端口）。
     */
    public static int port() {
        if (!sStarted) return 0;
        return BridgeLifecycle.isLeader() ? sPort : 0;
    }

    /** 当前配置是否允许本进程监听数据面（纯策略，见 {@link BridgePolicy}）。 */
    public static boolean shouldListen() {
        return BridgePolicy.shouldListen(sBridge, sToken);
    }

    /** 当前配置下是否应绑 0.0.0.0（LAN 且 token 为 32 hex）。 */
    public static boolean bindsLan() {
        return "0.0.0.0".equals(BridgePolicy.bindHost(sLan, sBridge, sToken));
    }

    /** 当前 socket 是否真的绑在 0.0.0.0（弱 token 回退 loopback 时为 false）。 */
    public static boolean isBoundLan() {
        return sBoundLan;
    }

    /**
     * 从共享配置刷新策略字段（生命周期每 10s 巡检 + 配置变化后调用）。
     *
     * rev 高者优先：控制面拉到的 App 配置 rev 通常高于 target prefs；
     * rev 相同则采用 target prefs（旧路径最后写入的值），这样 leader 收到
     * {@code bridge=false} 后，其他进程即使还没收到控制面 reload 也不会误接管。
     * target prefs 读取去掉 logcat，避免每 10s 刷屏。
     */
    public static void refreshPolicyFromConfig() {
        try {
            Config mem = Config.current();
            Config prefs = Config.forTargetQuiet();
            Config c = prefs.cfgRev() >= mem.cfgRev() ? prefs : mem;
            sToken = BridgePolicy.normalize(c.token());
            sLan = c.lan();
            sBridge = c.bridge();
        } catch (Throwable ignored) {
        }
    }

    /** server 是否在当前进程运行（仅 leader 可能为 true）。 */
    public static boolean isServerRunning() {
        return sStarted;
    }

    /** 当前进程名（只用于日志/诊断，不参与任何选举逻辑）。 */
    public static String processName() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object v = at.getMethod("currentProcessName").invoke(null);
            if (v instanceof String) return (String) v;
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * WP1 控制面接线：只启动一次；任何异常只写日志。
     * feature flag（模块 prefs: control_plane，默认 true）关闭时完全不绑定服务，
     * 行为与 beta.4 的旧推送路径一致。
     */
    private static void startControlPlane() {
        try {
            if (!controlPlaneEnabled()) {
                info("控制面已通过 feature flag 关闭");
                return;
            }
            BridgeControlClient.start(appContext());
            BridgeControlClient.onBridgeStarted();
        } catch (Throwable t) {
            log("控制面启动失败（已忽略）: " + t);
        }
    }

    /** 模块 prefs 开关；读不到时默认开启（控制面本身是增量能力）。 */
    private static boolean controlPlaneEnabled() {
        try {
            de.robv.android.xposed.XSharedPreferences p =
                    new de.robv.android.xposed.XSharedPreferences("plus.xiaoi.module", Prefs.NAME);
            p.reload();
            return p.getBoolean(Prefs.KEY_CONTROL_PLANE, true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 配置热更新后同步内存 token，并交给生命周期决定启动/关闭/重绑。 */
    public static void updateToken(String token) {
        try {
            sToken = BridgePolicy.normalize(token);
            BridgeLifecycle.apply();
        } catch (Throwable ignored) {
        }
    }

    /** 配置热更新后同步局域网开关与 MCP 开关，并交给生命周期重算。 */
    public static void updateAccess(boolean lan, boolean bridge) {
        sLan = lan;
        sBridge = bridge;
        BridgeLifecycle.apply();
    }

    // ------------------------------------------------------------- hook 与 CLI 绑定

    /** 让 CLI 的 ConnectionContext 共用同一个会话集合。 */
    private static void hookSessions(final ClassLoader cl) {
        try {
            Class<?> cc = Class.forName("com.aios.osbot.cli.server.ConnectionContext", false, cl);
            XposedBridge.hookMethod(cc.getMethod("getOwnedSessions"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        param.setResult(SHARED_SESSIONS);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("会话集 hook 失败（已忽略）: " + t);
        }
    }

    /** onBind 返回的就是 CLI binder，顺手拿实例，省一次字段反射。 */
    private static void hookServiceBinding(final ClassLoader cl) {
        try {
            Class<?> svc = Class.forName("com.aios.osbot.external.CliCommandService", false, cl);
            XposedBridge.hookMethod(svc.getMethod("onBind", Intent.class), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        captureBinder(cl, param.getResult());
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("CLI 服务 hook 失败（已忽略）: " + t);
        }
    }

    /** 反射把 IBinder 转成 ICliCommandService；失败则退回直接用 binder。 */
    private static void captureBinder(ClassLoader cl, Object binder) {
        if (binder == null || sCli != null) return;
        Object cli = null;
        try {
            Class<?> stub = Class.forName("com.aios.apptoolsdk.aidl.ICliCommandService$Stub",
                    false, cl);
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            cli = asInterface.invoke(null, binder);
        } catch (Throwable t) {
            log("asInterface 反射失败，尝试直接调用: " + t);
        }
        if (cli == null && hasExecuteCommand(binder)) cli = binder;
        if (cli != null) sCli = cli;
    }

    private static boolean hasExecuteCommand(Object o) {
        if (o == null) return false;
        try {
            o.getClass().getMethod("executeCommand", String.class);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 主动绑定自家 CLI 服务，确保服务实例被创建（同进程，无 IPC 往返）。 */
    private static void bindCli(final ClassLoader cl) {
        if (sCli != null || sCliBinding) return;
        try {
            final Context ctx = appContext();
            if (ctx == null) {
                log("bindCli: 拿不到 Context");
                return;
            }
            sCliBinding = true;
            Intent it = new Intent("com.aios.osbot.action.CLI_COMMAND");
            it.setClassName("com.miui.voiceassist", "com.aios.osbot.external.CliCommandService");
            boolean ok = ctx.bindService(it, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    sCliBinding = false;
                    try {
                        captureBinder(cl, b);
                    } catch (Throwable t) {
                        log("onServiceConnected 失败（已忽略）: " + t);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    sCli = null;
                    sCliBinding = false;
                }
            }, Context.BIND_AUTO_CREATE);
            if (!ok) {
                sCliBinding = false;
                log("bindCli 被拒绝（服务可能不可用）");
            }
        } catch (Throwable t) {
            sCliBinding = false;
            log("bindCli 失败（已忽略）: " + t);
        }
    }

    public static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- socket 服务

    /**
     * 由 {@link BridgeLifecycle} 在确认本进程为 leader 后调用。
     * 再次校验策略，防止任何路径在 MCP 关闭时误开监听。
     */
    public static boolean startServerIfLeader() {
        // 防御性校验：只有持有 leader 锁的进程才允许绑定端口。
        if (!BridgeLifecycle.isLeader()) return false;
        return startServer(PORT_START);
    }

    /**
     * 彻底关闭数据面：关闭 server、清会话、复位端口/绑定模式、停掉请求线程池。
     * 可重复调用；不释放 leader 锁（锁由 {@link BridgeLifecycle} 管理）。
     */
    public static void stopServer() {
        ThreadPoolExecutor pool;
        boolean wasRunning;
        synchronized (LOCK) {
            wasRunning = sStarted;
            ServerSocket ss = sServer;
            sServer = null;
            closeQuietly(ss);
            sStarted = false;
            sPort = 0;
            sBoundLan = false;
            SHARED_SESSIONS.clear();
            pool = sPool;
            sPool = null;
        }
        if (pool != null) {
            try {
                pool.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
        if (wasRunning) log("能力桥已停止监听 pid=" + android.os.Process.myPid()
                + " process=" + processName());
    }

    private static boolean startServer(int preferred) {
        if (sStarted) return true;
        if (!BridgePolicy.shouldListen(sBridge, sToken)) {
            info("MCP 关闭或 token 为空，不启动监听");
            return false;
        }
        synchronized (LOCK) {
            if (sStarted) return true;
            final String host = BridgePolicy.bindHost(sLan, sBridge, sToken);
            final boolean lan = "0.0.0.0".equals(host);
            if (sLan && !lan) {
                // 只说明策略，不输出 token 内容。
                info("LAN 需要 32 hex token，已回退 loopback");
            }
            // 先在原端口重绑（token/局域网开关热切换时避免端口乱跳），再扫描其余端口。
            int[] order = new int[PORT_END - PORT_START + 1];
            int n = 0;
            if (preferred >= PORT_START && preferred <= PORT_END) order[n++] = preferred;
            for (int p = PORT_START; p <= PORT_END; p++) {
                if (p != preferred) order[n++] = p;
            }
            for (int i = 0; i < n; i++) {
                int port = order[i];
                ServerSocket ss = null;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress(InetAddress.getByName(host), port), 16);
                } catch (Throwable t) {
                    closeQuietly(ss);
                    // 多个小爱进程都会加载模块，端口被占是预期情况，不报错。
                    info(host + ":" + port + " 已被占用，尝试下一个端口");
                    continue;
                }
                try {
                    final ServerSocket bound = ss;
                    if (sPool == null) {
                        // 有界：core=4、max=8、queue=32。agent.submit 可能等 LLM 几十秒，
                        // 但绝不能因为慢连接无限建线程或让 accept 阻塞。
                        sPool = new ThreadPoolExecutor(4, 8, 30L, TimeUnit.SECONDS,
                                new LinkedBlockingQueue<Runnable>(32),
                                new ThreadFactory() {
                                    private int n;

                                    @Override
                                    public Thread newThread(Runnable r) {
                                        Thread t = new Thread(r, "xiaoi-bridge-" + (n++));
                                        t.setDaemon(true);
                                        return t;
                                    }
                                });
                    }
                    // 先发布状态再启动 accept：stopServer 立刻 close 也能让 accept 退出。
                    sServer = ss;
                    sBoundLan = lan;
                    sPort = port;
                    sStarted = true;
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(bound);
                        }
                    }, "xiaoi-bridge-accept");
                    t.setDaemon(true);
                    t.start();
                    log("能力桥已监听 " + host + ":" + port
                            + (lan ? "（局域网模式）" : "")
                            + " pid=" + android.os.Process.myPid()
                            + " process=" + processName());
                    return true;
                } catch (Throwable t) {
                    sServer = null;
                    sStarted = false;
                    sPort = 0;
                    sBoundLan = false;
                    closeQuietly(ss);
                    log("在 " + host + ":" + port + " 启动服务失败（已忽略）: " + t);
                }
            }
            info("无可用端口 " + PORT_START + "-" + PORT_END + "，能力桥未启动");
            return false;
        }
    }

    private static void serve(ServerSocket ss) {
        while (true) {
            Socket s;
            try {
                s = ss.accept();
            } catch (Throwable t) {
                // stopServer 会 close 掉 socket 让这里抛异常；属于正常关闭，不当错误。
                if (sServer == ss) {
                    log("accept 退出: " + t);
                } else {
                    info("accept 已停止");
                }
                return;
            }
            try {
                s.setSoTimeout(10000);
            } catch (Throwable ignored) {
            }
            try {
                final Socket fs = s;
                ThreadPoolExecutor pool = sPool;
                if (pool == null) {
                    closeQuietly(fs);
                    continue;
                }
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(fs);
                    }
                });
            } catch (RejectedExecutionException rex) {
                // 队列满：立即回 503 并断开，不阻塞 accept。
                try {
                    Http.respond(s.getOutputStream(), 503,
                            "text/plain; charset=utf-8", "busy");
                } catch (Throwable ignored) {
                }
                closeQuietly(s);
            } catch (Throwable t) {
                closeQuietly(s);
            }
        }
    }

    // ------------------------------------------------------------- 请求分发

    private static void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            Http.Req req = Http.read(in);
            if (req == null) return;
            if (req.method.length() == 0 || req.target.length() == 0) {
                respondText(s, 400, "bad request");
                return;
            }

            // 低信任端点限流：每源 IP 每 10s 最多 30 次；/mcp 有 Bearer，不参与。
            if ("/health".equals(req.path) || "/island".equals(req.path)) {
                if (!LOW_TRUST_RATE.allow(req.path + "|" + peerKey(s))) {
                    respondText(s, 429, "too many requests");
                    return;
                }
            }

            // MCP：标准 JSON-RPC over HTTP，只接受 POST（GET 返回 405）。
            if ("/mcp".equals(req.path)) {
                handleMcp(s, req);
                return;
            }
            // 手动触发一次超级岛（设置页按钮用）
            if ("/island".equals(req.path)) {
                handleIsland(s, req);
                return;
            }
            // WP4：配置只由手机 App 写；旧 HTTP 推送固定 410，不读 body、不写 prefs。
            if ("/config".equals(req.path)) {
                handleConfig(s, req);
                return;
            }
            if (!"GET".equals(req.method)) {
                respondText(s, 405, "only GET");
                return;
            }
            // /health 只接受 Bearer；不再回退模块 prefs 的旧 token。
            if ("/health".equals(req.path)) {
                if (!authorized(req.headers)) {
                    respondText(s, 401, "unauthorized");
                    return;
                }
                respondText(s, 200, "ok facade=" + (sFacade != null) + " port=" + sPort);
                return;
            }
            if (!authorized(req.headers)) {
                respondText(s, 401, "unauthorized");
                return;
            }

            if ("/logs".equals(req.path)) {
                int limit = 80;
                long since = 0;
                try { limit = Integer.parseInt(req.query.get("limit")); } catch (Throwable ignored) {}
                try { since = Long.parseLong(req.query.get("since")); } catch (Throwable ignored) {}
                if (limit <= 0 || limit > 300) limit = 80;
                respondJson(s, 200, Logs.dump(limit, since));
                return;
            }
            // 调用量统计：?days=1..90（默认 7），总量来自小爱各进程共享的追加日志。
            if ("/stats".equals(req.path)) {
                int days = 7;
                try { days = Integer.parseInt(req.query.get("days")); } catch (Throwable ignored) {}
                if (days <= 0 || days > 90) days = 7;
                respondJson(s, 200, Usage.stats(days));
                return;
            }
            if ("/cli".equals(req.path)) {
                String cmd = req.query.get("cmd");
                if (cmd == null || cmd.length() == 0) {
                    respondText(s, 400, "missing cmd");
                    return;
                }
                String out = callCli(cmd);
                if (out == null) {
                    respondText(s, 503, "ERR cli service not ready");
                } else {
                    respondText(s, 200, out);
                }
                return;
            }
            respondText(s, 404, "not found: " + req.path);
        } catch (java.io.IOException ioe) {
            // 超长行/header 超限：拒绝请求，不把它当内部错误。
            try {
                respondText(s, 431, "request header too large");
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            log("请求处理失败（已忽略）: " + t);
            try {
                respondText(s, 500, "error");
            } catch (Throwable ignored) {
            }
        } finally {
            closeQuietly(s);
        }
    }

    /**
     * 最小可用的 MCP Streamable HTTP 服务端：JSON-RPC 2.0、单端点、无会话。
     * 协议处理在 {@link Mcp#handle}；这里只做方法 / loopback / 鉴权三道闸。
     */
    private static void handleMcp(Socket s, Http.Req req) {
        try {
            if (!"POST".equals(req.method)) {
                respondText(s, 405, "only POST");
                return;
            }
            // 局域网模式只对 MCP 开放；即使绑了 0.0.0.0，也要 token 正确（见下）。
            if (!isLoopback(s) && !sBoundLan) {
                respondText(s, 403, "loopback only");
                return;
            }
            if (!authorized(req.headers)) {
                respondText(s, 401, "unauthorized");
                return;
            }
            String out = Mcp.handle(req.body, HOST);
            if (out == null) {
                Http.respondAccepted(s.getOutputStream());
                return;
            }
            respondJson(s, 200, out);
        } catch (Throwable t) {
            log("MCP 请求处理失败（已忽略）: " + t);
            try {
                respondJson(s, 200, Mcp.rpcError(null, -32603, "internal error"));
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handleIsland(Socket s, Http.Req req) {
        try {
            if (!"POST".equals(req.method)) {
                respondText(s, 405, "only POST");
                return;
            }
            if (!isLoopback(s)) {
                respondText(s, 403, "loopback only");
                return;
            }
            // 只触发一次提示、不含任何配置/密钥，允许本机应用免 token 调用，
            // 让设置页在没有 token 的情况下也能验证岛通路。
            Config c = Config.current();
            boolean ok = Island.show(c.model(), true);
            Map<String, Object> r = Json.obj();
            r.put("ok", ok);
            r.put("text", c.model());
            respondJson(s, 200, Json.write(r));
        } catch (Throwable t) {
            try {
                respondText(s, 500, "error");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * POST /config：WP4 起固定 410 Gone。
     *
     * 不读取/解析 body、不鉴权、不写 prefs、不热更新；GET 等其他方法仍是 405。
     * 旧 App/脚本会失败，这是有意的破坏性变更：配置只允许在手机 App 内修改。
     */
    private static void handleConfig(Socket s, Http.Req req) {
        if (!"POST".equals(req.method)) {
            try {
                respondText(s, 405, "only POST");
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            respondText(s, 410, "gone: /config 已停用，请在小爱同学+ App 内修改配置");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- 鉴权策略

    private static boolean authorized(Map<String, String> headers) {
        try {
            String token = sToken;
            if (token == null || token.length() == 0) return false;
            return Http.bearerMatchesAny(headers, token);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 限流 key：来源地址；拿不到时用固定串（等价全局限流，仍是有界的）。 */
    private static String peerKey(Socket s) {
        try {
            InetAddress a = s.getInetAddress();
            if (a == null) return "unknown";
            String h = a.getHostAddress();
            return h == null ? "unknown" : h;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------- 基础设施

    /**
     * 写入小爱自己的 SharedPreferences（当前进程 = 小爱进程，目录自然属于小爱），
     * 字段名与 Config.KEY_* 保持一致，后续 Config.forTarget() 可直接读到。
     */
    private static boolean writeTargetPrefs(Config c) {
        try {
            Context ctx = appContext();
            if (ctx == null) return false;
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
            p.edit()
                    .putString(Config.KEY_BASE_URL, c.baseUrl())
                    .putString(Config.KEY_API_KEY, c.apiKey())
                    .putString(Config.KEY_MODEL, c.model())
                    .putBoolean(Config.KEY_MARKER, c.marker())
                    .putBoolean(Config.KEY_BRIDGE, c.bridge())
                    .putBoolean(Config.KEY_MCP_LAN, c.lan())
                    .putString(Config.KEY_TOKEN, c.token())
                    .putString(Config.KEY_MARKER_TPL, c.markerTemplate())
                    .putString(Config.KEY_ENABLED, c.enabledFlag() ? "1" : "0")
                    .putLong(Config.KEY_CFG_REV, c.cfgRev())
                    .commit();
            return true;
        } catch (Throwable t) {
            log("写入小爱 prefs 失败（已忽略）: " + t);
            return false;
        }
    }

    private static boolean isLoopback(Socket s) {
        try {
            InetAddress a = s.getInetAddress();
            if (a == null) return false;
            return a.isLoopbackAddress() || "127.0.0.1".equals(a.getHostAddress());
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return CLI 原始响应；服务未就绪返回 null。 */
    private static String callCli(String cmd) {
        Object cli = sCli;
        if (cli == null) return null;
        try {
            Method m = cli.getClass().getMethod("executeCommand", String.class);
            m.setAccessible(true);
            Object r = m.invoke(cli, cmd);
            return r == null ? "" : String.valueOf(r);
        } catch (Throwable t) {
            log("CLI 调用失败: " + t);
            return "ERR " + t;
        }
    }

    public static String versionName() {
        try {
            Context ctx = appContext();
            if (ctx != null) {
                String v = ctx.getPackageManager()
                        .getPackageInfo("plus.xiaoi.module", 0).versionName;
                if (v != null && v.length() > 0) return v;
            }
        } catch (Throwable ignored) {
        }
        return "0.2.0";
    }

    private static void respondText(Socket s, int code, String body) throws Exception {
        Http.respond(s.getOutputStream(), code, "text/plain; charset=utf-8", body);
    }

    private static void respondJson(Socket s, int code, String body) throws Exception {
        Http.respond(s.getOutputStream(), code, "application/json; charset=utf-8", body);
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        Logs.i(msg);
    }

    /** 预期内的竞争/环境信息，不当错误上报。 */
    private static void info(String msg) {
        Logs.i("I/" + msg);
    }
}
