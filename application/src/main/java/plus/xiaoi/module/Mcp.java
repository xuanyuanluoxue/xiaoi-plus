package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.AppToolProtocol;
import plus.xiaoi.module.application.log.RequestTrace;
import plus.xiaoi.module.core.Contract;

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
    /** 明确支持并可原样回显的协议版本（旧版客户端会挑一个）。 */
    private static final String[] SUPPORTED_PROTOCOLS = {"2025-03-26", "2024-11-05"};
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

        /**
         * 带调用方归属的版本：{@code caller=local|lan|inbound}。
         *
         * <p>默认退回旧回调，保持既有实现与测试不用改。</p>
         */
        default void onToolCall(String tool, boolean ok, long elapsedMs, int argCount,
                                String caller) {
            onToolCall(tool, ok, elapsedMs, argCount);
        }
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
        /** P2 提示词与记忆；null = 两个工具返回「不可用」（单测默认）。 */
        public final MemoryPort memory;
        /** 小爱进程内 AppTool（通道 A）；null = 相关工具返回不可用。 */
        public final AppToolPort appTool;

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry) {
            this(runtime, config, cli, island, telemetry, null, null);
        }

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry, AgentPort agent) {
            this(runtime, config, cli, island, telemetry, agent, null);
        }

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry, AgentPort agent,
                     MemoryPort memory) {
            this(runtime, config, cli, island, telemetry, agent, memory, null);
        }

        public Hosts(RuntimePort runtime, ConfigPort config, CliPort cli,
                     IslandPort island, TelemetryPort telemetry, AgentPort agent,
                     MemoryPort memory, AppToolPort appTool) {
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
            this.memory = memory;
            this.appTool = appTool;
        }
    }

    /**
     * 提示词与记忆端口（P2）。
     *
     * <p>实现方是小爱进程里的 Bridge：记忆文件在小爱私有目录，只有同进程能直读。
     * 返回的 JSON 文本由 {@code MemoryService} 生成，{@code ok=false} 时工具层置
     * {@code isError=true}。</p>
     */
    public interface MemoryPort {
        /** 提示词配置 + 注入统计（JSON 文本）。 */
        String promptStatus();

        /** false = memoryWriteEnabled 关闭，写操作必须被拒绝。 */
        boolean writable();

        /** 分节返回 MEMORY.md；{@code limit} 是每小节条数。 */
        String memoryOverview(int limit);

        /** 追加条目；{@code section} 支持 {@code 一级/二级} 路径。 */
        String memoryAppend(String section, String text);

        /** 替换小节正文（保留标题）。 */
        String memoryReplace(String section, String text);

        /**
         * 官方记忆只读检索（转调小爱 {@code UIAgentServiceForOSBot#readMemory}）。
         *
         * @param keywords 关键词，空串表示最近记录
         * @return 小爱原样返回的 JSON 文本，或结构化错误（{@code ok=false}）
         */
        default String officialRead(String keywords, int page, int pageSize) {
            return "{\"ok\":false,\"code\":\"APPTOOL_UNAVAILABLE\","
                    + "\"message\":\"官方记忆通道未实现\"}";
        }

        /**
         * 官方记忆写入（自然语言，交给小爱自己的抽取流水线）。
         *
         * @return 小爱原样返回的 JSON 文本，或结构化错误
         */
        default String officialWrite(String query) {
            return "{\"ok\":false,\"code\":\"APPTOOL_UNAVAILABLE\","
                    + "\"message\":\"官方记忆通道未实现\"}";
        }

        /**
         * 加密记忆库探针（{@code clawdb=ok} / {@code clawdb=none}）；null = 不显示。
         *
         * <p>库是 SQLCipher 加密的，口令由小爱自己的 AndroidKeyStore 条目解出，
         * 只有小爱进程内能读。</p>
         */
        default String clawProbe() {
            return null;
        }

        /** 加密库统计：各表行数 + 时间范围 + {@code mem_type} 分布（不含正文）。 */
        default String clawStats() {
            return "{\"ok\":false,\"code\":\"CLAWDB_UNAVAILABLE\","
                    + "\"message\":\"加密库通道未实现\"}";
        }

        /**
         * 实时查记忆条目（{@code memory_meta}，不经 {@code MEMORY.md}）。
         *
         * @param q     关键词，空串不限；匹配条目正文与键
         * @param since 起始时间（epoch ms），0 不限
         * @param limit 条数（已夹到 1..200）
         */
        default String clawLive(String q, long since, int limit) {
            return "{\"ok\":false,\"code\":\"CLAWDB_UNAVAILABLE\","
                    + "\"message\":\"加密库通道未实现\"}";
        }

        /**
         * 官方记忆库的同步状态（{@code memory_data_<uid>.db}）：各表条数、
         * {@code syncStatus} 分布与待同步行。只读，不含 {@code meta} 正文。
         *
         * @param limit 每张表返回的待同步行数（已夹到 1..100）
         */
        default String officialSync(int limit) {
            return "{\"ok\":false,\"code\":\"OFFICIALDB_UNAVAILABLE\","
                    + "\"message\":\"官方记忆库通道未实现\"}";
        }
    }

    /**
     * 小爱进程内 AppTool 端口（通道 A）。
     *
     * <p>与 {@link MemoryPort} 分开：记忆是文件语义，这里是「小爱自己的 18 个方法」。
     * 实现方在 Bridge，底层走 {@code IAppToolProvider} 的 JSON-RPC。</p>
     */
    public interface AppToolPort {
        /** 调用小爱自己的一个 AppTool；返回原样 JSON 或结构化错误。 */
        String call(String tool, Map<String, Object> args);

        /** 就绪度，例如 {@code apptools=ok methods=64}。 */
        default String summary() {
            return "apptools=unknown";
        }

        /**
         * 调其它应用的 AppTool provider（跨包 bind，调用方是小爱）。
         *
         * @param pkg       目标包名
         * @param service   目标 Service 全类名
         * @param tool      provider 声明的工具名
         * @param args      参数对象（可为空 Map）
         * @param timeoutMs 等待上限
         * @return provider 原样 JSON 或结构化错误
         */
        default String callExternal(String pkg, String service, String tool,
                                    Map<String, Object> args, long timeoutMs) {
            return "{\"ok\":false,\"code\":\"APPTOOL_UNAVAILABLE\","
                    + "\"message\":\"外部 AppTool 通道未实现\"}";
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
        return handle(body, hosts, false, false, PROFILE_FULL);
    }

    /** 完整权限（本机 / 外部 Agent 默认）。 */
    public static final String PROFILE_FULL = "full";

    /** 只读画像：给「小爱反向调用」用，禁止一切会再触发小爱或改配置的工具。 */
    public static final String PROFILE_READONLY = "readonly";

    /** 入站（小爱）允许的工具：只读查询与设备动作，不含 submit/ask/cli/config/confirm。 */
    private static final java.util.Set<String> INBOUND_ALLOWED =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "xiaoai_status", "device.info", "app.open",
                    "alarm.set", "media.control", "notification.send"));

    /**
     * @param lan         true = 请求来自非 loopback 连接（局域网）
     * @param lanWriteOk 局域网请求是否带了写二次因子（{@code X-Xiaoi-Write: <token>}）
     * @param profile     {@link #PROFILE_FULL} 或 {@link #PROFILE_READONLY}
     */
    public static String handle(String body, Hosts hosts, boolean lan, boolean lanWriteOk) {
        return handle(body, hosts, lan, lanWriteOk, PROFILE_FULL);
    }

    public static String handle(String body, Hosts hosts, boolean lan, boolean lanWriteOk,
                                String profile) {
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
                Object ip = req.get("params");
                String requested = ip instanceof Map
                        ? Json.optString((Map<?, ?>) ip, "protocolVersion", "") : "";
                return rpcResult(id, initializeResult(hosts.runtime.versionName(), requested));
            }
            if ("ping".equals(m)) {
                return rpcResult(id, Json.obj());
            }
            if ("tools/list".equals(m)) {
                // 单端口统一合并时会请求对端的 tools/list；对端必须只回自己的工具，
                // 否则 A→B→A 无限递归。对端由 LoopbackMcpClient 带 params.own=true。
                Object p = req.get("params");
                boolean own = p instanceof Map && Json.optBoolean((Map<?, ?>) p, "own", false);
                return rpcResult(id, own ? toolsList(profile) : toolsListMerged(hosts, profile));
            }
            if ("tools/call".equals(m)) {
                // 只覆盖同进程同步调用链：工具执行期间产生的 Logs 行会带 req=<id>。
                // 跨 Binder / 隔线程的后续动作不在这个 trace 内，避免伪造关联。
                RequestTrace.enter();
                try {
                    Object p = req.get("params");
                    Map<?, ?> params = p instanceof Map ? (Map<?, ?>) p : null;
                    String tool = params == null ? "" : Json.optString(params, "name", "");
                    int argCount = 0;
                    if (params != null) {
                        Object a = params.get("arguments");
                        if (a instanceof Map) argCount = ((Map<?, ?>) a).size();
                    }
                    long started = System.currentTimeMillis();
                    Map<String, Object> result = toolsCall(params, hosts, lan, lanWriteOk, profile);
                    try {
                        boolean ok = !Boolean.TRUE.equals(result.get("isError"));
                        String caller = PROFILE_READONLY.equals(profile) ? "inbound"
                                : (lan ? "lan" : "local");
                        hosts.telemetry.onToolCall(tool, ok,
                                System.currentTimeMillis() - started, argCount, caller);
                    } catch (Throwable ignored) {
                        // 记录失败不能影响工具结果
                    }
                    return rpcResult(id, result);
                } finally {
                    RequestTrace.exit();
                }
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
        return initializeResult(versionName, null);
    }

    /**
     * 协议版本协商：客户端请求的版本在支持列表里就原样回显，否则回我们最新的。
     *
     * <p>硬编码单一版本会让「要 2025-06-18 的客户端」认为不兼容；反过来乱报
     * 未实现的版本又会诱导客户端使用我们没有的能力。这里只认自己真实现的版本。</p>
     */
    public static Map<String, Object> initializeResult(String versionName, String requested) {
        String negotiated = PROTOCOL;
        if (requested != null) {
            for (String v : SUPPORTED_PROTOCOLS) {
                if (v.equals(requested)) {
                    negotiated = v;
                    break;
                }
            }
        }
        Map<String, Object> toolsCap = Json.obj();
        toolsCap.put("listChanged", Boolean.FALSE);
        Map<String, Object> caps = Json.obj();
        caps.put("tools", toolsCap);

        Map<String, Object> info = Json.obj();
        info.put("name", SERVER);
        info.put("version", versionName);

        Map<String, Object> r = Json.obj();
        r.put("protocolVersion", negotiated);
        r.put("capabilities", caps);
        r.put("serverInfo", info);
        r.put("instructions", "小爱同学+ 统一 MCP 端点（8771，唯一端口）：24 个工具都从这里调用"
                + "（桥 18 + 设备动作 6，设备动作经控制面转发到 App 进程执行）。"
                + "xiaoai_submit 让小爱执行一句话任务，xiaoai_nlp 走小爱语义引擎，"
                + "xiaoai_status 探活，xiaoai_cli 走原始 CLI，"
                + "xiaoai_stats 看三方/官方模型调用量，xiaoai_prompt 看系统提示词状态，"
                + "xiaoai_memory 读写记忆（写需在 App 内开启）。"
                + "xiaoai_apptools 列出可调用的系统应用工具，xiaoai_apptool 按白名单调用"
                + "（日历/闹钟/录音/相册/天气/笔记…，写类要 confirm）。"
                + "端点与密钥只能在手机 App 内修改；远程只能切换已有端点的 model/marker。");
        return r;
    }

    /** 工具表（顺序：status / submit / todo_create / cli / config / island / switch_model / logs / stats）。 */
    /** xiaoai_todo_create 的输入上限：防止超长文本穿过 binder/HTTP。 */
    public static final int TODO_TITLE_MAX = plus.xiaoi.module.core.Contract.TODO_TITLE_MAX;
    public static final int TODO_CONTENT_MAX = plus.xiaoi.module.core.Contract.TODO_CONTENT_MAX;
    /** xiaoai_submit.text 上限。 */
    public static final int SUBMIT_TEXT_MAX = plus.xiaoi.module.core.Contract.SUBMIT_TEXT_MAX;
    /** xiaoai_nlp.text 上限（自然语言一句）。 */
    public static final int NLP_TEXT_MAX = 500;
    /** 外部 AppTool 单次调用上限（转录/相册搜索会跑模型或扫库）。 */
    public static final long APPTOOL_EXT_TIMEOUT_MS = 20000L;

    static Map<String, Object> toolsListAll() {
        List<Object> tools = Json.arr();

        Map<String, Object> status = Json.obj();
        status.put("name", "xiaoai_status");
        status.put("description", "探活：返回小爱进程内 facade/CLI 是否就绪、桥的监听端口，"
                + "以及 AppTool 通道就绪度（apptools=ok methods=N / apptools=none）。");
        status.put("inputSchema", emptySchema());

        Map<String, Object> nlp = Json.obj();
        nlp.put("name", "xiaoai_nlp");
        nlp.put("description", "把小爱自己的语义解析结果以 JSON 返回（小爱进程内 nlp_query，"
                + "不经过对话模型）：给出这句话会触发的执行计划，形如 "
                + "{\"type\",\"instruction_types\":[...],\"instruction_count\"}，"
                + "instruction_types 里的 Application.Operate / Template.Query / UIController.Navigate "
                + "等即小爱的能力分组。适合外部 Agent 先判断「这句话小爱能不能直接执行、属于哪类」。"
                + "只在本机解析的语句毫秒级返回；音乐/天气这类要联网的语句在小爱内部不返回，"
                + "8 秒后回 CALL_TIMEOUT，之后短时间再调回 NLP_BUSY（那条线程仍占着），"
                + "这类语句请改用 xiaoai_submit。");
        Map<String, Object> nlpProps = Json.obj();
        Map<String, Object> nlpText = prop("string", "要解析的自然语言，例如「明天早上八点叫我」");
        nlpText.put("minLength", 1L);
        nlpText.put("maxLength", (long) NLP_TEXT_MAX);
        nlpProps.put("text", nlpText);
        Map<String, Object> nlpSchema = Json.obj();
        nlpSchema.put("type", "object");
        nlpSchema.put("properties", nlpProps);
        nlpSchema.put("required", list("text"));
        nlpSchema.put("additionalProperties", Boolean.FALSE);
        nlp.put("inputSchema", nlpSchema);

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
        tools.add(nlp);
        tools.add(submit);
        tools.add(todo);
        tools.add(cli);
        tools.add(cfg);
        tools.add(isl);
        tools.add(sw);
        tools.add(lg);
        tools.add(st);
        tools.add(promptTool());
        tools.add(memoryTool());
        tools.add(appToolsListTool());
        tools.add(appToolCallTool());
        tools.add(appToolWrapper("xiaoai_alarms", "闹钟与倒计时：列出设备上已设置的闹钟或当前倒计时状态。"
                + "等价于 xiaoai_apptool{app=alarm,tool=deskclock_query_alarms}，只读。", emptySchema()));
        Map<String, Object> evProps = Json.obj();
        evProps.put("start", prop("string", "起始时间，例如「2026-11-23」或「2026-11-23 00:00:00」"));
        evProps.put("end", prop("string", "结束时间，格式同 start"));
        evProps.put("keyword", prop("string", "可选，按标题/地点过滤"));
        evProps.put("maxResults", prop("integer", "最多返回多少条，缺省 20"));
        tools.add(appToolWrapper("xiaoai_events", "查日程：按时间范围读日历（只读）。"
                + "等价于 xiaoai_apptool{app=calendar,tool=query_events}。", schema(evProps, list("start", "end"))));
        Map<String, Object> trProps = Json.obj();
        trProps.put("db_id", prop("integer", "录音的数据库 ID；给了就取这一条的转录"));
        trProps.put("q", prop("string", "不给 db_id 时按关键词搜索录音（空=最近录音）"));
        Map<String, Object> trLimit = prop("integer", "搜索返回条数，缺省 10，上限 50");
        trLimit.put("minimum", 1L);
        trLimit.put("maximum", 50L);
        trProps.put("limit", trLimit);
        tools.add(appToolWrapper("xiaoai_transcript", "录音转写：按 db_id 取转录全文，或按关键词搜索录音（只读）。"
                + "等价于 xiaoai_apptool{app=recorder,tool=get_transcription|search_recordings}。",
                schema(trProps, null)));
        Map<String, Object> wtProps = Json.obj();
        wtProps.put("city", prop("string", "城市名，例如「广州」"));
        Map<String, Object> wtAlert = prop("boolean", "true=查气象预警，false/缺省=查实时+预报");
        wtProps.put("alert", wtAlert);
        tools.add(appToolWrapper("xiaoai_weather", "天气：查指定城市的实时天气/预报或气象预警（只读）。"
                + "等价于 xiaoai_apptool{app=weather,tool=weather_get_city_weather|weather_get_city_alert}。",
                schema(wtProps, list("city"))));

        Map<String, Object> r = Json.obj();
        r.put("tools", tools);
        return r;
    }

    /** 按画像过滤后的工具表：readonly 只留入站白名单。 */
    public static Map<String, Object> toolsList() {
        return toolsList(PROFILE_FULL);
    }

    public static Map<String, Object> toolsList(String profile) {
        return filterTools(toolsListAll(), profile);
    }

    /** 按画像过滤：readonly 只留 {@link #INBOUND_ALLOWED}。 */
    static Map<String, Object> filterTools(Map<String, Object> all, String profile) {
        if (!PROFILE_READONLY.equals(profile) || all == null) return all;
        List<Object> kept = Json.arr();
        Object v = all.get("tools");
        if (v instanceof List) {
            for (Object t : (List<?>) v) {
                if (t instanceof Map && INBOUND_ALLOWED.contains(nameOf((Map<?, ?>) t))) {
                    kept.add(t);
                }
            }
        }
        Map<String, Object> r = Json.obj();
        r.put("tools", kept);
        return r;
    }

    private static String nameOf(Map<?, ?> tool) {
        Object n = tool.get("name");
        return n == null ? "" : String.valueOf(n);
    }

    /**
     * 单端口统一：本机（8771）工具 + 从 8772 转发来的动作面工具。
     *
     * <p>只追加归属为 {@code app} 的工具，并按名字去重；对端不可用时静默退化为
     * 只有能力桥工具，保证 8771 本身永远可用。</p>
     */
    public static Map<String, Object> toolsListMerged(Hosts hosts) {
        return toolsListMerged(hosts, PROFILE_FULL);
    }

    public static Map<String, Object> toolsListMerged(Hosts hosts, String profile) {
        Map<String, Object> r = toolsListAll();
        mergeAgentTools(r, hosts);
        return filterTools(r, profile);
    }

    /** 把 8772 的动作面工具并进 {@code r}；对端不可用时静默跳过。 */
    private static void mergeAgentTools(Map<String, Object> r, Hosts hosts) {
        if (hosts == null || hosts.agent == null) return;
        Object extra;
        try {
            extra = hosts.agent.tools();
        } catch (Throwable t) {
            return;
        }
        if (!(extra instanceof List)) return;
        Object mineObj = r.get("tools");
        if (!(mineObj instanceof List)) return;
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

    /** xiaoai_prompt：只读提示词配置与注入状态。 */
    private static Map<String, Object> promptTool() {
        Map<String, Object> t = Json.obj();
        t.put("name", "xiaoai_prompt");
        t.put("description", "读系统提示词配置与注入状态：开关 / 模式（append|replace）/ 正文 / "
                + "变量 / 记忆注入 / 最近是否命中。只返回配置与次数，不含对话内容。"
                + "修改提示词只能在手机 App「小爱 › 系统提示词」里做。");
        t.put("inputSchema", emptySchema());
        return t;
    }

    /** 通用 inputSchema：properties + 可选 required。 */
    private static Map<String, Object> schema(Map<String, Object> props, List<Object> required) {
        Map<String, Object> s = Json.obj();
        s.put("type", "object");
        s.put("properties", props);
        if (required != null && !required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", Boolean.FALSE);
        return s;
    }

    /** 便捷封装工具：只有名字、描述与 schema。 */
    private static Map<String, Object> appToolWrapper(String name, String description,
                                                      Map<String, Object> inputSchema) {
        Map<String, Object> t = Json.obj();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", inputSchema);
        return t;
    }

    /** xiaoai_apptools：列出可调用的 AppTool（白名单）。 */
    private static Map<String, Object> appToolsListTool() {
        Map<String, Object> props = Json.obj();
        props.put("app", prop("string", "可选，只看某个应用："
                + plus.xiaoi.module.core.apptool.AppToolCatalog.apps().toString()));
        return appToolWrapper("xiaoai_apptools",
                "列出可调用的系统应用工具（AppTool 白名单）：日历/闹钟/录音/相册/天气/笔记/电话等。"
                        + "每条带 app 别名、工具名、风险级与参数摘要；写类（risk=write）调用时需要 confirm=true。",
                schema(props, null));
    }

    /** xiaoai_apptool：按别名调用白名单里的任意工具。 */
    private static Map<String, Object> appToolCallTool() {
        Map<String, Object> props = Json.obj();
        props.put("app", prop("string", "应用别名，来自 xiaoai_apptools（alarm/calendar/notes/recorder/gallery/weather/phone/…）"));
        props.put("tool", prop("string", "工具名，来自 xiaoai_apptools"));
        props.put("args", prop("object", "工具参数对象，键名见 xiaoai_apptools 的 params"));
        props.put("confirm", prop("boolean", "写类工具（risk=write）必须为 true"));
        return appToolWrapper("xiaoai_apptool",
                "调用系统应用的某个 AppTool（跨包 bind，调用方是小爱）。"
                        + "只允许白名单里的 (app, tool) 组合；未知组合回 UNKNOWN_APPTOOL。"
                        + "写类工具要 confirm=true，否则回 NEEDS_CONFIRM。",
                schema(props, list("app", "tool")));
    }

    /** xiaoai_memory：记忆读写（写操作需在 App 内打开）。 */
    private static Map<String, Object> memoryTool() {
        Map<String, Object> t = Json.obj();
        t.put("name", "xiaoai_memory");
        t.put("description", "读写小爱记忆。op=get（默认）分节读本地 MEMORY.md；"
                + "op=official 转调小爱官方记忆检索（向量+关键词，返回官方口径）；"
                + "op=stats 读加密记忆库的行数与时间范围（不含正文）；"
                + "op=live 实时读加密库里的记忆条目（memory_meta，不经过 MEMORY.md）；"
                + "op=sync 读官方记忆库的同步状态（各表条数、syncStatus 分布、待同步行）；"
                + "op=append / op=replace 改本地文件（保留标题）；"
                + "op=official_write 交给官方抽取写入（自然语言，可能被改写）。"
                + "写操作需要先在 App 内打开「允许写记忆」；局域网来源还需打开「允许局域网写」。"
                + "每次本地写入前自动备份（保留 5 份）；不提供删除或清空。");
        Map<String, Object> props = Json.obj();
        Map<String, Object> op = prop("string",
                "操作：get / official / stats / live / sync / append / replace / official_write，缺省 get");
        List<Object> ops = Json.arr();
        ops.add("get");
        ops.add("official");
        ops.add("stats");
        ops.add("live");
        ops.add("sync");
        ops.add("append");
        ops.add("replace");
        ops.add("official_write");
        op.put("enum", ops);
        props.put("op", op);
        props.put("section", prop("string", "小节路径，例如「长期记忆/重要事实」；append/replace 必填"));
        props.put("text", prop("string",
                "要写入的内容，多行按条目拆分（上限 " + Contract.MEMORY_APPEND_MAX_BYTES + " 字节）"));
        Map<String, Object> limit = prop("integer",
                "op=get 每个小节的条数（缺省 50，上限 200）；op=live / op=sync 的条数（缺省 20，上限 200 / 100）");
        limit.put("minimum", 1L);
        limit.put("maximum", (long) Contract.MEMORY_GET_LIMIT_MAX);
        props.put("limit", limit);
        props.put("q", prop("string", "op=official 的检索关键词（空=最近记录）；"
                + "op=official_write 的自然语言内容（上限 " + Contract.OFFICIAL_MEMORY_QUERY_MAX + " 字）；"
                + "op=live 的关键词（匹配条目正文与键，空=不限）"));
        Map<String, Object> since = prop("integer", "op=live 的起始时间（epoch 毫秒），缺省 0=不限");
        since.put("minimum", 0L);
        props.put("since", since);
        Map<String, Object> page = prop("integer", "op=official 页码，缺省 1");
        page.put("minimum", 1L);
        props.put("page", page);
        Map<String, Object> pageSize = prop("integer", "op=official 每页条数，缺省 10，上限 50");
        pageSize.put("minimum", 1L);
        pageSize.put("maximum", 50L);
        props.put("pageSize", pageSize);
        Map<String, Object> schema = Json.obj();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("additionalProperties", Boolean.FALSE);
        t.put("inputSchema", schema);
        return t;
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
        return toolsCall(params, hosts, false, false);
    }

    static Map<String, Object> toolsCall(Map<?, ?> params, Hosts hosts, boolean lan, boolean lanWriteOk) {
        return toolsCall(params, hosts, lan, lanWriteOk, PROFILE_FULL);
    }

    static Map<String, Object> toolsCall(Map<?, ?> params, Hosts hosts, boolean lan,
                                         boolean lanWriteOk, String profile) {
        String name = params == null ? "" : Json.optString(params, "name", "");
        if (PROFILE_READONLY.equals(profile) && !INBOUND_ALLOWED.contains(name)) {
            return toolText("FORBIDDEN_TOOL: 入站（小爱）只允许 "
                    + INBOUND_ALLOWED + "；其余工具请由外部 Agent 直接调用", true);
        }
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
                if (hosts.appTool != null) {
                    String at = hosts.appTool.summary();
                    if (at != null && at.length() > 0) t = t + " " + at;
                }
                if (hosts.memory != null) {
                    String cdb = hosts.memory.clawProbe();
                    if (cdb != null && cdb.length() > 0) t = t + " " + cdb;
                }
                return toolText(t, false);
            }
            if ("xiaoai_nlp".equals(name)) {
                String bad = unexpectedArg(args, "text");
                if (bad != null) return toolText(bad, true);
                String text = Json.optString(args, "text", "").trim();
                if (text.length() == 0) return toolText("missing argument: text", true);
                if (text.length() > NLP_TEXT_MAX) {
                    return toolText("text too long (max " + NLP_TEXT_MAX + ")", true);
                }
                if (hosts.appTool == null) {
                    return toolText("APPTOOL_UNAVAILABLE：小爱进程内 AppTool 端口不可用", true);
                }
                Map<String, Object> q = Json.obj();
                q.put("query", text);
                String out = hosts.appTool.call("nlp_query", q);
                return toolText(out, failed(out));
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
            if ("xiaoai_prompt".equals(name)) {
                String bad = unexpectedArg(args);
                if (bad != null) return toolText(bad, true);
                if (hosts.memory == null) return memoryUnavailable();
                return toolText(hosts.memory.promptStatus(), false);
            }
            if ("xiaoai_memory".equals(name)) {
                return memoryToolCall(args, hosts, lan, lanWriteOk);
            }
            if ("xiaoai_apptools".equals(name)) {
                String bad = unexpectedArg(args, "app");
                if (bad != null) return toolText(bad, true);
                String app = Json.optString(args, "app", "").trim();
                if (app.length() > 0
                        && !plus.xiaoi.module.core.apptool.AppToolCatalog.hasApp(app)) {
                    return toolText("unknown app: " + app + "（可用："
                            + plus.xiaoi.module.core.apptool.AppToolCatalog.apps() + "）", true);
                }
                return toolText(appToolsJson(app), false);
            }
            if ("xiaoai_apptool".equals(name)) {
                return appToolCall(args, hosts);
            }
            if ("xiaoai_alarms".equals(name)) {
                String bad = unexpectedArg(args);
                if (bad != null) return toolText(bad, true);
                return appToolForward(hosts, "alarm", "deskclock_query_alarms", Json.obj());
            }
            if ("xiaoai_events".equals(name)) {
                String bad = unexpectedArg(args, "start", "end", "keyword", "maxResults");
                if (bad != null) return toolText(bad, true);
                String start = Json.optString(args, "start", "").trim();
                String end = Json.optString(args, "end", "").trim();
                if (start.length() == 0 || end.length() == 0) {
                    return toolText("missing argument: start / end（例如 2026-11-23）", true);
                }
                Map<String, Object> q = Json.obj();
                q.put("start_time", start);
                q.put("end_time", end);
                String kw = Json.optString(args, "keyword", "").trim();
                if (kw.length() > 0) q.put("keyword", kw);
                if (Json.has(args, "maxResults")) {
                    q.put("max_results", (long) Math.max(1, Json.optLong(args, "maxResults", 20L)));
                }
                return appToolForward(hosts, "calendar", "query_events", q);
            }
            if ("xiaoai_transcript".equals(name)) {
                String bad = unexpectedArg(args, "db_id", "q", "limit");
                if (bad != null) return toolText(bad, true);
                long dbId = Json.optLong(args, "db_id", 0L);
                if (dbId > 0L) {
                    Map<String, Object> q = Json.obj();
                    q.put("db_id", Long.valueOf(dbId));
                    return appToolForward(hosts, "recorder", "get_transcription", q);
                }
                Map<String, Object> q = Json.obj();
                String kw = Json.optString(args, "q", "").trim();
                if (kw.length() > 0) q.put("keyword", kw);
                long limit = Json.has(args, "limit") ? Json.optLong(args, "limit", 10L) : 10L;
                if (limit < 1L) limit = 1L;
                if (limit > 50L) limit = 50L;
                q.put("limit", Long.valueOf(limit));
                return appToolForward(hosts, "recorder", "search_recordings", q);
            }
            if ("xiaoai_weather".equals(name)) {
                String bad = unexpectedArg(args, "city", "alert");
                if (bad != null) return toolText(bad, true);
                String city = Json.optString(args, "city", "").trim();
                if (city.length() == 0) return toolText("missing argument: city", true);
                Map<String, Object> q = Json.obj();
                q.put("city", city);
                String tool = Json.optBoolean(args, "alert", false)
                        ? "weather_get_city_alert" : "weather_get_city_weather";
                return appToolForward(hosts, "weather", tool, q);
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
     * xiaoai_memory 的参数校验与分发。
     *
     * <p>写操作先看 {@link MemoryPort#writable()}：{@code memoryWriteEnabled=false}
     * 时直接拒绝，绝不落到文件层（默认关的开关不能靠调用方绕过）。</p>
     */
    private static Map<String, Object> memoryToolCall(Map<?, ?> args, Hosts hosts,
                                                          boolean lan, boolean lanWriteOk) {
        String bad = unexpectedArg(args, "op", "section", "text", "limit", "q", "page",
                "pageSize", "since");
        if (bad != null) return toolText(bad, true);
        if (hosts.memory == null) return memoryUnavailable();

        String op = Json.optString(args, "op", "get").trim().toLowerCase(java.util.Locale.US);
        if (op.length() == 0) op = "get";
        if ("get".equals(op)) {
            int limit = Json.has(args, "limit")
                    ? (int) Json.optLong(args, "limit", Contract.MEMORY_GET_LIMIT_DEFAULT)
                    : Contract.MEMORY_GET_LIMIT_DEFAULT;
            if (limit < 1) limit = 1;
            if (limit > Contract.MEMORY_GET_LIMIT_MAX) limit = Contract.MEMORY_GET_LIMIT_MAX;
            String text = hosts.memory.memoryOverview(limit);
            return toolText(text, failed(text));
        }
        if ("official".equals(op)) {
            int page = Json.has(args, "page") ? (int) Json.optLong(args, "page", 1) : 1;
            int size = Json.has(args, "pageSize") ? (int) Json.optLong(args, "pageSize", 10) : 10;
            if (page < 1) page = 1;
            if (size < 1) size = 10;
            if (size > 50) size = 50;
            String text = hosts.memory.officialRead(Json.optString(args, "q", "").trim(), page, size);
            return toolText(text, failed(text));
        }
        if ("official_write".equals(op)) {
            String q = Json.optString(args, "q", "").trim();
            if (q.length() == 0) return toolText("missing argument: q", true);
            if (q.length() > Contract.OFFICIAL_MEMORY_QUERY_MAX) {
                return toolText("q too long (max " + Contract.OFFICIAL_MEMORY_QUERY_MAX + ")", true);
            }
            String lower = q.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("忘掉") || lower.contains("忘记") || lower.contains("删除")
                    || lower.contains("清空") || lower.contains("forget")) {
                return toolText("official_write 不支持删除类指令（本模块不提供删除入口）", true);
            }
            String denied = writeDenied(hosts, lan, lanWriteOk);
            if (denied != null) return toolText(denied, true);
            String text = hosts.memory.officialWrite(q);
            return toolText(text, failed(text));
        }
        if ("stats".equals(op)) {
            String text = hosts.memory.clawStats();
            return toolText(text, failed(text));
        }
        if ("live".equals(op)) {
            int limit = Json.has(args, "limit")
                    ? (int) Json.optLong(args, "limit", Contract.MEMORY_LIVE_LIMIT_DEFAULT)
                    : Contract.MEMORY_LIVE_LIMIT_DEFAULT;
            if (limit < 1) limit = 1;
            if (limit > Contract.MEMORY_LIVE_LIMIT_MAX) limit = Contract.MEMORY_LIVE_LIMIT_MAX;
            long since = Json.has(args, "since") ? Json.optLong(args, "since", 0L) : 0L;
            if (since < 0L) since = 0L;
            String text = hosts.memory.clawLive(Json.optString(args, "q", "").trim(), since, limit);
            return toolText(text, failed(text));
        }
        if ("sync".equals(op)) {
            int limit = Json.has(args, "limit")
                    ? (int) Json.optLong(args, "limit", Contract.MEMORY_LIVE_LIMIT_DEFAULT)
                    : Contract.MEMORY_LIVE_LIMIT_DEFAULT;
            if (limit < 1) limit = 1;
            if (limit > Contract.MEMORY_SYNC_LIMIT_MAX) limit = Contract.MEMORY_SYNC_LIMIT_MAX;
            String text = hosts.memory.officialSync(limit);
            return toolText(text, failed(text));
        }
        if (!"append".equals(op) && !"replace".equals(op)) {
            return toolText("unknown op: " + op
                    + "（可用：get, append, replace, official, official_write, stats, live, sync）", true);
        }
        String denied = writeDenied(hosts, lan, lanWriteOk);
        if (denied != null) return toolText(denied, true);
        String section = Json.optString(args, "section", "").trim();
        if (section.length() == 0) return toolText("missing argument: section", true);
        if (section.length() > Contract.MEMORY_SECTION_MAX) {
            return toolText("section too long (max " + Contract.MEMORY_SECTION_MAX + ")", true);
        }
        String text = Json.optString(args, "text", "");
        if (text.trim().length() == 0) return toolText("missing argument: text", true);
        if (utf8Length(text.trim()) > Contract.MEMORY_APPEND_MAX_BYTES) {
            return toolText("text too large (max " + Contract.MEMORY_APPEND_MAX_BYTES + " bytes)", true);
        }
        String out = "append".equals(op)
                ? hosts.memory.memoryAppend(section, text)
                : hosts.memory.memoryReplace(section, text);
        return toolText(out, failed(out));
    }

    /** 白名单目录 → JSON（供 xiaoai_apptools）。 */
    private static String appToolsJson(String app) {
        List<Object> appsOut = Json.arr();
        int total = 0;
        List<String> apps = app.length() > 0
                ? java.util.Collections.singletonList(app)
                : plus.xiaoi.module.core.apptool.AppToolCatalog.apps();
        for (String alias : apps) {
            List<plus.xiaoi.module.core.apptool.AppToolCatalog.Entry> entries =
                    plus.xiaoi.module.core.apptool.AppToolCatalog.byApp(alias);
            if (entries.isEmpty()) continue;
            Map<String, Object> item = Json.obj();
            item.put("app", alias);
            item.put("pkg", plus.xiaoi.module.core.apptool.AppToolCatalog.packageOf(alias));
            List<Object> toolsOut = Json.arr();
            for (plus.xiaoi.module.core.apptool.AppToolCatalog.Entry e : entries) {
                Map<String, Object> t = Json.obj();
                t.put("tool", e.tool);
                t.put("risk", e.risk);
                t.put("params", e.params);
                toolsOut.add(t);
                total++;
            }
            item.put("tools", toolsOut);
            appsOut.add(item);
        }
        Map<String, Object> out = Json.obj();
        out.put("ok", Boolean.TRUE);
        out.put("count", (long) total);
        out.put("apps", appsOut);
        return Json.write(out);
    }

    /** xiaoai_apptool 的实现：白名单 + 风险闸门 + 跨包调用。 */
    private static Map<String, Object> appToolCall(Map<?, ?> args, Hosts hosts) {
        String bad = unexpectedArg(args, "app", "tool", "args", "confirm");
        if (bad != null) return toolText(bad, true);
        String app = Json.optString(args, "app", "").trim();
        String tool = Json.optString(args, "tool", "").trim();
        if (app.length() == 0) return toolText("missing argument: app", true);
        if (tool.length() == 0) return toolText("missing argument: tool", true);
        if (hosts.appTool == null) {
            return toolText("APPTOOL_UNAVAILABLE：小爱进程内 AppTool 端口不可用", true);
        }
        plus.xiaoi.module.core.apptool.AppToolCatalog.Entry entry =
                plus.xiaoi.module.core.apptool.AppToolCatalog.find(app, tool);
        if (entry == null) {
            return toolText("UNKNOWN_APPTOOL：" + app + "/" + tool + " 不在白名单"
                    + "（先调 xiaoai_apptools 看可用组合）", true);
        }
        if (entry.writable() && !Json.optBoolean(args, "confirm", false)) {
            return toolText("NEEDS_CONFIRM：" + app + "/" + tool + " 会改系统状态，"
                    + "确认后加 \"confirm\":true 再调用", true);
        }
        Map<String, Object> callArgs = Json.obj();
        Object raw = args.get("args");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() instanceof String) callArgs.put((String) e.getKey(), e.getValue());
            }
        }
        return forwardCatalog(hosts, app, tool, callArgs);
    }

    /** 便捷封装走的统一出口。 */
    private static Map<String, Object> appToolForward(Hosts hosts, String app, String tool,
                                                      Map<String, Object> callArgs) {
        if (hosts.appTool == null) {
            return toolText("APPTOOL_UNAVAILABLE：小爱进程内 AppTool 端口不可用", true);
        }
        return forwardCatalog(hosts, app, tool, callArgs);
    }

    private static Map<String, Object> forwardCatalog(Hosts hosts, String app, String tool,
                                                      Map<String, Object> callArgs) {
        String pkg = plus.xiaoi.module.core.apptool.AppToolCatalog.packageOf(app);
        String svc = plus.xiaoi.module.core.apptool.AppToolCatalog.serviceOf(app);
        if (pkg == null || svc == null) {
            return toolText("UNKNOWN_APPTOOL：" + app, true);
        }
        String out = hosts.appTool.callExternal(pkg, svc, tool, callArgs, APPTOOL_EXT_TIMEOUT_MS);
        return toolText(out, failed(out));
    }

    /** 写操作的两道闸：本机写开关 + 局域网写开关；通过返回 null。 */
    private static String writeDenied(Hosts hosts, boolean lan, boolean lanWriteOk) {
        if (!hosts.memory.writable()) {
            return "memoryWriteEnabled=false：请在手机 App「小爱 › 记忆」里打开"
                    + "「允许写记忆」后再写（默认关闭）";
        }
        if (lan && !lanWriteOk) {
            return "DENIED_LAN: 局域网来源的写操作需要额外请求头 "
                    + "`X-Xiaoi-Write: <同一个 Bearer token>`（二次鉴权）。只读操作不受影响";
        }
        return null;
    }

    /** UTF-8 字节数；编码不可用按最坏情况估算（宁可拒绝也不越界）。 */
    private static int utf8Length(String s) {
        try {
            return s.getBytes("UTF-8").length;
        } catch (Throwable t) {
            return s.length() * 3;
        }
    }

    /** 端口返回的 JSON 里 {@code ok=false} → 工具层置 isError。 */
    static boolean failed(String json) {
        if (json == null || json.length() == 0) return true;
        try {
            Object v = Json.read(json);
            // 系统 AppTool 会直接返回数组（闹钟列表）或纯文本（「已删除闹钟 4」），
            // 只有我们自己的结构化 JSON 才用 ok=false 表示失败。
            if (!(v instanceof Map)) return false;
            Object ok = ((Map<?, ?>) v).get("ok");
            return Boolean.FALSE.equals(ok);
        } catch (Throwable t) {
            // 非 JSON 文本：provider 已经给出答复，不算失败。
            return false;
        }
    }

    private static Map<String, Object> memoryUnavailable() {
        return toolText("提示词/记忆接口不可用（需要小爱进程运行，且 Application 已就绪）", true);
    }

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
