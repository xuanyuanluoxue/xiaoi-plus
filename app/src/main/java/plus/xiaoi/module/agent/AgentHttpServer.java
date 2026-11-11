package plus.xiaoi.module.agent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.Http;

/**
 * App 进程内的 Agent HTTP 端点（纯 JVM）。
 *
 * <p>默认只绑 {@code 127.0.0.1:8770}；只有配置显式打开 {@code agentLan} 且
 * token 为 32 位十六进制时才绑 {@code 0.0.0.0}（见 {@link AgentLanPolicy}），
 * 弱 token 自动回退 loopback。路由只有两个：{@code POST /mcp}（Bearer + JSON-RPC）
 * 和 {@code GET /health}。所有请求都要求 Bearer token；token 来自 App 配置的
 * {@code mcpToken}，与 8771 共用同一个值。</p>
 *
 * <p>线程模型：一个 accept 线程 + 有界请求池（core 2 / max 4 / queue 16）。
 * 队列满时直接 503，不无限排队；stop() 关闭 server socket 与线程池，幂等。</p>
 */
public final class AgentHttpServer {

    public static final String HOST = "127.0.0.1";
    /** 首选端口；被别的进程占用时按 PORT_MIN~PORT_MAX 依次顺延。 */
    public static final int DEFAULT_PORT = 8770;
    public static final int PORT_MIN = 8770;
    public static final int PORT_MAX = 8775;
    /** 兼容旧引用（等于首选端口）。 */
    public static final int PORT = DEFAULT_PORT;

    private static final int REQUEST_CORE = 2;
    private static final int REQUEST_MAX = 4;
    private static final int REQUEST_QUEUE = 16;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final String token;
    private final AgentMcp mcp;
    private final ActionRegistry registry;
    /** 配置请求的局域网开关；实际是否监听 0.0.0.0 由 AgentLanPolicy 决定。 */
    private final boolean lan;

    private volatile boolean running;
    private volatile String lastError = "";
    private volatile String boundHost = AgentLanPolicy.LOOPBACK;
    /** 实际绑定成功的端口（顺延后可能与 DEFAULT_PORT 不同）。 */
    private volatile int port = DEFAULT_PORT;
    private ServerSocket server;
    private ExecutorService acceptExecutor;
    private ThreadPoolExecutor requestPool;

    /** 等价 {@code lan=false}：保持 WP1.2a 构造方式，8770 仅本机。 */
    public AgentHttpServer(String token, AgentMcp mcp, ActionRegistry registry) {
        this(token, mcp, registry, false);
    }

    public AgentHttpServer(String token, AgentMcp mcp, ActionRegistry registry, boolean lan) {
        this.token = token == null ? "" : token.trim();
        this.mcp = mcp;
        this.registry = registry;
        this.lan = lan;
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return running;
    }

    /** 实际是否监听 0.0.0.0（弱令牌回退时为 false）。 */
    public boolean isLan() {
        return running && AgentLanPolicy.ANY.equals(boundHost);
    }

    /** 最近一次启动实际绑定的地址；未启动时为 {@code 127.0.0.1}。 */
    public String bindHost() {
        return boundHost;
    }

    /**
     * 最近一次启动的错误，或「请求 LAN 但令牌不足、已回退」的说明；空串 = 正常。
     * 运行时（{@link #isRunning()} 为 true）出现非空内容只代表回退，不代表启动失败。
     */
    public String lastError() {
        return lastError;
    }

    /**
     * 启动监听。
     *
     * @return 空串 = 成功；非空 = 明确错误（不抛异常、不崩溃）
     */
    public synchronized String start() {
        if (running) return "";
        if (token.length() == 0) {
            lastError = "mcpToken 为空，拒绝启动 8770";
            return lastError;
        }
        if (mcp == null || registry == null) {
            lastError = "AgentMcp/ActionRegistry 未初始化";
            return lastError;
        }

        ServerSocket ss = null;
        String host = AgentLanPolicy.bindHost(lan, token);
        String fallback = AgentLanPolicy.fallbackReason(lan, token);
        try {
            String bindFailure = "";
            for (int candidate = PORT_MIN; candidate <= PORT_MAX; candidate++) {
                ServerSocket attempt = null;
                try {
                    attempt = new ServerSocket();
                    attempt.setReuseAddress(true);
                    attempt.bind(new InetSocketAddress(InetAddress.getByName(host), candidate));
                    attempt.setSoTimeout(0);
                    ss = attempt;
                    port = candidate;
                    break;
                } catch (Throwable t) {
                    closeQuietly(attempt);
                    if (bindFailure.length() == 0) {
                        bindFailure = t.getClass().getSimpleName()
                                + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
                    }
                }
            }
            if (ss == null) {
                lastError = "绑定 " + host + ":" + PORT_MIN + "~" + PORT_MAX
                        + " 全部失败：" + bindFailure;
                AgentRuntimeStatus.setError(lastError);
                return lastError;
            }
            server = ss;
            boundHost = host;
            running = true;
            // 回退说明放在 lastError（running=true 时不是失败），方便通知/日志如实展示。
            lastError = port == DEFAULT_PORT
                    ? fallback
                    : (fallback.length() == 0 ? "" : fallback + "；")
                      + "端口 " + DEFAULT_PORT + " 被占用，已顺延到 " + port;
            acceptExecutor = Executors.newSingleThreadExecutor(
                    factory("xiaoi-agent-accept"));
            requestPool = new ThreadPoolExecutor(REQUEST_CORE, REQUEST_MAX,
                    KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<Runnable>(REQUEST_QUEUE),
                    factory("xiaoi-agent-http"),
                    new ThreadPoolExecutor.AbortPolicy());
            acceptExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            });
            return "";
        } catch (Throwable t) {
            lastError = "绑定 " + host + ":" + PORT_MIN + "~" + PORT_MAX + " 失败："
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
            closeQuietly(ss);
            server = null;
            running = false;
            boundHost = AgentLanPolicy.LOOPBACK;
            shutdownAccept();
            shutdownRequests();
            AgentRuntimeStatus.setError(lastError);
            return lastError;
        }
    }

    /** 停止监听；可重复调用。 */
    public synchronized void stop() {
        running = false;
        ServerSocket ss = server;
        server = null;
        closeQuietly(ss);
        shutdownAccept();
        shutdownRequests();
    }

    // ------------------------------------------------------------- accept / 请求

    private void acceptLoop() {
        ServerSocket ss = server;
        while (running && ss != null) {
            Socket socket = null;
            try {
                socket = ss.accept();
                socket.setSoTimeout(READ_TIMEOUT_MS);
                final Socket s = socket;
                try {
                    requestPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            handle(s);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    AgentCallLog.add("W", "http", "503 busy");
                    respondQuietly(s, 503, "text/plain; charset=utf-8", "busy");
                    closeQuietly(s);
                } catch (Throwable t) {
                    closeQuietly(s);
                }
            } catch (SocketException e) {
                if (running) lastError = "accept 失败：" + e.getMessage();
                break;
            } catch (Throwable t) {
                if (running) lastError = "accept 失败：" + t.getClass().getSimpleName();
                // 单个连接异常不结束服务；server socket 关闭时下一轮会退出。
            }
        }
    }

    private void handle(Socket socket) {
        if (socket == null) return;
        try {
            Http.Req req = Http.read(socket.getInputStream());
            if (req == null) return;
            if (req.method.length() == 0) {
                logHttp(socket, "400 bad request");
                respondQuietly(socket, 400, "text/plain; charset=utf-8", "bad request");
                return;
            }

            if ("/mcp".equals(req.path)) {
                if (!"POST".equals(req.method)) {
                    logHttp(socket, "405 /mcp " + req.method);
                    respondQuietly(socket, 405, "text/plain; charset=utf-8", "only POST");
                    return;
                }
                if (!authorized(req)) {
                    logHttp(socket, "401 /mcp unauthorized");
                    respondQuietly(socket, 401, "text/plain; charset=utf-8", "unauthorized");
                    return;
                }
                String body = mcp == null ? null : mcp.handle(req.body, registry);
                if (body == null) {
                    Http.respondAccepted(socket.getOutputStream());
                } else {
                    Http.respond(socket.getOutputStream(), 200,
                            "application/json; charset=utf-8", body);
                }
                return;
            }

            if ("/health".equals(req.path)) {
                if (!"GET".equals(req.method)) {
                    logHttp(socket, "405 /health " + req.method);
                    respondQuietly(socket, 405, "text/plain; charset=utf-8", "only GET");
                    return;
                }
                if (!authorized(req)) {
                    logHttp(socket, "401 /health unauthorized");
                    respondQuietly(socket, 401, "text/plain; charset=utf-8", "unauthorized");
                    return;
                }
                // 真机验收发现：这里曾拼常量 PORT，8770 被占用顺延后仍报 8770。
                // 改报本实例实际绑定成功的端口（AgentService 上报到 AgentRuntimeStatus 的同一值）。
                Http.respond(socket.getOutputStream(), 200,
                        "text/plain; charset=utf-8",
                        "ok agent=true port=" + port + " bind=" + boundHost);
                return;
            }

            logHttp(socket, "404 " + req.path);
            respondQuietly(socket, 404, "text/plain; charset=utf-8", "not found");
        } catch (Throwable t) {
            logHttp(socket, "500 internal error");
            respondQuietly(socket, 500, "text/plain; charset=utf-8", "internal error");
        } finally {
            closeQuietly(socket);
        }
    }

    /** 来源只分本机/局域网，不记录原始 IP。 */
    private static String source(Socket s) {
        try {
            return s != null && s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress()
                    ? "loopback" : "lan";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static void logHttp(Socket s, String msg) {
        AgentCallLog.add("W", "http", msg + " source=" + source(s));
    }

    private boolean authorized(Http.Req req) {
        try {
            return token.length() > 0 && Http.bearerMatchesAny(req.headers, token);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void respondQuietly(Socket s, int code, String type, String body) {
        try {
            Http.respond(s.getOutputStream(), code, type, body);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------- 线程池

    private static ThreadFactory factory(final String prefix) {
        final ThreadFactory base = Executors.defaultThreadFactory();
        return new ThreadFactory() {
            private final java.util.concurrent.atomic.AtomicInteger n =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = base.newThread(r);
                t.setName(prefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    private void shutdownAccept() {
        ExecutorService e = acceptExecutor;
        acceptExecutor = null;
        if (e != null) {
            try {
                e.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    private void shutdownRequests() {
        ThreadPoolExecutor e = requestPool;
        requestPool = null;
        if (e != null) {
            try {
                e.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }

}
