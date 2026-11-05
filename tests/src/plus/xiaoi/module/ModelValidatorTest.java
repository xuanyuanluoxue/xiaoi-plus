package plus.xiaoi.module;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** ModelValidator 测试：本地起 HTTP 桩，验证状态码与响应分类。 */
public final class ModelValidatorTest {

    private ModelValidatorTest() {}

    private static HttpServer srv;
    private static String base;

    public static int run() {
        try {
            srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            srv.createContext("/ok/chat/completions", fixed(200,
                    "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}"));
            srv.createContext("/auth/chat/completions", fixed(401, "{\"error\":\"bad key\"}"));
            srv.createContext("/notfound/chat/completions", fixed(404, "{\"error\":\"no model\"}"));
            srv.createContext("/badmodel/chat/completions", fixed(400, "{\"error\":\"model not found\"}"));
            srv.createContext("/empty/chat/completions", fixed(200, "{}"));
            srv.createContext("/html/chat/completions", fixed(200, "<html>not json</html>"));
            srv.start();
            base = "http://127.0.0.1:" + srv.getAddress().getPort();

            ModelValidator.Result r = ModelValidator.check(base + "/ok", "sk-x", "m");
            eq("ok code", "ok", r.code);
            ok("ok isOk", r.ok);
            ok("ok latency", r.latencyMs >= 0);

            r = ModelValidator.check(base + "/auth", "sk-x", "m");
            eq("auth code", "auth", r.code);
            ok("auth not ok", !r.ok);

            r = ModelValidator.check(base + "/notfound", "sk-x", "m");
            eq("404 model", "model", r.code);

            r = ModelValidator.check(base + "/badmodel", "sk-x", "m");
            eq("400 model hint", "model", r.code);

            r = ModelValidator.check(base + "/empty", "sk-x", "m");
            eq("200 no choices", "empty", r.code);

            r = ModelValidator.check(base + "/html", "sk-x", "m");
            eq("non-json body", "empty", r.code);

            // 不可达端口（连接拒绝）→ endpoint
            r = ModelValidator.check("http://127.0.0.1:1", "sk-x", "m");
            eq("unreachable", "endpoint", r.code);

            // 空参数
            eq("empty baseUrl", "endpoint", ModelValidator.check("", "k", "m").code);
            eq("empty model", "model", ModelValidator.check(base + "/ok", "k", "").code);
        } catch (Throwable t) {
            T.ok("ModelValidatorTest 异常: " + t, false);
        } finally {
            try { if (srv != null) srv.stop(0); } catch (Throwable ignored) {}
        }
        return T.report("ModelValidatorTest");
    }

    private static HttpHandler fixed(final int code, final String body) {
        return new HttpHandler() {
            public void handle(HttpExchange ex) {
                try {
                    byte[] b = body.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().set("Content-Type", "application/json");
                    ex.sendResponseHeaders(code, b.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(b);
                    os.close();
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
