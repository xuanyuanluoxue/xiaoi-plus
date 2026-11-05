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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 本地能力桥（可选）：默认只绑 127.0.0.1，开启「局域网访问」且 token 非空时
 * 改为监听 0.0.0.0，让同一 Wi-Fi 下的电脑/手机用 Bearer token 直连。
 * 端口从 8771 起依次尝试 8771~8775（主进程 / :core / :voice_trigger 共存时自动错开），
 * 供本机或局域网脚本调用小爱的 CLI，并接收 {@code POST /config} 推送的配置。
 *
 * v0.8 起协议逻辑拆到纯 JVM 层：HTTP 线协议在 {@link Http}，
 * MCP/JSON-RPC 与工具表在 {@link Mcp}（含各自的桌面单测）；
 * 本类只保留 Android 侧接线：Xposed hook、CLI binder 反射、
 * prefs 写入、socket 生命周期与鉴权策略。
 *
 * 除 {@code POST /config}（仅 loopback，且当前进程尚未配置 token 时允许免鉴权）与
 * {@code POST /island}（仅 loopback，无副作用）外，所有请求必须带
 * {@code Authorization: Bearer <token>}；token 为空时一律 401。
 * 局域网模式不会放宽这两条 loopback 限制：改配置和弹岛只能在本机发起。
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
    private static volatile ExecutorService sPool;

    /** 工具执行的 Android 侧后端；Mcp 层通过它访问一切非纯逻辑能力。 */
    private static final Mcp.Host HOST = new Mcp.Host() {
        @Override
        public boolean facadeReady() { return sFacade != null; }

        @Override
        public boolean cliReady() { return sCli != null; }

        @Override
        public int port() { return sPort; }

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
            Config c = Config.create(n.baseUrl, n.apiKey, n.model, n.marker, n.bridge, n.token,
                    n.markerTemplate, n.enabled, n.lan);
            if (!writeTargetPrefs(c)) return false;
            sToken = n.token.trim();
            updateAccess(n.lan, n.bridge);
            Config.setCurrent(c);
            Hook.applyConfig(c);
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

    /** 由 Hook 在抓到 OsbotFacadeImpl 实例后调用；服务只启动一次，可重复调用。 */
    public static void install(ClassLoader cl, Object facade) {
        if (cl == null) return;
        sFacade = facade;
        boolean first;
        synchronized (LOCK) {
            first = !sTried;
            sTried = true;
        }
        try {
            sToken = safeToken();
            sLan = safeLan();
            sBridge = safeBridge();
            if (first) {
                if (sToken.length() == 0) {
                    log("能力桥已开启但未配置 token，仅允许 loopback 首次 POST /config");
                }
                hookSessions(cl);
                hookServiceBinding(cl);
                bindCli(cl);
            }
            if (!sStarted && startServer(PORT_START)) {
                log("能力桥已监听 " + bindHost() + ":" + sPort
                        + (sBoundLan ? "（局域网模式，需要 Bearer token）" : ""));
            }
        } catch (Throwable t) {
            log("能力桥启动失败（已忽略）: " + t);
        }
    }

    /** 实际监听端口；0 表示未启动。 */
    public static int port() {
        return sPort;
    }

    /** 配置热更新后同步内存 token（空 token 等于重新打开首次配置窗口，仅 loopback）。 */
    public static void updateToken(String token) {
        try {
            sToken = token == null ? "" : token.trim();
            // token 从空变为非空、或反过来，都会改变「能否安全开放局域网」的结论。
            rebindIfNeeded();
        } catch (Throwable ignored) {
        }
    }

    /** 配置热更新后同步局域网开关与 MCP 开关；绑定模式变化时原地重启监听。 */
    public static void updateAccess(boolean lan, boolean bridge) {
        sLan = lan;
        sBridge = bridge;
        rebindIfNeeded();
    }

    private static void rebindIfNeeded() {
        if (!sStarted) return;
        boolean want = sLan && sBridge && sToken.length() > 0;
        if (want == sBoundLan) return;
        synchronized (LOCK) {
            int preferred = sPort;
            closeQuietly(sServer);
            sServer = null;
            sStarted = false;
            sPort = 0;
            startServer(preferred);
        }
    }

    private static String bindHost() {
        return sBoundLan ? "0.0.0.0" : "127.0.0.1";
    }

    private static String safeToken() {
        try {
            String t = Config.forTarget().token();
            return t == null ? "" : t.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean safeLan() {
        try {
            return Config.forTarget().lan();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean safeBridge() {
        try {
            return Config.forTarget().bridge();
        } catch (Throwable t) {
            return false;
        }
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
        if (sCli != null) return;
        try {
            final Context ctx = appContext();
            if (ctx == null) {
                log("bindCli: 拿不到 Context");
                return;
            }
            Intent it = new Intent("com.aios.osbot.action.CLI_COMMAND");
            it.setClassName("com.miui.voiceassist", "com.aios.osbot.external.CliCommandService");
            boolean ok = ctx.bindService(it, new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName n, IBinder b) {
                    try {
                        captureBinder(cl, b);
                    } catch (Throwable t) {
                        log("onServiceConnected 失败（已忽略）: " + t);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName n) {
                    sCli = null;
                }
            }, Context.BIND_AUTO_CREATE);
            if (!ok) log("bindCli 被拒绝（服务可能不可用）");
        } catch (Throwable t) {
            log("bindCli 失败（已忽略）: " + t);
        }
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- socket 服务

    private static boolean startServer(int preferred) {
        if (sStarted) return true;
        synchronized (LOCK) {
            if (sStarted) return true;
            final boolean lan = sLan && sBridge && sToken.length() > 0;
            final String host = lan ? "0.0.0.0" : "127.0.0.1";
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
                        // agent.submit 会等 LLM 回复，单次可能几十秒；别让 4 个线程互相饿死。
                        sPool = Executors.newFixedThreadPool(8, new ThreadFactory() {
                            private int n;

                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "xiaoi-bridge-" + (n++));
                                t.setDaemon(true);
                                return t;
                            }
                        });
                    }
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            serve(bound);
                        }
                    }, "xiaoi-bridge-accept");
                    t.setDaemon(true);
                    t.start();
                    sServer = ss;
                    sBoundLan = lan;
                    sPort = port;
                    sStarted = true;
                    log("实际监听 " + host + ":" + port + (lan ? "（局域网模式）" : ""));
                    return true;
                } catch (Throwable t) {
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
                log("accept 退出: " + t);
                return;
            }
            try {
                s.setSoTimeout(10000);
            } catch (Throwable ignored) {
            }
            try {
                final Socket fs = s;
                sPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(fs);
                    }
                });
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
            // 配置推送：唯一允许免 token POST 的端点，鉴权规则单独处理。
            if ("/config".equals(req.path)) {
                handleConfig(s, req);
                return;
            }
            if (!"GET".equals(req.method)) {
                respondText(s, 405, "only GET");
                return;
            }
            // /health 是推送前的「认端口」探针：信任集合必须与 /config 对齐，
            // 否则 token 轮换时用新 token 探测不到仍持旧 token 的桥 → 配置永远推不进去
            // （健康探测失败即不认领，推送直接跳过）。内存 token 未就绪时以模块 prefs 为准备。
            if ("/health".equals(req.path)) {
                if (!healthAuthorized(req.headers)) {
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

    /** POST /config：只接受 loopback，写入小爱自己的 prefs 并热重装 hook。 */
    private static void handleConfig(Socket s, Http.Req req) {
        try {
            if (!"POST".equals(req.method)) {
                respondText(s, 405, "only POST");
                return;
            }
            if (!isLoopback(s)) {
                respondText(s, 403, "loopback only");
                return;
            }
            if (!configAuthorized(req.headers)) {
                respondText(s, 401, "unauthorized");
                return;
            }

            if (req.body.length() == 0) {
                respondText(s, 400, "empty body");
                return;
            }
            Object v = Json.read(req.body);
            if (!(v instanceof Map)) throw new Json.Exception("not a json object");
            Map<?, ?> o = (Map<?, ?>) v;
            // v0.8：缺失字段保持小爱侧当前生效值（token 轮换无需重发整份配置）；
            // 显式出现的字段按全量替换语义处理，与 v1/v2 设置页行为一致。
            Mcp.Snapshot merged = Mcp.mergeConfig(o, HOST.config());
            Config c = Config.create(merged.baseUrl, merged.apiKey, merged.model,
                    merged.marker, merged.bridge, merged.token, merged.markerTemplate,
                    merged.enabled, merged.lan);

            if (!writeTargetPrefs(c)) {
                respondText(s, 503, "target context unavailable");
                return;
            }

            sToken = c.token().trim();
            updateAccess(c.lan(), c.bridge());
            try {
                Hook.applyConfig(c);
            } catch (Throwable t) {
                log("applyConfig 失败（已忽略）: " + t);
            }

            Map<String, Object> r = Json.obj();
            r.put("ok", true);
            r.put("port", sPort);
            respondJson(s, 200, Json.write(r));
            log("配置已推送：model=" + c.model() + " bridge=" + c.bridge());
        } catch (Throwable t) {
            log("配置推送失败（已忽略）: " + t);
            try {
                respondText(s, 400, "bad config: " + t.getMessage());
            } catch (Throwable ignored) {
            }
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

    /**
     * /health 鉴权：内存 token ∪ 模块 prefs 当前 token；两者都没有时仍然 401
     * （与 contract-test 的「无 token → 401」判据保持一致）。
     *
     * 为什么要放宽到 prefs：设置页改 token 时先写 prefs 再推送，此刻内存里还是旧 token。
     * 若 /health 只认内存 token，新 token 的健康探测会 401 → 不能被认领 → 永不下发新 token。
     */
    private static boolean healthAuthorized(Map<String, String> headers) {
        try {
            String mem = sToken == null ? "" : sToken.trim();
            String prefs = modulePrefsToken();
            String primary = mem.length() > 0 ? mem : prefs;
            if (primary.length() == 0) return false;
            return Http.bearerMatchesAny(headers, primary, prefs);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * /config 鉴权：已配置 token 时必须带 Bearer；尚未配置时允许本机首次推送
     * （该窗口持续到本进程写入第一个 token 为止）。
     *
     * v0.8 修复 token 轮换死锁（两半）：
     * 1) 鉴权放宽为「内存 token」或「模块 prefs 当前 token」匹配其一——设置页重存一次即自愈；
     * 2) 缺失字段保持当前值（见 {@link Mcp#mergeConfig}）——换 token 不必重发整份配置，
     *    也就不会把小爱侧正在生效的模型配置冲掉。
     */
    private static boolean configAuthorized(Map<String, String> headers) {
        try {
            String token = sToken;
            if (token == null || token.length() == 0) return true;
            return Http.bearerMatchesAny(headers, token, modulePrefsToken());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 模块 prefs 里当前保存的桥 token（设置页的事实源）；读不到返回空串。 */
    private static String modulePrefsToken() {
        try {
            de.robv.android.xposed.XSharedPreferences p =
                    new de.robv.android.xposed.XSharedPreferences("plus.xiaoi.module", Config.PREFS);
            p.reload();
            String t = p.getString(Config.KEY_TOKEN, "");
            return t == null ? "" : t.trim();
        } catch (Throwable t) {
            return "";
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

    private static String versionName() {
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
