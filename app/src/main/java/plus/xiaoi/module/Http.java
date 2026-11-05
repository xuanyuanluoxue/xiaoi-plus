package plus.xiaoi.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP 线协议的纯读写层（无 android 依赖）。
 *
 * 请求解析、Bearer 匹配、响应写出集中在这里，方便 JVM 单测；
 * socket 生命周期与鉴权策略（哪个端点要 token、哪些只允许 loopback）仍由 Bridge 决定。
 * 输出字节格式与 v0.7 及之前手写的版本完全一致，由契约基线保证。
 */
public final class Http {

    private Http() {}

    /** 请求体上限；Content-Length 缺失或超限时按空体处理。 */
    public static final int MAX_BODY = 64 * 1024;

    /** 一条已解析的 HTTP 请求。 */
    public static final class Req {
        /** 请求动词（原样大写）；请求行不合法时为空串，由调用方按 400 处理。 */
        public final String method;
        /** 原始 target（path?query）。 */
        public final String target;
        /** 去掉 query 的路径。 */
        public final String path;
        /** 头域，key 已小写。 */
        public final Map<String, String> headers;
        /** query 参数，key/value 已 URL 解码。 */
        public final Map<String, String> query;
        /** 请求体（按 Content-Length 读取）。 */
        public final String body;

        Req(String method, String target, String path, Map<String, String> headers,
            Map<String, String> query, String body) {
            this.method = method;
            this.target = target;
            this.path = path;
            this.headers = headers;
            this.query = query;
            this.body = body;
        }
    }

    /**
     * 从输入流读一条请求；连接直接关闭（没有请求行）返回 null。
     * 头部行数预算 128，与旧实现一致；请求行不合法时 method/target 为空串。
     */
    public static Req read(InputStream in) throws IOException {
        String reqLine = readLine(in);
        if (reqLine == null) return null;

        Map<String, String> headers = new HashMap<String, String>();
        String line;
        int budget = 128;
        while (budget-- > 0 && (line = readLine(in)) != null && line.length() > 0) {
            int i = line.indexOf(':');
            if (i > 0) {
                headers.put(line.substring(0, i).trim().toLowerCase(Locale.US),
                        line.substring(i + 1).trim());
            }
        }

        String method = "";
        String target = "";
        String[] parts = reqLine.split(" ");
        if (parts.length >= 2) {
            method = parts[0];
            target = parts[1];
        }
        String body = readBody(in, headers);
        int qi = target.indexOf('?');
        String path = qi < 0 ? target : target.substring(0, qi);
        return new Req(method, target, path, headers, parseQuery(target), body);
    }

    /** 解析 target 的 query 参数（URL 解码；畸形键值忽略）。 */
    public static Map<String, String> parseQuery(String target) {
        Map<String, String> m = new HashMap<String, String>();
        int i = target == null ? -1 : target.indexOf('?');
        if (i < 0) return m;
        for (String kv : target.substring(i + 1).split("&")) {
            int j = kv.indexOf('=');
            if (j <= 0) continue;
            try {
                m.put(URLDecoder.decode(kv.substring(0, j), "UTF-8"),
                        URLDecoder.decode(kv.substring(j + 1), "UTF-8"));
            } catch (Throwable ignored) {
            }
        }
        return m;
    }

    /**
     * Bearer 匹配：Authorization 头须精确等于 "Bearer &lt;token&gt;"（大小写敏感、单空格）。
     * 比较用 MessageDigest.isEqual 保持常数时间。传入多个 token 时任一匹配即通过——
     * 这是 v0.8 修复 token 轮换死锁的鉴权放宽点，仅在 /config 上使用多 token。
     */
    public static boolean bearerMatchesAny(Map<String, String> headers, String... tokens) {
        try {
            String got = headers == null ? null : headers.get("authorization");
            if (got == null) return false;
            byte[] a = got.getBytes("UTF-8");
            for (String t : tokens) {
                if (t == null || t.length() == 0) continue;
                byte[] b = ("Bearer " + t).getBytes("UTF-8");
                if (MessageDigest.isEqual(a, b)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写一条 text/plain 响应（字节格式与旧实现一致）。 */
    public static void respond(OutputStream os, int code, String contentType, String body)
            throws IOException {
        byte[] out = (body == null ? "" : body).getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + out.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        os.write(head.getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    /** JSON-RPC 通知的 202 空体响应。 */
    public static void respondAccepted(OutputStream os) throws IOException {
        os.write(("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.flush();
    }

    /** 状态码 → 短语。注意 403 没有专属短语（沿用旧实现，落到 Internal Server Error）。 */
    public static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }

    static String readBody(InputStream in, Map<String, String> headers) {
        try {
            int len = 0;
            String cl = headers.get("content-length");
            if (cl != null) len = Integer.parseInt(cl.trim());
            if (len <= 0 || len > MAX_BODY) return "";
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return any ? sb.toString() : null;
    }
}
