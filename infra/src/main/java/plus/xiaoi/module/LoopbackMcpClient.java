package plus.xiaoi.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import plus.xiaoi.module.core.Contract;

/**
 * 本机回环 MCP 客户端（纯 JVM，无 android 依赖）。
 *
 * <p>0.16.0 单端口统一用它把「对方进程拥有的工具」转发过去：
 * 8771（小爱进程）↔ 8772（App 进程）。两端都以 {@code /health} 自证身份，
 * 因此端口被占用顺延后仍然能被发现。
 *
 * <p>发现策略：按候选端口顺序探测 {@code GET /health}，响应用
 * {@code healthNeedle} 匹配（例如 {@code "ok agent=true"}）；命中后缓存 10 秒，
 * 未命中缓存 2 秒，避免每次调用都扫一遍端口。调用失败会立刻清缓存，下一次重新探测。
 *
 * <p>线程安全：{@link #port()} / {@link #available()} 只读 volatile 字段；
 * 并发探测允许重复（幂等、开销小）。
 */
public final class LoopbackMcpClient {

    private static final String HOST = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MS = 1500;
    /** 响应体上限（审计 200 条 + 工具表远小于此值）。 */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final long FOUND_TTL_MS = 10_000L;
    private static final long MISS_TTL_MS = 2_000L;

    private final int[] candidates;
    private final String healthNeedle;
    private volatile String token;
    private volatile int port;
    private volatile long probedAt;
    private volatile String lastError = "";

    /**
     * @param token        与对端共用的 Bearer（空 = 不带头，对端需开免鉴权）
     * @param healthNeedle 只有 {@code /health} 正文包含它时才认这个端口
     * @param candidates   候选端口（按顺序探测）
     */
    public LoopbackMcpClient(String token, String healthNeedle, int... candidates) {
        this.token = token == null ? "" : token.trim();
        this.healthNeedle = healthNeedle == null ? "" : healthNeedle;
        this.candidates = candidates == null || candidates.length == 0
                ? new int[]{Contract.BRIDGE_PORT} : candidates.clone();
    }

    public void setToken(String value) {
        String next = value == null ? "" : value.trim();
        if (!next.equals(token)) {
            token = next;
            // token 变了，之前的「可用」判定可能不再成立。
            port = 0;
            probedAt = 0L;
        }
    }

    /** 已发现的端口；0 = 暂不可用。不会触发探测。 */
    public int port() {
        return port;
    }

    /** 对端当前是否可达（会触发一次探测，命中缓存时不会重复扫端口）。 */
    public boolean available() {
        return ensurePort() > 0;
    }

    public String lastError() {
        return lastError;
    }

    /** 对端工具清单（MCP {@code result.tools}）；不可用返回 null。 */
    public Map<String, Object> tools() {
        // own=true：只要对端自己的工具，避免两端合并时互相递归。
        Map<String, Object> params = Json.obj();
        params.put("own", Boolean.TRUE);
        Object v = callResult("tools/list", params).get("tools");
        if (!(v instanceof java.util.List)) return null;
        Map<String, Object> r = Json.obj();
        r.put("tools", v);
        return r;
    }

    /**
     * 转发一次 tools/call。
     *
     * @return MCP {@code result}（含 content / isError）；失败返回 null
     */
    public Map<String, Object> callTool(String name, Map<?, ?> arguments) {
        if (name == null || name.length() == 0) return null;
        Map<String, Object> params = Json.obj();
        params.put("name", name);
        params.put("arguments", arguments == null ? Json.obj() : arguments);
        Map<String, Object> result = callResult("tools/call", params);
        if (result.isEmpty()) return null;
        return result;
    }

    // ------------------------------------------------------------- 内部

    private Map<String, Object> callResult(String method, Map<String, Object> params) {
        Map<String, Object> req = Json.obj();
        req.put("jsonrpc", "2.0");
        req.put("id", "proxy");
        req.put("method", method);
        if (params != null) req.put("params", params);
        int p = ensurePort();
        if (p <= 0) return Json.obj();
        long timeout = timeoutFor(params);
        String body = post(p, Json.write(req), (int) timeout);
        if (body == null) return Json.obj();
        try {
            Object parsed = Json.read(body);
            if (!(parsed instanceof Map)) return Json.obj();
            Object result = ((Map<?, ?>) parsed).get("result");
            if (!(result instanceof Map)) return Json.obj();
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) result;
            return out;
        } catch (Throwable t) {
            lastError = "parse response failed";
            return Json.obj();
        }
    }

    /** xiaoai_ask 可能跑满 timeoutMs，转发超时要跟着放宽。 */
    private static long timeoutFor(Map<String, Object> params) {
        long t = Contract.PROXY_TIMEOUT_MS;
        if (params == null) return t;
        Object args = params.get("arguments");
        if (!(args instanceof Map)) return t;
        long ask = Json.optLong((Map<?, ?>) args, "timeoutMs", 0L);
        if (ask > 0) {
            long want = ask + Contract.PROXY_TIMEOUT_SLACK_MS;
            if (want > t) t = want;
        }
        long max = Contract.ASK_TIMEOUT_MAX_MS + Contract.PROXY_TIMEOUT_SLACK_MS;
        return Math.min(t, max);
    }

    private int ensurePort() {
        long now = System.currentTimeMillis();
        int known = port;
        if (known > 0 && now - probedAt < FOUND_TTL_MS) return known;
        if (known == 0 && probedAt > 0 && now - probedAt < MISS_TTL_MS) return 0;
        probedAt = now;
        for (int candidate : candidates) {
            if (healthy(candidate)) {
                port = candidate;
                return candidate;
            }
        }
        port = 0;
        return 0;
    }

    private boolean healthy(int candidate) {
        try {
            Http.Req r = exchange(candidate, "GET /health HTTP/1.1\r\n", null, 1500);
            if (r == null) return false;
            if (!"200".equals(statusOf(r))) return false;
            return r.body != null && r.body.contains(healthNeedle);
        } catch (Throwable t) {
            return false;
        }
    }

    private String post(int candidate, String body, int readTimeoutMs) {
        try {
            byte[] raw = body.getBytes("UTF-8");
            Http.Req r = exchange(candidate, null, raw, readTimeoutMs);
            if (r == null) {
                lastError = "connect failed";
                // 对端没了：立刻清缓存，下一次重新探测（否则会一直打一个死端口）。
                reset();
                return null;
            }
            if (!"200".equals(statusOf(r))) {
                lastError = "http " + statusOf(r);
                return null;
            }
            return r.body;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName();
            reset();
            return null;
        }
    }

    /**
     * 发一条请求并解析响应。
     *
     * @param requestLine GET 时给完整请求行（含结尾 CRLF）；POST 时传 null
     * @param body        POST 正文；GET 传 null
     */
    private Http.Req exchange(int candidate, String requestLine, byte[] body, int readTimeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getByName(HOST), candidate),
                    CONNECT_TIMEOUT_MS);
            s.setSoTimeout(Math.max(1000, readTimeoutMs));
            StringBuilder head = new StringBuilder();
            if (requestLine != null) {
                head.append(requestLine);
            } else {
                head.append("POST /mcp HTTP/1.1\r\n");
                head.append("Content-Type: application/json\r\n");
                head.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
            }
            head.append("Host: ").append(HOST).append(':').append(candidate).append("\r\n");
            if (token.length() > 0) {
                head.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            OutputStream os = s.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            if (body != null) os.write(body);
            os.flush();
            return readResponse(s.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 极简响应解析：状态行 + header + Content-Length 正文（上限 1 MiB）。 */
    private static Http.Req readResponse(InputStream in) throws IOException {
        String status = Http.readLine(in);
        if (status == null) return null;
        Map<String, String> headers = new java.util.HashMap<String, String>();
        int lines = 0;
        while (true) {
            String line = Http.readLine(in);
            if (line == null || line.length() == 0) break;
            if (++lines > Http.MAX_HEADER_LINES) throw new IOException("too many headers");
            int j = line.indexOf(':');
            if (j > 0) {
                headers.put(line.substring(0, j).trim().toLowerCase(java.util.Locale.US),
                        line.substring(j + 1).trim());
            }
        }
        int len = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                len = Integer.parseInt(cl.trim());
            } catch (Throwable ignored) {
            }
        }
        if (len < 0) len = 0;
        if (len > MAX_RESPONSE_BYTES) len = MAX_RESPONSE_BYTES;
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        String body = new String(buf, 0, off, "UTF-8");
        String[] parts = status.split(" ");
        String code = parts.length >= 2 ? parts[1] : "";
        return new Http.Req("GET", code, "/", headers, new java.util.HashMap<String, String>(),
                body, false);
    }

    private static String statusOf(Http.Req r) {
        return r == null ? "" : r.target;
    }

    private void reset() {
        port = 0;
        probedAt = 0L;
    }
}
