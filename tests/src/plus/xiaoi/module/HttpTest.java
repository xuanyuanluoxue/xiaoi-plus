package plus.xiaoi.module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** HTTP 线协议测试：请求解析、Bearer 匹配、响应字节。 */
public final class HttpTest {

    private HttpTest() {}

    public static int run() throws Exception {
        // ------------------------------------------------------------ 请求解析
        String req =
                "GET /cli?cmd=%7B%22id%22%3A%221%22%7D&x=%E4%B8%AD HTTP/1.1\r\n"
                + "Host: 127.0.0.1:8771\r\n"
                + "Authorization: Bearer abc\r\n"
                + "\r\n";
        Http.Req r = Http.read(stream(req));
        eq("method", "GET", r.method);
        eq("path", "/cli", r.path);
        eq("query decoded cmd", "{\"id\":\"1\"}", r.query.get("cmd"));
        eq("query decoded utf8", "中", r.query.get("x"));
        eq("header lowercased", "Bearer abc", r.headers.get("authorization"));
        eq("no body", "", r.body);

        String post =
                "POST /mcp HTTP/1.1\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: 10\r\n"
                + "\r\n"
                + "{\"a\":true}";
        r = Http.read(stream(post));
        eq("post method", "POST", r.method);
        eq("post body", "{\"a\":true}", r.body);

        r = Http.read(stream("POST /mcp HTTP/1.1\r\n\r\n"));
        eq("missing content-length", "", r.body);

        r = Http.read(stream("POST /mcp HTTP/1.1\r\nContent-Length: 999999\r\n\r\n"));
        eq("oversized body", "", r.body);

        r = Http.read(stream("GET\r\n"));
        eq("malformed request line method", "", r.method);
        eq("malformed request line target", "", r.target);

        eq("read on closed stream", (Object) null, Http.read(stream("")));

        // ------------------------------------------------------------ query 边角
        Map<String, String> q = Http.parseQuery("/logs?limit=5&since=100");
        eq("query limit", "5", q.get("limit"));
        eq("query since", "100", q.get("since"));
        q = Http.parseQuery("/logs?a=1&flag&b=%2B");
        eq("query plus decode", "+", q.get("b"));
        ok("query bare key ignored", !q.containsKey("flag"));
        ok("query no marker", Http.parseQuery("/health").isEmpty());

        // ------------------------------------------------------------ Bearer 匹配
        ok("bearer match", Http.bearerMatchesAny(hdr("authorization", "Bearer abc"), "abc"));
        ok("bearer any-of-two", Http.bearerMatchesAny(hdr("authorization", "Bearer abc"), "xyz", "abc"));
        ok("bearer wrong token", !Http.bearerMatchesAny(hdr("authorization", "Bearer abc"), "xyz"));
        ok("bearer lowercase scheme", !Http.bearerMatchesAny(hdr("authorization", "bearer abc"), "abc"));
        ok("bearer double space", !Http.bearerMatchesAny(hdr("authorization", "Bearer  abc"), "abc"));
        ok("bearer missing header", !Http.bearerMatchesAny(new HashMap<String, String>(), "abc"));
        ok("bearer null headers", !Http.bearerMatchesAny(null, "abc"));
        ok("bearer empty token skipped", !Http.bearerMatchesAny(hdr("authorization", "Bearer abc"), "", "xyz"));
        ok("bearer no tokens", !Http.bearerMatchesAny(hdr("authorization", "Bearer abc")));

        // ------------------------------------------------------------ 响应字节（黄金串）
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Http.respond(os, 401, "text/plain; charset=utf-8", "unauthorized");
        eq("respond 401 bytes",
                "HTTP/1.1 401 Unauthorized\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: 12\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "unauthorized",
                new String(os.toByteArray(), StandardCharsets.UTF_8));

        os = new ByteArrayOutputStream();
        Http.respond(os, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        eq("respond 200 json bytes",
                "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: 11\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "{\"ok\":true}",
                new String(os.toByteArray(), StandardCharsets.UTF_8));

        os = new ByteArrayOutputStream();
        Http.respondAccepted(os);
        eq("respond 202 bytes",
                "HTTP/1.1 202 Accepted\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n"
                + "\r\n",
                new String(os.toByteArray(), StandardCharsets.UTF_8));

        eq("reason 405", "Method Not Allowed", Http.reason(405));
        eq("reason 403 falls back", "Internal Server Error", Http.reason(403));
        eq("reason 500", "Internal Server Error", Http.reason(500));

        return T.report("HttpTest");
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> hdr(String k, String v) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(k, v);
        return m;
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
