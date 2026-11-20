package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.AppToolProtocol;

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

    // ------------------------------------------------------------- 后端端口（WP3）

    /** 运行时状态：facade/CLI/端口/版本。 */
    public interface RuntimePort {
        boolean facadeReady();

        boolean cliReady();

        int port();

        String versionName();

        /**
         * hook 就绪度摘要：{@code hooks=ok|partial|none compat=whitelist|probe|none xiaoai=<版本>}。
         * 宿主未装载 hook 时返回空串（不把「不适用」报成 none）。
         */
        String hookSummary();
    }

    /** 配置读写：只读快照 + 受限写入（远程只能改 model/marker）。 */
    public interface ConfigPort {
        Snapshot config();

        boolean writeConfig(Snapshot next);
    }

    /** 小爱 CLI 透传。 */
    public interface CliPort {
        /** 执行一条小爱 CLI JSON；服务未就绪返回 null。 */
        String callCli(String cmd);
    }

    /** 超级岛。 */
    public interface IslandPort {
        boolean showIsland(String text, boolean force);
    }

    /** 日志/统计/待办/单次调用元数据。 */
    public interface TelemetryPort {
        String logs(int limit, long since);

        /** 调用量统计 JSON（结构见 {@link UsageCounter#stats}）。 */
        String stats(int days);

        /** 确定性创建 Notes 待办；失败时 Result.text 是结构化错误。 */
        AppToolProtocol.Result createTodo(String title, String content);

        /** 单次 tools/call 的元数据回调；不含参数值。 */
        default void onToolCall(String tool, boolean ok, long elapsedMs, int argCount) {}
    }

    /**
     * 8772 动作面转发端口（0.16.0 单端口统一）。
     *
     * <p>能力桥只按 {@link plus.xiaoi.module.core.mcp.ToolOwnership} 决定转发哪些工具，
     * 实现方负责发现 8772 的实际端口（8770 常被小爱占用，会顺延）。</p>
     */
    public interface AgentPort {
        /** 对端工具清单（MCP {@code result.tools} 的 List）；不可用返回 null。 */
        Object tools();

        /** 转发一次 tools/call；不可用或失败返回 null。 */
        Map<String, Object> callTool(String name, Map<?, ?> arguments);
    }

    /**
     * 8771 工具后端（WP3）：把原 11 方法 god 接口拆成 5 个能力端口。
     * Bridge 用同一实现填满五个槽位；测试可分别注入。
     */
    public static final class Hosts {
        public final RuntimePort runtime;
        public final ConfigPort config;
        public final CliPort cli;
        public final IslandPort island;
        public final TelemetryPort telemetry;
        /** 8772 转发；null = 只提供 8771 自己的工具（单测/未启用）。 */
        public final AgentPort agent;

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry) {
            this(runtime, config, cli, island, telemetry, null);
        }

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry, AgentPort agent) {
            if (runtime == null || config == null || cli == null
                    || island == null || telemetry == null) {
                throw new IllegalArgumentException("Mcp.Hosts 端口不能为空");
            }
            this.runtime = runtime;
            this.config = config;
            this.cli = cli;
            this.island = island;
            this.telemetry = telemetry;
            this.agent = agent;
        }
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
        /** true = 允许同一局域网访问能力桥（仍要求非空 token）。 */
        public final boolean lan;
        /** true = 免鉴权（可选）；本机与局域网都跳过 Bearer。 */
        public final boolean noAuth;
        /** true = 允许任意非空密码用于局域网（不要求 32 hex）。 */
        public final boolean weakToken;
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
            this(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled, false);
        }

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token, String markerTemplate, boolean enabled,
                        boolean lan) {
            this(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled, lan, false);
        }

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token, String markerTemplate, boolean enabled,
                        boolean lan, boolean noAuth) {
            this(baseUrl, apiKey, model, marker, bridge, token, markerTemplate, enabled, lan, noAuth, false);
        }

        public Snapshot(String baseUrl, String apiKey, String model, boolean marker,
                        boolean bridge, String token, String markerTemplate, boolean enabled,
                        boolean lan, boolean noAuth, boolean weakToken) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null ? "" : model;
            this.marker = marker;
            this.markerTemplate = markerTemplate == null ? "" : markerTemplate;
            this.enabled = enabled;
            this.bridge = bridge;
            this.lan = lan;
            this.noAuth = noAuth;
            this.weakToken = weakToken;
            this.token = token == null ? "" : token;
        }
    }

    // ------------------------------------------------------------- 入口

    /**
     * 处理一条 POST /mcp 报文。
     *
     * @return 响应体；通知（无 id）返回 null，调用方应回 202 空体
     */
    public static String handle(String body, Hosts hosts) {
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
                return rpcResult(id, initializeResult(hosts.runtime.versionName()));
            }
            if ("ping".equals(m)) {
                return rpcResult(id, Json.obj());
            }
            if ("tools/list".equals(m)) {
                // 单端口统一合并时会请求对端的 tools/list；对端必须只回自己的工具，
                // 否则 A→B→A 无限递归。对端由 LoopbackMcpClient 带 params.own=true。
                Object p = req.get("params");
                boolean own = p instanceof Map && Json.optBoolean((Map<?, ?>) p, "own", false);
                return rpcResult(id, own ? toolsList() : toolsListMerged(hosts));
            }
            if ("tools/call".equals(m)) {
                Object p = req.get("params");
                Map<?, ?> params = p instanceof Map ? (Map<?, ?>) p : null;
                String tool = params == null ? "" : Json.optString(params, "name", "");
                int argCount = 0;
                if (params != null) {
                    Object a = params.get("arguments");
                    if (a instanceof Map) argCount = ((Map<?, ?>) a).size();
                }
                long started = System.currentTimeMillis();
                Map<String, Object> result = toolsCall(params, hosts);
                try {
                    boolean ok = !Boolean.TRUE.equals(result.get("isError"));
                    hosts.telemetry.onToolCall(tool, ok, System.currentTimeMillis() - started, argCount);
                } catch (Throwable ignored) {
                    // 记录失败不能影响工具结果
                }
                return rpcResult(id, result);
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
        r.put("instructions", "小爱同学+ 统一 MCP 端点（8771，唯一端口）：15 个工具都从这里调用"
                + "（桥 9 + 设备动作 6，设备动作经控制面转发到 App 进程执行）。"
                + "xiaoai_submit 让小爱执行一句话任务，xiaoai_status 探活，xiaoai_cli 走原始 CLI，"
                + "xiaoai_stats 看三方/官方模型调用量。"
                + "端点与密钥只能在手机 App 内修改；远程只能切换已有端点的 model/marker。");
        return r;
    }

    /** 工具表（顺序：status / submit / todo_create / cli / config / island / switch_model / logs / stats）。 */
    /** xiaoai_todo_create 的输入上限：防止超长文本穿过 binder/HTTP。 */
    public static final int TODO_TITLE_MAX = plus.xiaoi.module.core.Contract.TODO_TITLE_MAX;
    public static final int TODO_CONTENT_MAX = plus.xiaoi.module.core.Contract.TODO_CONTENT_MAX;
    /** xiaoai_submit.text 上限。 */
    public static final int SUBMIT_TEXT_MAX = plus.xiaoi.module.core.Contract.SUBMIT_TEXT_MAX;

    public static Map<String, Object> toolsList() {
        List<Object> tools = Json.arr();

        Map<String, Object> status = Json.obj();
        status.put("name", "xiaoai_status");
        status.put("description", "探活：返回小爱进程内 facade/CLI 是否就绪，以及桥的监听端口。");
        status.put("inputSchema", emptySchema());

        Map<String, Object> submit = Json.obj();
        submit.put("name", "xiaoai_submit");
        submit.put("description", "让小爱（手机端语音助手）执行一句话任务。默认返回它的回复文本；"
                + "structured=true 时返回 {\"ok\",\"text\",\"sessionId\"}，可用 sessionId 续聊。"
                + "不传 sessionId 时自动开新会话。");
        Map<String, Object> submitProps = Json.obj();
        Map<String, Object> submitText = prop("string", "要说给小爱的话");
        submitText.put("minLength", 1L);
        submitText.put("maxLength", (long) SUBMIT_TEXT_MAX);
        submitProps.put("text", submitText);
        submitProps.put("sessionId", prop("string", "可选；复用已有会话以获得上下文"));
        submitProps.put("structured", prop("boolean",
                "可选；true 返回 JSON（含 sessionId），默认 false 只返回文本（兼容旧提示词）"));
        Map<String, Object> submitSchema = Json.obj();
        submitSchema.put("type", "object");
        submitSchema.put("properties", submitProps);
        submitSchema.put("required", list("text"));
        submitSchema.put("additionalProperties", Boolean.FALSE);
        submit.put("inputSchema", submitSchema);

        Map<String, Object> todo = Json.obj();
        todo.put("name", "xiaoai_todo_create");
        todo.put("description", "确定性创建一张独立的小米笔记待办卡片：直接调用 Notes 的 "
                + "AppToolProvider（todo_create），不经过 LLM、不做语义改写。title 必填；"
                + "content 可省略，省略时自动生成「- [ ] <title>」，否则每行一项、格式为 "
                + "「- [ ] 文本」。失败返回结构化错误，可改用 xiaoai_submit 让小爱兜底。");
        Map<String, Object> todoProps = Json.obj();
        todoProps.put("title", prop("string", "待办清单标题，必填"));
        todoProps.put("content", prop("string", "可选；每行「- [ ] 文本」，留空则由 title 生成一项"));
        Map<String, Object> todoSchema = Json.obj();
        todoSchema.put("type", "object");
        todoSchema.put("properties", todoProps);
        todoSchema.put("required", list("title"));
        todoSchema.put("additionalProperties", Boolean.FALSE);
        todo.put("inputSchema", todoSchema);

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
        isl.put("description", "手动触发一次超级岛提示（单次，6 秒后自动消失），用于验证岛通路。"
                + "deprecated：新代码请用 xiaoai_notify（channel=island）。");
        Map<String, Object> islProps = Json.obj();
        islProps.put("text", prop("string", "可选；提示文案，默认「小爱同学plus」"));
        islProps.put("force", prop("boolean", "是否忽略单次限制（调试用，默认 true）"));
        Map<String, Object> islSchema = Json.obj();
        islSchema.put("type", "object");
        islSchema.put("properties", islProps);
        islSchema.put("additionalProperties", Boolean.FALSE);
        isl.put("inputSchema", islSchema);

        Map<String, Object> sw = Json.obj();
        sw.put("name", "xiaoai_switch_model");
        sw.put("description", "热切换模型：只能改 model / marker；端点、API Key、bridge、lan 只能在手机 App 设置。"
                + "写入 prefs 后全进程 1 秒内生效，无需重启小爱。");
        Map<String, Object> swProps = Json.obj();
        swProps.put("model", prop("string", "上游 model id，例如 deepseek-chat"));
        swProps.put("marker", prop("boolean", "可选；回复开头是否带 [model=...]"));
        Map<String, Object> swSchema = Json.obj();
        swSchema.put("type", "object");
        swSchema.put("properties", swProps);
        swSchema.put("required", list("model"));
        swSchema.put("additionalProperties", Boolean.FALSE);
        sw.put("inputSchema", swSchema);

        Map<String, Object> lg = Json.obj();
        lg.put("name", "xiaoai_logs");
        lg.put("description", "读取模块运行日志（最近 300 条环形缓冲，落盘在 files/xiaoi_plus.log）。"
                + "每行含 seq/t/level/msg，并追加 tag/ts(epoch 毫秒)/pid。");
        Map<String, Object> lgProps = Json.obj();
        Map<String, Object> lgLimit = prop("integer",
                "返回尾部多少条；缺省 80，0 = 空数组，上限 300");
        lgLimit.put("minimum", 0L);
        lgLimit.put("maximum", (long) plus.xiaoi.module.core.Contract.LOGS_LIMIT_MAX);
        lgProps.put("limit", lgLimit);
        lgProps.put("since", prop("integer", "只返回 seq 大于该值的日志"));
        Map<String, Object> lgSchema = Json.obj();
        lgSchema.put("type", "object");
        lgSchema.put("properties", lgProps);
        lgSchema.put("additionalProperties", Boolean.FALSE);
        lg.put("inputSchema", lgSchema);

        Map<String, Object> st = Json.obj();
        st.put("name", "xiaoai_stats");
        st.put("description", "调用量统计：按天返回三方 / 官方模型的调用次数。"
                + "默认只算主对话（请求含 tools 或消息数 ≥6），记忆抽取等子调用单独记在 sub 里。"
                + "days 超过 90 会夹到 90 并在结果里回 clamped=true。");
        Map<String, Object> stProps = Json.obj();
        Map<String, Object> stDays = prop("integer", "统计最近多少天，默认 7，范围 1~90");
        stDays.put("minimum", 1L);
        stDays.put("maximum", (long) plus.xiaoi.module.core.Contract.STATS_DAYS_MAX);
        stProps.put("days", stDays);
        Map<String, Object> stSchema = Json.obj();
        stSchema.put("type", "object");
        stSchema.put("properties", stProps);
        stSchema.put("additionalProperties", Boolean.FALSE);
        st.put("inputSchema", stSchema);

        tools.add(status);
        tools.add(submit);
        tools.add(todo);
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

    /**
     * 单端口统一：本机（8771）工具 + 从 8772 转发来的动作面工具。
     *
     * <p>只追加归属为 {@code app} 的工具，并按名字去重；对端不可用时静默退化为
     * 只有能力桥工具，保证 8771 本身永远可用。</p>
     */
    public static Map<String, Object> toolsListMerged(Hosts hosts) {
        Map<String, Object> r = toolsList();
        if (hosts == null || hosts.agent == null) return r;
        Object extra;
        try {
            extra = hosts.agent.tools();
        } catch (Throwable t) {
            return r;
        }
        if (!(extra instanceof List)) return r;
        Object mineObj = r.get("tools");
        if (!(mineObj instanceof List)) return r;
        @SuppressWarnings("unchecked")
        List<Object> mine = (List<Object>) mineObj;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Object o : mine) {
            if (o instanceof Map) seen.add(Json.optString((Map<?, ?>) o, "name", ""));
        }
        for (Object o : (List<?>) extra) {
            if (!(o instanceof Map)) continue;
            String name = Json.optString((Map<?, ?>) o, "name", "");
            if (!plus.xiaoi.module.core.mcp.ToolOwnership.isApp(name)) continue;
            if (seen.contains(name)) continue;
            seen.add(name);
            mine.add(o);
        }
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

    static Map<String, Object> toolsCall(Map<?, ?> params, Hosts hosts) {
        String name = params == null ? "" : Json.optString(params, "name", "");
        Map<?, ?> args = null;
        if (params != null) {
            Object a = params.get("arguments");
            if (a instanceof Map) args = (Map<?, ?>) a;
        }
        try {
            if ("xiaoai_status".equals(name)) {
                String bad = unexpectedArg(args);
                if (bad != null) return toolText(bad, true);
                String t = "facade=" + hosts.runtime.facadeReady()
                        + " cli=" + hosts.runtime.cliReady()
                        + " port=" + hosts.runtime.port()
                        + " version=" + hosts.runtime.versionName();
                String hook = hosts.runtime.hookSummary();
                if (hook != null && hook.length() > 0) t = t + " " + hook;
                return toolText(t, false);
            }
            if ("xiaoai_submit".equals(name)) {
                String bad = unexpectedArg(args, "text", "sessionId", "structured");
                if (bad != null) return toolText(bad, true);
                String text = Json.optString(args, "text", "").trim();
                if (text.length() == 0) return toolText("missing argument: text", true);
                if (text.length() > SUBMIT_TEXT_MAX) {
                    return toolText("text too long (max " + SUBMIT_TEXT_MAX + ")", true);
                }
                String sid = Json.optString(args, "sessionId", "").trim();
                if (sid.length() == 0) {
                    String open = hosts.cli.callCli(cliJson("session.open", Json.obj()));
                    sid = extractSessionId(open);
                    if (sid == null) return toolText("session.open failed: " + brief(open), true);
                }
                Map<String, Object> p = Json.obj();
                p.put("sessionId", sid);
                p.put("text", text);
                String out = hosts.cli.callCli(cliJson("agent.submit", p));
                if (out == null) return toolText("ERR cli service not ready", true);
                if (!Json.optBoolean(args, "structured", false)) {
                    // 默认仍是纯文本：很多现有提示词直接展示 xiaoai_submit 的返回。
                    return toolText(out, false);
                }
                Map<String, Object> r = Json.obj();
                r.put("ok", true);
                r.put("text", out);
                r.put("sessionId", sid);
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_todo_create".equals(name)) {
                String bad = unexpectedArg(args, "title", "content");
                if (bad != null) return toolText(bad, true);
                String title = Json.optString(args, "title", "").trim();
                if (title.length() == 0) return toolText("missing argument: title", true);
                if (title.length() > TODO_TITLE_MAX) {
                    return toolText("title too long (max " + TODO_TITLE_MAX + ")", true);
                }
                String content = Json.optString(args, "content", "");
                if (content.trim().length() == 0) {
                    content = AppToolProtocol.defaultContent(title);
                }
                if (content.length() > TODO_CONTENT_MAX) {
                    return toolText("content too long (max " + TODO_CONTENT_MAX + ")", true);
                }
                AppToolProtocol.Result r;
                try {
                    r = hosts.telemetry.createTodo(title, content);
                } catch (Throwable t) {
                    r = AppToolProtocol.error("HOST_ERROR", "Notes 调用层异常", String.valueOf(t));
                }
                if (r == null) {
                    return toolText(AppToolProtocol.errorJson("EMPTY_RESULT",
                            "Notes 调用层返回空结果", ""), true);
                }
                return toolText(r.text, !r.ok);
            }
            if ("xiaoai_cli".equals(name)) {
                String bad = unexpectedArg(args, "cmd");
                if (bad != null) return toolText(bad, true);
                String cmd = Json.optString(args, "cmd", "").trim();
                if (cmd.length() == 0) return toolText("missing argument: cmd", true);
                String out = hosts.cli.callCli(cmd);
                if (out == null) return toolText("ERR cli service not ready", true);
                return toolText(out, false);
            }
            if ("xiaoai_config".equals(name)) {
                String bad = unexpectedArg(args);
                if (bad != null) return toolText(bad, true);
                Snapshot c = hosts.config.config();
                Map<String, Object> r = Json.obj();
                r.put("model", c.model);
                r.put("baseUrl", c.baseUrl);
                r.put("enabled", c.enabled);
                r.put("marker", c.marker);
                r.put("markerTemplate", c.markerTemplate);
                r.put("bridge", c.bridge);
                r.put("lan", c.lan);
                r.put("noAuth", c.noAuth);
                r.put("weakToken", c.weakToken);
                // 鉴权模式：off（免 token）/ weak（任意非空密码）/ token（要求 32 hex）
                r.put("authMode", plus.xiaoi.module.core.BridgePolicy.authMode(c.noAuth, c.weakToken));
                r.put("apiKeySet", c.apiKey.length() > 0);
                r.put("tokenSet", c.token.length() > 0);
                r.put("port", (long) hosts.runtime.port());
                r.put("version", hosts.runtime.versionName());
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_switch_model".equals(name)) {
                Snapshot cur = hosts.config.config();
                // WP4 D10：远程只能改 model/marker；端点和密钥只能在手机 App 设置。
                String rejected = rejectedSwitchArg(args);
                if (rejected != null) return toolText(rejected, true);
                String model = Json.optString(args, "model", "").trim();
                if (model.length() == 0) return toolText("missing argument: model", true);
                if (model.length() > plus.xiaoi.module.core.Contract.MODEL_MAX) {
                    return toolText("model too long (max "
                            + plus.xiaoi.module.core.Contract.MODEL_MAX + ")", true);
                }
                boolean marker = Json.has(args, "marker")
                        ? Json.optBoolean(args, "marker", cur.marker) : cur.marker;
                if (cur.baseUrl.length() == 0) {
                    return toolText("missing argument: baseUrl（当前没有可用端点，请在手机 App 设置）", true);
                }
                Snapshot next = new Snapshot(cur.baseUrl, cur.apiKey, model, marker,
                        cur.bridge, cur.token, cur.markerTemplate, cur.enabled, cur.lan, cur.noAuth,
                        cur.weakToken);
                if (!hosts.config.writeConfig(next)) return toolText("write prefs failed", true);
                Map<String, Object> r = Json.obj();
                r.put("ok", true);
                r.put("model", model);
                r.put("lastGoodModel", model);
                r.put("baseUrl", cur.baseUrl);
                r.put("apiKeySet", cur.apiKey.length() > 0);
                r.put("changed", !model.equals(cur.model) || marker != cur.marker);
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_island".equals(name)) {
                String bad = unexpectedArg(args, "text", "force");
                if (bad != null) return toolText(bad, true);
                String t = Json.optString(args, "text", "").trim();
                // 0.16.0：默认文案不再是模型名（模型名对用户没有意义）。
                String text = t.length() > 0 ? t : ISLAND_DEFAULT_TEXT;
                boolean ok = hosts.island.showIsland(text, Json.optBoolean(args, "force", true));
                Map<String, Object> r = Json.obj();
                r.put("ok", ok);
                r.put("alreadyShown", !ok);
                r.put("text", text);
                return toolText(Json.write(r), false);
            }
            if ("xiaoai_logs".equals(name)) {
                String bad = unexpectedArg(args, "limit", "since");
                if (bad != null) return toolText(bad, true);
                int limit = Json.has(args, "limit")
                        ? (int) Json.optLong(args, "limit", 0)
                        : plus.xiaoi.module.core.Contract.LOGS_LIMIT_DEFAULT;
                limit = plus.xiaoi.module.core.bridge.BridgeHttpPolicy.logLimit(limit);
                long since = Json.optLong(args, "since", 0);
                return toolText(hosts.telemetry.logs(limit, since), false);
            }
            if ("xiaoai_stats".equals(name)) {
                String bad = unexpectedArg(args, "days");
                if (bad != null) return toolText(bad, true);
                long raw = Json.optLong(args, "days",
                        plus.xiaoi.module.core.Contract.STATS_DAYS_DEFAULT);
                // 不在这里夹：上层要能看出「请求值被夹过」并回 clamped=true。
                int days = raw > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) raw;
                return toolText(hosts.telemetry.stats(days), false);
            }
            return forwardOrUnknown(name, args, hosts);
        } catch (Throwable t) {
            try {
                return toolText("tool failed: " + t, true);
            } catch (Throwable ignored) {
                return Json.obj();
            }
        }
    }

    /** xiaoai_island 的默认文案（0.16.0 起不再用模型名）。 */
    public static final String ISLAND_DEFAULT_TEXT = "小爱同学plus";

    /**
     * 单端口统一：动作面归属的工具转发给 8772；其余仍报 unknown tool。
     */
    private static Map<String, Object> forwardOrUnknown(String name, Map<?, ?> args, Hosts hosts) {
        if (hosts != null && hosts.agent != null
                && plus.xiaoi.module.core.mcp.ToolOwnership.isApp(name)) {
            Map<String, Object> out = null;
            try {
                out = hosts.agent.callTool(name, args);
            } catch (Throwable ignored) {
            }
            if (out != null) return out;
            Map<String, Object> o = Json.obj();
            o.put("ok", false);
            o.put("code", "AGENT_UNAVAILABLE");
            o.put("message", "8772 动作面暂不可用（App 进程未运行或 AgentService 已停止），可稍后重试");
            o.put("retryable", true);
            return toolText(Json.write(o), true);
        }
        return toolText("unknown tool: " + name, true);
    }

    /**
     * 未知参数校验：0.16.0 起 8771 不再静默忽略错参数，模型传错会看到明确错误。
     *
     * @return null = 可以继续；否则是 isError 文案
     */
    static String unexpectedArg(Map<?, ?> args, String... allowed) {
        if (args == null || args.isEmpty()) return null;
        for (Object k : args.keySet()) {
            String key = k == null ? "" : String.valueOf(k);
            boolean ok = false;
            for (String a : allowed) {
                if (a.equals(key)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return "unknown argument: " + key
                        + (allowed.length == 0 ? "（该工具不接受参数）"
                        : "（可用：" + join(allowed) + "）");
            }
        }
        return null;
    }

    private static String join(String... items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * xiaoai_switch_model 的参数白名单：只允许 model / marker。
     * 端点和密钥类参数给出明确可行动文案；其他未知参数也拒绝，避免静默忽略。
     *
     * @return null = 可以继续；否则是 isError 文案
     */
    static String rejectedSwitchArg(Map<?, ?> args) {
        if (args == null) return null;
        for (Object k : args.keySet()) {
            String key = k == null ? "" : String.valueOf(k);
            if ("model".equals(key) || "marker".equals(key)) continue;
            if ("baseUrl".equals(key) || "apiKey".equals(key) || "bridge".equals(key)
                    || "lan".equals(key) || "token".equals(key) || "enabled".equals(key)) {
                return "端点和密钥只能在手机 App 设置；xiaoai_switch_model 只接受 model / marker"
                        + "（" + key + " 已拒绝）";
            }
            return "unsupported argument: " + key
                    + "（xiaoai_switch_model 只接受 model / marker）";
        }
        return null;
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
