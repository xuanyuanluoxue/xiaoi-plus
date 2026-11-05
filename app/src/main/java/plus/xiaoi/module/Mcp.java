package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

/**
 * MCP / JSON-RPC 2.0 协议层（纯 JVM，无 android 依赖）。
 *
 * 职责：报文解析与分发（initialize / ping / tools/list / tools/call）、
 * 工具表 schema、错误码与响应装配。所有需要碰 Android 的能力
 * （CLI 反射、Config、Island、Logs、prefs 写入）通过 {@link Host} 注入，
 * 桌面单测用 FakeHost 即可覆盖全部协议行为。
 *
 * 行为与 v0.7 的 Bridge 内联实现逐条对齐：错误码、错误文案、工具顺序、
 * 通知（无 id）返回 null 由调用方回 202，都保持不变，由契约基线保证。
 */
public final class Mcp {

    private Mcp() {}

    /** MCP Streamable HTTP 最小子集的协议版本。 */
    public static final String PROTOCOL = "2025-03-26";
    public static final String SERVER = "xiaoi-plus";

    // ------------------------------------------------------------- 后端接口

    /** 工具执行后端；由 Bridge 提供 Android 侧实现，测试用 FakeHost。 */
    public interface Host {
        boolean facadeReady();

        boolean cliReady();

        int port();

        String versionName();

        /** 执行一条小爱 CLI JSON；服务未就绪返回 null。 */
        String callCli(String cmd);

        /** 当前生效配置快照。 */
        Snapshot config();

        /** 写入 prefs 并热更新；失败返回 false（工具层转成错误文案）。 */
        boolean writeConfig(Snapshot next);

        boolean showIsland(String text, boolean force);

        String logs(int limit, long since);

        /** 调用量统计 JSON（结构见 {@link UsageCounter#stats}）。 */
        String stats(int days);
    }

    /** 配置的只读快照（Config 的纯 JVM 投影）。 */
    public static final class Snapshot {
        public final String baseUrl;
        public final String apiKey;
        public final String model;
        public final boolean marker;
        /** 输出前缀模板；空 = 不注入。支持 {model} 占位符。 */
        public final String markerTemplate;
        /** 显式启用位：false = 暂停重定向（保留配置）。 */
        public final boolean enabled;
        public final boolean bridge;
        public final String token;

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token) {
            this(baseUrl, apiKey, model, marker, bridge, token, "", true);
        }

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token, String markerTemplate) {
            this(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, true);
        }

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token, String markerTemplate, boolean enabled) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null ? "" : model;
            this.marker = marker;
            this.markerTemplate = markerTemplate == null ? "" : markerTemplate;
            this.enabled = enabled;
            this.bridge = bridge;
            this.token = token == null ? "" : token;
        }
    }

    // ------------------------------------------------------------- 入口

    /**
     * 处理一条 POST /mcp 报文。
     *
     * @return 响应体；通知（无 id）返回 null，调用方应回 202 空体
     */
    public static String handle(String body, Host host) {
        try {
            if (body == null || body.length() == 0) {
                return rpcError(null, -32700, "empty body");
            }
            Object v;
            try {
                v = Json.read(body);
            } catch (Throwable t) {
                return rpcError(null, -32700, "parse error");
            }
            if (!(v instanceof Map)) return rpcError(null, -32700, "parse error");
            Map<?, ?> req = (Map<?, ?>) v;

            Object id = req.get("id");
            String m = Json.optString(req, "method", "");
            if (m.length() == 0) {
                return rpcError(id, -32600, "missing method");
            }
            // 通知没有 id：规范允许直接 202 空体。
            if (id == null) return null;

            if ("initialize".equals(m)) {
                return rpcResult(id, initializeResult(host.versionName()));
            }
            if ("ping".equals(m)) {
                return rpcResult(id, Json.obj());
            }
            if ("tools/list".equals(m)) {
                return rpcResult(id, toolsList());
            }
            if ("tools/call".equals(m)) {
                Object p = req.get("params");
                Map<?, ?> params = p instanceof Map ? (Map<?, ?>) p : null;
                return rpcResult(id, toolsCall(params, host));
            }
            return rpcError(id, -32601, "method not found: " + m);
        } catch (Throwable t) {
            return rpcError(null, -32603, "internal error");
        }
    }

    // ------------------------------------------------------------- 响应装配

    public static String rpcResult(Object id, Object result) {
        try {
            Map<String, Object> o = Json.obj();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("result", result);
            return Json.write(o);
        } catch (Throwable t) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"encode failed\"}}";
        }
    }

    public static String rpcError(Object id, int code, String message) {
        try {
            Map<String, Object> err = Json.obj();
            err.put("code", (long) code);
            err.put("message", message);
            Map<String, Object> o = Json.obj();
            o.put("jsonrpc", "2.0");
            o.put("id", id);
            o.put("error", err);
            return Json.write(o);
        } catch (Throwable t) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"encode failed\"}}";
        }
    }

    // ------------------------------------------------------------- 方法实现

    public static Map<String, Object> initializeResult(String versionName) {
        Map<String, Object> toolsCap = Json.obj();
        toolsCap.put("listChanged", Boolean.FALSE);
        Map<String, Object> caps = Json.obj();
        caps.put("tools", toolsCap);

        Map<String, Object> info = Json.obj();
        info.put("name", SERVER);
        info.put("version", versionName);

        Map<String, Object> r = Json.obj();
        r.put("protocolVersion", PROTOCOL);
        r.put("capabilities", caps);
        r.put("serverInfo", info);
        r.put("instructions", "小爱同学（手机端）能力桥：xiaoai_submit 让小爱执行一句话任务，"
                + "xiaoai_status 探活，xiaoai_cli 走原始 CLI，xiaoai_stats 看三方/官方模型调用量。"
                + "所有调用都发生在手机本机回环地址上。");
        return r;
    }

    /** 工具表（顺序：status / submit / cli / config / island / switch_model / logs / stats）。 */
    public static Map<String, Object> toolsList() {
        List<Object> tools = Json.arr();

        Map<String, Object> status = Json.obj();
        status.put("name", "xiaoai_status");
        status.put("description", "探活：返回小爱进程内 facade/CLI 是否就绪，以及桥的监听端口。");
        status.put("inputSchema", emptySchema());

        Map<String, Object> submit = Json.obj();
        submit.put("name", "xiaoai_submit");
        submit.put("description", "让小爱（手机端语音助手）执行一句话任务，返回它的回复文本。"
                + "不传 sessionId 时自动开新会话。");
        Map<String, Object> submitProps = Json.obj();
        submitProps.put("text", prop("string", "要说给小爱的话"));
        submitProps.put("sessionId", prop("string", "可选；复用已有会话以获得上下文"));
        Map<String, Object> submitSchema = Json.obj();
        submitSchema.put("type", "object");
        submitSchema.put("properties", submitProps);
        submitSchema.put("required", list("text"));
        submitSchema.put("additionalProperties", Boolean.FALSE);
        submit.put("inputSchema", submitSchema);

        Map<String, Object> cli = Json.obj();
        cli.put("name", "xiaoai_cli");
        cli.put("description", "原始 CLI：传入 {\"id\",\"method\",\"params\"} 的 JSON 字符串，"
                + "直接走小爱 CliDispatcher 并返回原始响应。");
        Map<String, Object> cliProps = Json.obj();
        cliProps.put("cmd", prop("string", "JSON 命令串，例如 {\"id\":\"1\",\"method\":\"auth.status\"}"));
        Map<String, Object> cliSchema = Json.obj();
        cliSchema.put("type", "object");
        cliSchema.put("properties", cliProps);
        cliSchema.put("required", list("cmd"));
        cliSchema.put("additionalProperties", Boolean.FALSE);
        cli.put("inputSchema", cliSchema);

        Map<String, Object> cfg = Json.obj();
        cfg.put("name", "xiaoai_config");
        cfg.put("description", "读取当前生效配置（model / baseUrl / marker / bridge / token 是否已设置）。");
        cfg.put("inputSchema", emptySchema());

        Map<String, Object> isl = Json.obj();
        isl.put("name", "xiaoai_island");
        isl.put("description", "手动触发一次超级岛提示（单次，6 秒后自动消失），用于验证岛通路。");
        Map<String, Object> islProps = Json.obj();
        islProps.put("text", prop("string", "可选；提示文案，默认显示当前模型"));
        islProps.put("force", prop("boolean", "是否忽略单次限制（调试用，默认 true）"));
        Map<String, Object> islSchema = Json.obj();
        islSchema.put("type", "object");
        islSchema.put("properties", islProps);
        islSchema.put("additionalProperties", Boolean.FALSE);
        isl.put("inputSchema", islSchema);

        Map<String, Object> sw = Json.obj();
        sw.put("name", "xiaoai_switch_model");
        sw.put("description", "热切换模型：只传 model 即可复用现有端点与 key；也可一并改 baseUrl/apiKey。"
                + "写入 prefs 后全进程 1 秒内生效，无需重启小爱，也无需打开模块设置页。");
        Map<String, Object> swProps = Json.obj();
        swProps.put("model", prop("string", "上游 model id，例如 deepseek-chat"));
        swProps.put("baseUrl", prop("string", "可选；OpenAI 兼容 base URL"));
        swProps.put("apiKey", prop("string", "可选；不传则保留现有 key"));
        swProps.put("marker", prop("boolean", "可选；回复开头是否带 [model=...]"));
        swProps.put("bridge", prop("boolean", "可选；是否保留能力桥"));
        Map<String, Object> swSchema = Json.obj();
        swSchema.put("type", "object");
        swSchema.put("properties", swProps);
        swSchema.put("required", list("model"));
        swSchema.put("additionalProperties", Boolean.FALSE);
        sw.put("inputSchema", swSchema);

        Map<String, Object> lg = Json.obj();
        lg.put("name", "xiaoai_logs");
        lg.put("description", "读取模块运行日志（最近 300 条环形缓冲，落盘在 files/xiaoi_plus.log）。");
        Map<String, Object> lgProps = Json.obj();
        lgProps.put("limit", prop("integer", "返回尾部多少条，默认 80"));
        lgProps.put("since", prop("integer", "只返回 seq 大于该值的日志"));
        Map<String, Object> lgSchema = Json.obj();
        lgSchema.put("type", "object");
        lgSchema.put("properties", lgProps);
        lgSchema.put("additionalProperties", Boolean.FALSE);
        lg.put("inputSchema", lgSchema);

        Map<String, Object> st = Json.obj();
        st.put("name", "xiaoai_stats");
        st.put("description", "调用量统计：按天返回三方 / 官方模型的调用次数。"
                + "默认只算主对话（请求含 tools 或消息数 ≥6），记忆抽取等子调用单独记在 sub 里。");
        Map<String, Object> stProps = Json.obj();
        stProps.put("days", prop("integer", "统计最近多少天，默认 7，范围 1~90"));
        Map<String, Object> stSchema = Json.obj();
        stSchema.put("type", "object");
        stSchema.put("properties", stProps);
        stSchema.put("additionalProperties", Boolean.FALSE);
        st.put("inputSchema", stSchema);

        tools.add(status);
        tools.add(submit);
        tools.add(cli);
        tools.add(cfg);
        tools.add(isl);
        tools.add(sw);
        tools.add(lg);
        tools.add(st);

        Map<String, Object> r = Json.obj();
        r.put("tools", tools);
        return r;
    }

    /** 工具名列表（去掉 xiaoai_ 前缀），供状态速览等紧凑展示。 */
    public static String toolNames(String sep) {
        StringBuilder sb = new StringBuilder();
        Object v = toolsList().get("tools");
        if (!(v instanceof List)) return "";
        for (Object item : (List<?>) v) {
            if (!(item instanceof Map)) continue;
            String name = Json.optString((Map<?, ?>) item, "name", "");
            if (name.length() == 0) continue;
            if (name.startsWith("xiaoai_")) name = name.substring(7);
            if (sb.length() > 0) sb.append(sep);
            sb.append(name);
        }
        return sb.toString();
    }

    /** 工具清单的纯文本（Skill/复制用），从 toolsList 单一来源生成，避免文档漏工具。 */
    public static String toolListText() {
        StringBuilder sb = new StringBuilder();
        Object v = toolsList().get("tools");
        if (!(v instanceof List)) return "";
        for (Object item : (List<?>) v) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) item;
            String name = Json.optString(m, "name", "");
            String desc = Json.optString(m, "description", "");
            if (name.length() == 0) continue;
            sb.append("- ").append(name);
            if (desc.length() > 0) sb.append("：").append(desc);
            sb.append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> s = Json.obj();
        s.put("type", "object");
        s.put("properties", Json.obj());
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = Json.obj();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static List<Object> list(String... items) {
        List<Object> l = Json.arr();
        for (String s : items) l.add(s);
        return l;
    }

    // ------------------------------------------------------------- 工具执行

    static Map<String, Object> toolsCall(Map<?, ?> params, Host host) {
        String name = params == null ? "" : Json.optString(params, "name", "");
        Map<?, ?> args = null;
        if (params != null) {
            Object a = params.get("arguments");
            if (a instanceof Map) args = (Map<?, ?>) a;
        }
        try {
            if ("xiaoai_status".equals(name)) {
                String t = "facade=" + host.facadeReady()
                        + " cli=" + host.cliReady()
                        + " port=" + host.port()
                        + " version=" + host.versionName();
                return toolText(t, false);
            }
            if ("xiaoai_submit".equals(name)) {
                String text = Json.optString(args, "text", "").trim();
                if (text.length() == 0) return toolText("missing argument: text", true);
                String sid = Json.optString(args, "sessionId", "").trim();
                if (sid.length() == 0) {
                    String open = host.callCli(cliJson("session.open", Json.obj()));
                    sid = extractSessionId(open);
                    if (sid == null) return toolText("session.open failed: " + brief(open), true);
                }
                Map<String, Object> p = Json.obj();
                p.put("sessionId", sid);
                p.put("text", text);
                String out = host.callCli(cliJson("agent.submit", p));
                if (out == null) return toolText("ERR cli service not ready", true);
                return toolText(out, false);
            }
            if ("xiaoai_cli".equals(name)) {
                String cmd = Json.optString(args, "cmd", "").trim();
                if (cmd.length() == 0) return toolText("missing argument: cmd", true);
                String out = host.callCli(cmd);
                if (out == null) return toolText("ERR cli service not ready", true);
                return toolText(out, false);
            }
            if ("xiaoai_config".equals(name)) {
                Snapshot c = host.config();
                Map<String, Object> r = Json.obj();
                r.put("model", c.model);
                r.put("baseUrl", c.baseUrl);
                r.put("enabled", c.enabled);
                r.put("marker", c.marker);
                r.put("markerTemplate", c.markerTemplate);
                r.put("bridge", c.bridge);
                r.put("apiKeySet", c.apiKey.length() > 0);
                r.put("tokenSet", c.token.length() > 0);
                r.put("port", (long) host.port());
                r.put("version", host.versionName());
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_switch_model".equals(name)) {
                Snapshot cur = host.config();
                String model = Json.optString(args, "model", "").trim();
                if (model.length() == 0) return toolText("missing argument: model", true);
                String base = Json.has(args, "baseUrl")
                        ? Json.optString(args, "baseUrl", "").trim() : cur.baseUrl;
                String key = Json.has(args, "apiKey")
                        ? Json.optString(args, "apiKey", "") : cur.apiKey;
                boolean marker = Json.has(args, "marker")
                        ? Json.optBoolean(args, "marker", cur.marker) : cur.marker;
                String markerTemplate = Json.has(args, "markerTemplate")
                        ? Json.optString(args, "markerTemplate", "") : cur.markerTemplate;
                boolean bridgeOn = Json.has(args, "bridge")
                        ? Json.optBoolean(args, "bridge", cur.bridge) : cur.bridge;
                if (base.length() == 0) {
                    return toolText("missing argument: baseUrl（当前没有可用端点）", true);
                }
                Snapshot next = new Snapshot(base, key, model, marker, bridgeOn, cur.token, markerTemplate, cur.enabled);
                if (!host.writeConfig(next)) return toolText("write prefs failed", true);
                Map<String, Object> r = Json.obj();
                r.put("ok", true);
                r.put("model", model);
                r.put("baseUrl", base);
                r.put("apiKeySet", key.length() > 0);
                r.put("changed", !model.equals(cur.model) || !base.equals(cur.baseUrl));
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_island".equals(name)) {
                Snapshot c = host.config();
                String t = Json.optString(args, "text", "").trim();
                String model = t.length() > 0 ? t : c.model;
                boolean ok = host.showIsland(model, Json.optBoolean(args, "force", true));
                Map<String, Object> r = Json.obj();
                r.put("ok", ok);
                r.put("alreadyShown", !ok);
                r.put("text", model);
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_logs".equals(name)) {
                int limit = (int) Json.optLong(args, "limit", 80);
                if (limit <= 0) limit = 80;
                long since = Json.optLong(args, "since", 0);
                return toolText(host.logs(limit, since), false);
            }
            if ("xiaoai_stats".equals(name)) {
                int days = (int) Json.optLong(args, "days", 7);
                if (days <= 0 || days > 90) days = 7;
                return toolText(host.stats(days), false);
            }
            return toolText("unknown tool: " + name, true);
        } catch (Throwable t) {
            try {
                return toolText("tool failed: " + t, true);
            } catch (Throwable ignored) {
                return Json.obj();
            }
        }
    }

    static Map<String, Object> toolText(String text, boolean error) {
        Map<String, Object> part = Json.obj();
        part.put("type", "text");
        part.put("text", text == null ? "" : text);
        List<Object> content = Json.arr();
        content.add(part);
        Map<String, Object> r = Json.obj();
        r.put("content", content);
        r.put("isError", error);
        return r;
    }

    // ------------------------------------------------------------- 配置合并

    /**
     * POST /config 的合并语义：**缺失的字段保持当前值；出现的字段（含空串/null）替换**。
     *
     * v0.8 之前是全量替换——配合「只认内存旧 token」的鉴权，形成 token 轮换死锁：
     * 设置页推送端只有新 token 和它自己那份（可能为默认值的）配置，重发整份
     * 会把小爱侧真正生效的配置冲掉。现在换 token 只需推 {"token":"..."}。
     * v1 设置页与 v2 配置页都全量发送全部字段，对它们行为不变。
     */
    public static Snapshot mergeConfig(Map<?, ?> body, Snapshot cur) {
        String baseUrl = Json.has(body, "baseUrl")
                ? Json.optString(body, "baseUrl", "") : cur.baseUrl;
        String apiKey = Json.has(body, "apiKey")
                ? Json.optString(body, "apiKey", "") : cur.apiKey;
        String model = Json.has(body, "model")
                ? Json.optString(body, "model", "") : cur.model;
        boolean marker = Json.has(body, "marker")
                ? Json.optBoolean(body, "marker", cur.marker) : cur.marker;
        String markerTemplate = Json.has(body, "markerTemplate")
                ? Json.optString(body, "markerTemplate", "") : cur.markerTemplate;
        boolean bridge = Json.has(body, "bridge")
                ? Json.optBoolean(body, "bridge", cur.bridge) : cur.bridge;
        String token = Json.has(body, "token")
                ? Json.optString(body, "token", "") : cur.token;
        boolean enabled = Json.has(body, "enabled")
                ? Json.optBoolean(body, "enabled", cur.enabled) : cur.enabled;
        return new Snapshot(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled);
    }

    // ------------------------------------------------------------- 会话辅助

    static String cliJson(String method, Map<String, Object> params) {
        try {
            Map<String, Object> o = Json.obj();
            o.put("id", "mcp");
            o.put("method", method);
            o.put("params", params == null ? Json.obj() : params);
            return Json.write(o);
        } catch (Throwable t) {
            return "{}";
        }
    }

    static String extractSessionId(String out) {
        if (out == null || out.length() == 0) return null;
        try {
            Object v = Json.read(out);
            if (v instanceof Map) {
                String s = pickString((Map<?, ?>) v, "sessionId");
                if (s != null) return s;
            }
        } catch (Throwable ignored) {
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"").matcher(out);
        return m.find() ? m.group(1) : null;
    }

    private static String pickString(Map<?, ?> o, String key) {
        String v = Json.optString(o, key, "");
        if (v.length() > 0) return v;
        Object r = o.get("result");
        if (r instanceof Map) {
            v = Json.optString((Map<?, ?>) r, key, "");
            if (v.length() > 0) return v;
        }
        return null;
    }

    static String brief(String s) {
        if (s == null) return "null";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
