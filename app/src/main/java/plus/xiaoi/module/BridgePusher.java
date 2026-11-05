package plus.xiaoi.module;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 配置推送内核（纯 JVM，无 android 依赖，可桌面单测）。
 *
 * 修 R2「猜端口」：不再只看第一个 HTTP 200，而是先用自己的身份判据认端口，再全端口推送并聚合。
 *
 * <pre>
 *   for port in 8771..8775:
 *       GET /health（Bearer token）→ 响应匹配 ^ok facade=(true|false) port=\d+ 才算「本模块的桥」
 *   for 已知桥: POST /config，收集 {port, code}
 * </pre>
 *
 * 只认 body 里带 {@code port=} 的响应：8770 曾被其它服务占用并返回 {@code ok facade=true}
 * （没有 port= 字段），旧实现会把它误当成桥（契约脚本早已改成同一判据，生产路径这里补齐）。
 *
 * 超时故意取得很短（连接 600ms、读 1.5s）：最坏情况下 5 个端口 × 2 次请求仍在秒级，
 * 不能让一次"小爱没运行"的保存卡住 UI。
 */
public final class BridgePusher {

    /** 端口扫描区间（与 Hook 里的监听区间一致）。 */
    public static final int PORT_FROM = 8771;
    public static final int PORT_TO = 8775;

    static final int CONNECT_TIMEOUT_MS = 600;
    static final int READ_TIMEOUT_MS = 1500;

    /** 桥的身份判据：必须以 "ok facade=…" 开头且带 port=，避免认领 8770 上的冒名服务。 */
    private static final Pattern HEALTH_OK =
            Pattern.compile("^ok facade=(true|false) port=\\d+");

    private BridgePusher() {}

    /** 一次全端口推送的聚合结果（供 UI / 日志使用）。 */
    public static final class Result {
        /** 成功推送到 POST /config 的端口数。 */
        public int pushed;
        /** 认领成功的桥数量（M）。 */
        public int total;
        /** 实际探测过的端口数（含未认领的）。 */
        public int probed;
        /** 成功端口。 */
        public final List<Integer> ports = new ArrayList<Integer>();
        /** 失败原因，形如 "8773: 连接失败"。 */
        public final List<String> errors = new ArrayList<String>();

        public boolean allOk() {
            return total > 0 && pushed == total;
        }

        /** 状态条文案（P3 直接消费；当前只用于日志/提示）。 */
        public String message() {
            if (total == 0) {
                return "已保存到文件；未找到运行中的小爱";
            }
            if (pushed == total) {
                return "已推送到 " + pushed + " 个进程";
            }
            if (pushed == 0) {
                return "已保存到文件；推送失败（共 " + total + " 个进程）";
            }
            return "已推送 " + pushed + "/" + total + "，其余进程将在 800ms 内从 prefs 生效";
        }

        /** 一行日志摘要：端口、结果、原因。 */
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("pushed=").append(pushed).append('/').append(total)
                    .append(" probed=").append(probed);
            if (!ports.isEmpty()) sb.append(" ports=").append(ports);
            if (!errors.isEmpty()) sb.append(" errors=").append(errors);
            return sb.toString();
        }
    }

    /** 扫描默认区间（8771~8775）并推送。 */
    public static Result pushAll(String token, String body) {
        int[] ports = new int[PORT_TO - PORT_FROM + 1];
        for (int i = 0; i < ports.length; i++) ports[i] = PORT_FROM + i;
        return pushAll(token, body, ports);
    }

    /**
     * 扫描指定端口列表并推送（测试用；生产走默认区间）。
     *
     * @param token Bearer token；空 = 不带鉴权头（桥尚未配置 token 时允许首次推送）
     * @param body  POST /config 的 JSON
     */
    public static Result pushAll(String token, String body, int[] ports) {
        Result r = new Result();
        if (ports == null) return r;
        for (int port : ports) {
            r.probed++;
            String why = claim(port, token);
            if (why != null) {
                r.errors.add(port + ": " + why);
                continue;
            }
            r.total++;
            String err = send(port, token, body);
            if (err == null) {
                r.pushed++;
                r.ports.add(port);
            } else {
                r.errors.add(port + ": " + err);
            }
        }
        if (r.total == 0 && r.probed > 0) {
            r.errors.add("未发现本模块的桥（小爱未运行或 MCP 未开启）");
        }
        return r;
    }

    /**
     * 认领端口：GET /health，只有状态 200 且 body 匹配身份判据才算桥。
     * @return null = 认领成功；否则返回失败原因
     */
    private static String claim(int port, String token) {
        HttpURLConnection c = null;
        try {
            c = open(port, "/health", "GET", token, null);
            int code = c.getResponseCode();
            if (code != 200) return "health " + code;
            String text = readBody(c);
            if (!isOurBridge(text)) return "非本模块桥: " + shortText(text);
            return null;
        } catch (Throwable t) {
            return "连接失败: " + brief(t);
        } finally {
            close(c);
        }
    }

    /** POST /config。@return null = 成功；否则失败原因 */
    private static String send(int port, String token, String body) {
        HttpURLConnection c = null;
        try {
            byte[] bytes = body == null ? new byte[0] : body.getBytes("UTF-8");
            c = open(port, "/config", "POST", token, bytes);
            int code = c.getResponseCode();
            if (code != 200) return "config " + code;
            readBody(c); // 读完再断开，避免连接复用异常
            return null;
        } catch (Throwable t) {
            return "推送失败: " + brief(t);
        } finally {
            close(c);
        }
    }

    private static HttpURLConnection open(int port, String path, String method,
                                          String token, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestMethod(method);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "*/*");
        if (token != null && token.length() > 0) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
            } finally {
                os.close();
            }
        }
        return c;
    }

    /** 读响应体（最多 4KB；/health 只有一行）。 */
    private static String readBody(HttpURLConnection c) {
        InputStream in = null;
        try {
            in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() >= 4096) break;
            }
            return new String(out.toByteArray(), "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void close(HttpURLConnection c) {
        try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
    }

    /** 身份判据：与 scripts/contract-test.sh 一致，必须带 port=。 */
    static boolean isOurBridge(String healthBody) {
        if (healthBody == null) return false;
        return HEALTH_OK.matcher(healthBody.trim()).find();
    }

    private static String shortText(String s) {
        if (s == null || s.length() == 0) return "<空响应>";
        String t = s.length() > 40 ? s.substring(0, 40) + "…" : s;
        return t.replace('\n', ' ');
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) return t.getClass().getSimpleName();
        return t.getClass().getSimpleName() + ": " + m;
    }

    // ------------------------------------------------------------- 载荷构造（R3）

    /**
     * 按字段分组构造 POST /config 的 body（无密钥时的推送策略，修 R3）。
     *
     * <ul>
     *   <li>有 apiKey：全量（含 enabled/baseUrl/model/apiKey），文件即事实</li>
     *   <li>无 apiKey：只推 mcp.* 与 marker 相关字段。
     *       绝不带 baseUrl/model/apiKey/enabled——否则会用空 key 把运行时生效配置冲掉</li>
     * </ul>
     *
     * 纯函数，桌面可测。
     */
    public static String payloadFor(AppConfig c) {
        if (c == null) return "{}";
        java.util.Map<String, Object> o = Json.obj();
        boolean hasKey = c.apiKeyOfActive().length() > 0;
        if (hasKey) {
            o.put("enabled", Boolean.valueOf(c.enabled));
            o.put("baseUrl", c.baseUrl());
            o.put("apiKey", c.apiKey());
            o.put("model", c.model());
        }
        o.put("bridge", Boolean.valueOf(c.mcpEnabled));
        o.put("token", c.mcpToken == null ? "" : c.mcpToken);
        o.put("marker", Boolean.valueOf(c.marker));
        o.put("markerTemplate", c.markerTemplate == null ? "" : c.markerTemplate);
        return Json.write(o);
    }
}
