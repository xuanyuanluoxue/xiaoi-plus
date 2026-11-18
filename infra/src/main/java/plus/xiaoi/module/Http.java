package plus.xiaoi.module;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import plus.xiaoi.module.core.Contract;

/**
 * HTTP 线协议的纯读写层（无 android 依赖）。
 *
 * <p>请求解析、Bearer 匹配、响应写出集中在这里，方便 JVM 单测；
 * socket 生命周期与鉴权策略（哪个端点要 token、哪些只允许 loopback）仍由 Bridge 决定。
 *
 * <p>0.16.0：
 * <ul>
 *   <li>请求体超限不再「静默当空体」——{@link Req#tooLarge} 为 true，调用方回 413；</li>
 *   <li>所有响应统一带 CORS 头，配合 OPTIONS 预检，浏览器面板/网页 Agent 可直接调用；</li>
 *   <li>上限常量统一来自 {@link Contract}。</li>
 * </ul>
 */
public final class Http {

    private Http() {}

    /** 请求体上限；Content-Length 超限时 {@link Req#tooLarge} 为 true（body 为空）。 */
    public static final int MAX_BODY = Contract.MAX_BODY_BYTES;

    /** 单行（请求行/header）字符上限；超过直接抛 IOException，绝不无限读。 */
    public static final int MAX_LINE = Contract.MAX_HEADER_LINE;

    /** header 总行数上限（含末尾空行之前的字段行）。 */
    public static final int MAX_HEADER_LINES = Contract.MAX_HEADER_LINES;

    /** 浏览器面板需要的最小 CORS 集合；不含 credentials，所以可以用 *。 */
    private static final String CORS =
            "Access-Control-Allow-Origin: *\r\n"
            + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
            + "Access-Control-Allow-Headers: authorization, content-type\r\n"
            + "Access-Control-Max-Age: 600\r\n";

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
        /** 请求体（按 Content-Length 读取）；超限时为空串。 */
        public final String body;
        /** Content-Length 超过 {@link #MAX_BODY}：调用方应回 413 而不是 -32700。 */
        public final boolean tooLarge;

        Req(String method, String target, String path, Map<String, String> headers,
            Map<String, String> query, String body, boolean tooLarge) {
            this.method = method;
            this.target = target;
            this.path = path;
            this.headers = headers;
            this.query = query;
            this.body = body;
            this.tooLarge = tooLarge;
        }
    }

    /**
     * 从输入流读一条请求；连接直接关闭（没有请求行）返回 null。
     * 单行超过 {@link #MAX_LINE} 或 header 超过 {@link #MAX_HEADER_LINES} 行时抛
     * IOException（调用方断开连接），请求行不合法时 method/target 为空串。
     */
    public static Req read(InputStream in) throws IOException {
        String reqLine = readLine(in);
        if (reqLine == null) return null;

        Map<String, String> headers = new HashMap<String, String>();
        int count = 0;
        while (true) {
            String line = readLine(in);
            if (line == null || line.length() == 0) break;
            if (count >= MAX_HEADER_LINES) throw new IOException("too many headers");
            count++;
            int j = line.indexOf(':');
            if (j > 0) {
                headers.put(line.substring(0, j).trim().toLowerCase(Locale.US),
                        line.substring(j + 1).trim());
            }
        }

        String method = "";
        String target = "";
        String[] parts = reqLine.split(" ");
        if (parts.length >= 2) {
            method = parts[0];
            target = parts[1];
        }
        int declared = declaredLength(headers);
        boolean tooLarge = declared > MAX_BODY;
        String body;
        if (tooLarge) {
            // 先把超限的正文读掉（有上限），再交给调用方回 413：
            // 直接 close 会让客户端在写完前收到 RST，看不到 413。
            drain(in, Math.min(declared, MAX_BODY * 16));
            body = "";
        } else {
            body = readBody(in, declared);
        }
        int qi = target.indexOf('?');
        String path = qi < 0 ? target : target.substring(0, qi);
        return new Req(method, target, path, headers, parseQuery(target), body, tooLarge);
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
     * 比较用 MessageDigest.isEqual 保持常数时间。传入多个 token 时任一匹配即通过；
     * 生产路径当前只传一个 token。
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

    /** 写一条 text/plain 响应（字节格式与旧实现一致，额外带 CORS 头）。 */
    public static void respond(OutputStream os, int code, String contentType, String body)
            throws IOException {
        byte[] out = (body == null ? "" : body).getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + out.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + CORS
                + "Connection: close\r\n\r\n";
        os.write(head.getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    /** JSON-RPC 通知的 202 空体响应。 */
    public static void respondAccepted(OutputStream os) throws IOException {
        os.write(("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\n"
                + CORS
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.flush();
    }

    /** CORS 预检：204 无正文，允许浏览器带 Authorization 调用。 */
    public static void respondPreflight(OutputStream os) throws IOException {
        os.write(("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n"
                + CORS
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.flush();
    }

    /** 状态码 → 短语。 */
    public static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 410: return "Gone";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }

    private static int declaredLength(Map<String, String> headers) {
        String cl = headers == null ? null : headers.get("content-length");
        if (cl == null) return 0;
        try {
            return Integer.parseInt(cl.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 丢弃最多 len 字节（超限请求体用，保证客户端能收到 413 而不是 RST）。 */
    static void drain(InputStream in, int len) {
        try {
            byte[] buf = new byte[4096];
            int left = len;
            while (left > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, left));
                if (n < 0) break;
                left -= n;
            }
        } catch (Throwable ignored) {
        }
    }

    static String readBody(InputStream in, int len) {
        try {
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
            if (c != '\r') {
                sb.append((char) c);
                if (sb.length() > MAX_LINE) throw new IOException("line too long");
            }
        }
        return any ? sb.toString() : null;
    }
}
