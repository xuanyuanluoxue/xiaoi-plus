package plus.xiaoi.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** BridgeClient：端口发现、鉴权头、MCP 取文本、失败降级。 */
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
            T.ok("端口发现命中", BridgeClient.discover("tok-1", port, port) == port);
            T.ok("无 token 不发现", BridgeClient.discover("", port, port) == 0);

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

            // 冒名服务：/health 不含 port= 时不能被认领
            HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fake.createContext("/health", new HttpHandler() {
                public void handle(HttpExchange e) {
                    send(e, 200, "ok facade=true");
                }
            });
            fake.start();
            int fakePort = fake.getAddress().getPort();
            try {
                T.eq("无 port= 不认领", 0, BridgeClient.discover("tok-1", fakePort, fakePort));
            } finally {
                fake.stop(0);
            }

            T.eq("不可达端口返回 null", null, BridgeClient.get(1, "/health", "tok-1"));
            BridgeClient.mcpCall(port, "", "xiaoai_status", "{}");
            T.ok("空 token 不带 Authorization", mcpAuth.get() == null);
        } finally {
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
