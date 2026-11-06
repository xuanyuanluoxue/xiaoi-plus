package plus.xiaoi.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import plus.xiaoi.module.core.BridgeEndpoint;

/** BridgeClient：端口来自控制面登记（不扫端口）、鉴权头、MCP 取文本、失败降级。 */
public final class BridgeClientTest {

    private BridgeClientTest() {}

    public static int run() throws Exception {
        // ------------------------------------------------------------ 常规 GET + 鉴权
        final AtomicReference<String> auth = new AtomicReference<String>();
        final AtomicReference<String> path = new AtomicReference<String>();
        final AtomicReference<String> mcpAuth = new AtomicReference<String>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/health", new HttpHandler() {
            public void handle(HttpExchange e) {
                auth.set(e.getRequestHeaders().getFirst("Authorization"));
                path.set(e.getRequestURI().toString());
                send(e, 200, "ok facade=true port=" + e.getLocalAddress().getPort());
            }
        });
        srv.createContext("/stats", new HttpHandler() {
            public void handle(HttpExchange e) {
                send(e, 200, "{\"today\":{\"third\":2}}");
            }
        });
        srv.createContext("/mcp", new HttpHandler() {
            public void handle(HttpExchange e) {
                mcpAuth.set(e.getRequestHeaders().getFirst("Authorization"));
                String req = new String(readAll(e), java.nio.charset.StandardCharsets.UTF_8);
                Object v = Json.read(req);
                String name = v instanceof Map
                        ? Json.optString((Map<?, ?>) ((Map<?, ?>) v).get("params"), "name", "") : "";
                if ("xiaoai_config".equals(name)) {
                    send(e, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":\"{\\\"model\\\":\\\"m1\\\"}\"}],\"isError\":false}}");
                } else {
                    send(e, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                            + "{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}}");
                }
            }
        });
        srv.createContext("/island", new HttpHandler() {
            public void handle(HttpExchange e) {
                send(e, 200, "{\"ok\":true}");
            }
        });
        srv.createContext("/fail", new HttpHandler() {
            public void handle(HttpExchange e) {
                send(e, 500, "boom");
            }
        });
        srv.start();
        int port = srv.getAddress().getPort();
        try {
            BridgeClient.Response r = BridgeClient.get(port, "/health", "tok-1");
            T.ok("GET 成功", r != null && r.ok());
            T.ok("带 Bearer 头", "Bearer tok-1".equals(auth.get()));

            // discover 只读控制面登记，不发送任何请求；token 参数被忽略。
            BridgeEndpoint.setPort(port);
            auth.set(null);
            path.set(null);
            T.eq("discover 返回控制面端口", port, BridgeClient.discover("tok-1"));
            T.ok("discover 不发请求", auth.get() == null && path.get() == null);
            T.eq("discover 忽略 token 参数", port, BridgeClient.discover(""));

            final int[] reloads = {0};
            BridgeEndpoint.setReloadAction(new Runnable() {
                public void run() { reloads[0]++; }
            });
            T.ok("requestReload 有回调", BridgeEndpoint.requestReload() && reloads[0] == 1);
            BridgeEndpoint.clearReloadAction();
            T.ok("清理后 requestReload 返回 false", !BridgeEndpoint.requestReload());

            BridgeEndpoint.setPort(0);
            T.eq("无 leader 时端口为 0", 0, BridgeClient.discover("tok-1"));
            BridgeEndpoint.setPort(port);

            String cfg = BridgeClient.runtimeConfig(port, "tok-1");
            T.ok("MCP 取 content[0].text", cfg != null && cfg.contains("\"model\":\"m1\""));
            T.eq("MCP 返回原文", "ok",
                    BridgeClient.mcpCall(port, "tok-1", "xiaoai_status", "{}"));

            String stats = BridgeClient.stats(port, "tok-1", 1);
            T.ok("stats 返回体", stats != null && stats.contains("\"third\":2"));
            T.ok("island 成功", BridgeClient.island(port));

            BridgeClient.Response bad = BridgeClient.get(port, "/fail", "tok-1");
            T.ok("非 2xx 保留状态码", bad != null && bad.code == 500);
            T.eq("非 2xx 读 errorStream", "boom", bad.body);

            // 冒名服务不再被 discover 触及：控制面没登记端口时一律 0。
            BridgeEndpoint.setPort(0);
            T.eq("无 leader 时不猜端口", 0, BridgeClient.discover("tok-1"));

            T.eq("不可达端口返回 null", null, BridgeClient.get(1, "/health", "tok-1"));
            BridgeClient.mcpCall(port, "", "xiaoai_status", "{}");
            T.ok("空 token 不带 Authorization", mcpAuth.get() == null);
        } finally {
            BridgeEndpoint.setPort(0);
            srv.stop(0);
        }

        return T.report("BridgeClientTest");
    }

    private static void send(HttpExchange e, int code, String body) {
        try {
            byte[] b = body.getBytes("UTF-8");
            e.sendResponseHeaders(code, b.length);
            OutputStream out = e.getResponseBody();
            out.write(b);
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readAll(HttpExchange e) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = e.getRequestBody().read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }
}
