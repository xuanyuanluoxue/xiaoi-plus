package plus.xiaoi.module;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 访问本机能力桥的唯一客户端（纯 JVM，可桌面单测）。
 *
 * 以前 Home / Logs / ConfigSync 各自写 HttpURLConnection + 8771~8775 循环，
 * 超时、鉴权头、错误处理三份拷贝。这里统一：
 * - 端口发现只认 /health 响应里的 {@code port=}（8770 等冒名服务会被跳过）
 * - 所有请求带同一个 Bearer 头，失败统一返回 null（不抛异常）
 * - 响应体有上限，避免异常服务把 App 内存打满
 */
public final class BridgeClient {

    public static final int PORT_START = 8771;
    public static final int PORT_END = 8775;

    private static final int CONNECT_MS = 500;
    private static final int READ_MS = 1500;
    private static final int BODY_LIMIT = 256 * 1024;

    public static final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private BridgeClient() {}

    /** 扫 8771~8775，返回第一个自证身份的桥端口；找不到返回 0。 */
    public static int discover(String token) {
        return discover(token, PORT_START, PORT_END);
    }

    /** 指定范围（单测用随机端口，避免和本机真实桥/其它服务冲突）。 */
    static int discover(String token, int start, int end) {
        String t = token == null ? "" : token.trim();
        if (t.length() == 0) return 0;
        for (int port = start; port <= end; port++) {
            Response r = get(port, "/health", t, CONNECT_MS, READ_MS);
            if (r != null && r.code == 200 && r.body.contains(" port=")) return port;
        }
        return 0;
    }

    public static Response get(int port, String path, String token) {
        return get(port, path, token, CONNECT_MS, READ_MS);
    }

    public static Response get(int port, String path, String token,
                               int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestMethod("GET");
            auth(c, token);
            return read(c);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static Response postJson(int port, String path, String token, String body) {
        return postJson(port, path, token, body, CONNECT_MS, READ_MS);
    }

    public static Response postJson(int port, String path, String token, String body,
                                    int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            auth(c, token);
            byte[] data = (body == null ? "" : body).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(data);
            } finally {
                os.close();
            }
            return read(c);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 发一条 MCP tools/call，返回 result.content[0].text；失败返回 null。 */
    public static String mcpCall(int port, String token, String tool, String argsJson) {
        try {
            Map<String, Object> params = Json.obj();
            params.put("name", tool);
            params.put("arguments", Json.read(argsJson == null || argsJson.length() == 0 ? "{}" : argsJson));
            Map<String, Object> req = Json.obj();
            req.put("jsonrpc", "2.0");
            req.put("id", 1L);
            req.put("method", "tools/call");
            req.put("params", params);
            Response r = postJson(port, "/mcp", token, Json.write(req));
            if (r == null || !r.ok()) return null;
            Object v = Json.read(r.body);
            if (!(v instanceof Map)) return null;
            Object result = ((Map<?, ?>) v).get("result");
            if (!(result instanceof Map)) return null;
            Object content = ((Map<?, ?>) result).get("content");
            if (!(content instanceof java.util.List) || ((java.util.List<?>) content).isEmpty()) return null;
            Object first = ((java.util.List<?>) content).get(0);
            if (!(first instanceof Map)) return null;
            Object text = ((Map<?, ?>) first).get("text");
            return text == null ? null : String.valueOf(text);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 运行时配置（xiaoai_config 的 JSON 文本）；桥不可达返回 null。 */
    public static String runtimeConfig(int port, String token) {
        return mcpCall(port, token, "xiaoai_config", "{}");
    }

    public static String logs(int port, String token, int limit) {
        Response r = get(port, "/logs?limit=" + limit, token);
        return r != null && r.ok() ? r.body : null;
    }

    public static String stats(int port, String token, int days) {
        Response r = get(port, "/stats?days=" + days, token);
        return r != null && r.ok() ? r.body : null;
    }

    /** 触发一次超级岛；/island 在桥侧免 token（只影响本机提示）。 */
    public static boolean island(int port) {
        Response r = postJson(port, "/island", "", "{}", 600, 2000);
        return r != null && r.ok();
    }

    // ------------------------------------------------------------- 内部

    private static void auth(HttpURLConnection c, String token) {
        String t = token == null ? "" : token.trim();
        if (t.length() > 0) c.setRequestProperty("Authorization", "Bearer " + t);
    }

    private static Response read(HttpURLConnection c) {
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String body = in == null ? "" : readAll(in);
            return new Response(code, body);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) {
        try {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > BODY_LIMIT) break;
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
