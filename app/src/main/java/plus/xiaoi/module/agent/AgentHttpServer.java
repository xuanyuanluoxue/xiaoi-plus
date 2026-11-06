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
 * <p>WP1.2a 只绑 {@code 127.0.0.1:8770}，不做 LAN。路由只有两个：
 * {@code POST /mcp}（Bearer + JSON-RPC）和 {@code GET /health}。所有请求都要求
 * Bearer token；token 来自 App 配置的 {@code mcpToken}，与 8771 共用同一个值。</p>
 *
 * <p>线程模型：一个 accept 线程 + 有界请求池（core 2 / max 4 / queue 16）。
 * 队列满时直接 503，不无限排队；stop() 关闭 server socket 与线程池，幂等。</p>
 */
public final class AgentHttpServer {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 8770;

    private static final int REQUEST_CORE = 2;
    private static final int REQUEST_MAX = 4;
    private static final int REQUEST_QUEUE = 16;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final String token;
    private final AgentMcp mcp;
    private final ActionRegistry registry;

    private volatile boolean running;
    private volatile String lastError = "";
    private ServerSocket server;
    private ExecutorService acceptExecutor;
    private ThreadPoolExecutor requestPool;

    public AgentHttpServer(String token, AgentMcp mcp, ActionRegistry registry) {
        this.token = token == null ? "" : token.trim();
        this.mcp = mcp;
        this.registry = registry;
    }

    public int port() {
        return PORT;
    }

    public boolean isRunning() {
        return running;
    }

    /** 最近一次绑定/accept 失败原因；空串 = 正常。 */
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
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName(HOST), PORT));
            ss.setSoTimeout(0);
            server = ss;
            running = true;
            lastError = "";
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
            lastError = "绑定 " + HOST + ":" + PORT + " 失败："
                    + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : "（" + t.getMessage() + "）");
            closeQuietly(ss);
            server = null;
            running = false;
            shutdownAccept();
            shutdownRequests();
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
                respondQuietly(socket, 400, "text/plain; charset=utf-8", "bad request");
                return;
            }

            if ("/mcp".equals(req.path)) {
                if (!"POST".equals(req.method)) {
                    respondQuietly(socket, 405, "text/plain; charset=utf-8", "only POST");
                    return;
                }
                if (!authorized(req)) {
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
                    respondQuietly(socket, 405, "text/plain; charset=utf-8", "only GET");
                    return;
                }
                if (!authorized(req)) {
                    respondQuietly(socket, 401, "text/plain; charset=utf-8", "unauthorized");
                    return;
                }
                Http.respond(socket.getOutputStream(), 200,
                        "text/plain; charset=utf-8",
                        "ok agent=true port=" + PORT);
                return;
            }

            respondQuietly(socket, 404, "text/plain; charset=utf-8", "not found");
        } catch (Throwable t) {
            respondQuietly(socket, 500, "text/plain; charset=utf-8", "internal error");
        } finally {
            closeQuietly(socket);
        }
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
