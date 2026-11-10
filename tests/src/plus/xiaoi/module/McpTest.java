package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.AppToolProtocol;

/**
 * MCP 协议层测试：FakeHost 覆盖全部方法与工具。
 * 黄金串断言锁定 v0.7 的逐字节输出（错误码、文案、字段顺序），
 * 这是「拆分不改变行为」的核心凭证之一（另一部分是真机契约基线）。
 */
public final class McpTest {

    private McpTest() {}

    // ------------------------------------------------------------ FakeHost

    private static final class FakeHost implements Mcp.Host {
        String version = "9.9.9";
        boolean facade = true;
        boolean cli = true;
        int port = 8771;
        String cfgBase = "https://api.deepseek.com/v1";
        String cfgKey = "sk-test";
        String cfgModel = "deepseek-chat";
        boolean cfgMarker = true;
        boolean cfgBridge = true;
        String cfgToken = "tok-1";

        final List<String> cliCalls = new ArrayList<String>();
        final Map<String, String> cliResponses = new LinkedHashMap<String, String>();
        Mcp.Snapshot written;
        boolean writeOk = true;
        String islandText;
        boolean islandForce;
        boolean islandOk = true;
        int logsLimit = -1;
        long logsSince = -1;
        int statsDays = -1;
        AppToolProtocol.Result todoResult = AppToolProtocol.ok("{\"noteId\":\"n1\"}");
        String todoTitle;
        String todoContent;
        int todoCalls;
        boolean todoThrow;

        public boolean facadeReady() { return facade; }
        public boolean cliReady() { return cli; }
        public int port() { return port; }
        public String versionName() { return version; }

        public String callCli(String cmd) {
            cliCalls.add(cmd);
            if (!cli) return null;
            Object v;
            try { v = Json.read(cmd); } catch (Throwable t) { return "{}"; }
            String method = v instanceof Map ? Json.optString((Map<?, ?>) v, "method", "") : "";
            String canned = cliResponses.get(method);
            return canned != null ? canned : "{}";
        }

        public Mcp.Snapshot config() {
            return new Mcp.Snapshot(cfgBase, cfgKey, cfgModel, cfgMarker, cfgBridge, cfgToken);
        }

        public boolean writeConfig(Mcp.Snapshot next) {
            written = next;
            return writeOk;
        }

        public boolean showIsland(String text, boolean force) {
            islandText = text;
            islandForce = force;
            return islandOk;
        }

        public String logs(int limit, long since) {
            logsLimit = limit;
            logsSince = since;
            return "{\"count\":0,\"lastSeq\":0,\"lines\":[]}";
        }

        public String stats(int days) {
            statsDays = days;
            return "{\"days\":" + days + ",\"today\":{\"third\":2,\"official\":1}}";
        }

        public AppToolProtocol.Result createTodo(String title, String content) {
            todoCalls++;
            todoTitle = title;
            todoContent = content;
            if (todoThrow) throw new IllegalStateException("boom");
            return todoResult;
        }
    }

    // ------------------------------------------------------------ 测试

    public static int run() {
        FakeHost h = new FakeHost();
        h.cliResponses.put("session.open", "{\"id\":\"mcp\",\"result\":{\"sessionId\":\"sid-1\"}}");
        h.cliResponses.put("agent.submit", "好的，收到");

        // ------------------------------------------------------------ 协议框架（黄金串）
        eq("initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                        + "\"serverInfo\":{\"name\":\"xiaoi-plus\",\"version\":\"9.9.9\"},"
                        + "\"instructions\":\"小爱同学（手机端）能力桥：xiaoai_submit 让小爱执行一句话任务，"
                        + "xiaoai_status 探活，xiaoai_cli 走原始 CLI，xiaoai_stats 看三方/官方模型调用量。"
                        + "端点与密钥只能在手机 App 内修改；远程只能切换已有端点的 model/marker。\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", h));

        eq("ping", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", h));

        eq("ping string id", "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}", h));

        eq("empty body", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"empty body\"}}",
                Mcp.handle("", h));

        eq("parse error", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}",
                Mcp.handle("this is not json", h));

        eq("non-object body is parse error",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}",
                Mcp.handle("[1,2]", h));

        eq("missing method", "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32600,\"message\":\"missing method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":9}", h));

        eq("unknown method", "{\"jsonrpc\":\"2.0\",\"id\":8,\"error\":{\"code\":-32601,\"message\":\"method not found: no/such/method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"no/such/method\"}", h));

        eq("notification returns null", null,
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", h));

        eq("explicit null id is notification", null,
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"notifications/initialized\"}", h));

        eq("missing method before notification check",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"missing method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":null}", h));

        // ------------------------------------------------------------ tools/list（结构 + 独立转录的描述）
        String out = Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", h);
        Object v = Json.read(out);
        Map<?, ?> root = (Map<?, ?>) v;
        eq("frame jsonrpc", "2.0", root.get("jsonrpc"));
        eq("frame id", Long.valueOf(3), root.get("id"));
        List<?> tools = (List<?>) ((Map<?, ?>) root.get("result")).get("tools");
        eq("tool count", 9, tools.size());
        eq("tool order", "xiaoai_status,xiaoai_submit,xiaoai_todo_create,xiaoai_cli,xiaoai_config,xiaoai_island,xiaoai_switch_model,xiaoai_logs,xiaoai_stats",
                names(tools));

        Map<?, ?> submit = (Map<?, ?>) tools.get(1);
        eq("submit description independent transcription",
                "让小爱（手机端语音助手）执行一句话任务，返回它的回复文本。不传 sessionId 时自动开新会话。",
                submit.get("description"));
        Map<?, ?> submitSchema = (Map<?, ?>) submit.get("inputSchema");
        eq("submit required", "[text]", ((List<?>) submitSchema.get("required")).toString());
        eq("submit additionalProperties", Boolean.FALSE, submitSchema.get("additionalProperties"));
        eq("submit props", "[text, sessionId]",
                ((Map<?, ?>) submitSchema.get("properties")).keySet().toString());

        Map<?, ?> todo = (Map<?, ?>) tools.get(2);
        ok("todo description says deterministic/no LLM",
                ((String) todo.get("description")).contains("不经过 LLM"));
        ok("todo description recommends submit fallback",
                ((String) todo.get("description")).contains("xiaoai_submit"));
        Map<?, ?> todoSchema = (Map<?, ?>) todo.get("inputSchema");
        eq("todo required", "[title]", ((List<?>) todoSchema.get("required")).toString());
        eq("todo props", "[title, content]",
                ((Map<?, ?>) todoSchema.get("properties")).keySet().toString());
        eq("todo additionalProperties", Boolean.FALSE, todoSchema.get("additionalProperties"));

        Map<?, ?> sw = (Map<?, ?>) tools.get(6);
        Map<?, ?> swSchema = (Map<?, ?>) sw.get("inputSchema");
        eq("switch required", "[model]", ((List<?>) swSchema.get("required")).toString());
        eq("switch props", "[model, marker]",
                ((Map<?, ?>) swSchema.get("properties")).keySet().toString());

        Map<?, ?> lg = (Map<?, ?>) tools.get(7);
        ok("logs schema has no required",
                !((Map<?, ?>) lg.get("inputSchema")).containsKey("required"));

        String toolText = Mcp.toolListText();
        ok("工具清单含 xiaoai_stats", toolText.contains("xiaoai_stats"));
        ok("工具清单含 xiaoai_todo_create", toolText.contains("xiaoai_todo_create"));
        eq("工具清单行数", 9, toolText.split("\n").length);

        Map<?, ?> statsTool = (Map<?, ?>) tools.get(8);
        eq("stats props", "[days]",
                ((Map<?, ?>) ((Map<?, ?>) statsTool.get("inputSchema")).get("properties")).keySet().toString());

        Map<?, ?> st = (Map<?, ?>) tools.get(0);
        eq("status schema empty props", "{}",
                ((Map<?, ?>) ((Map<?, ?>) st.get("inputSchema")).get("properties")).toString());

        // ------------------------------------------------------------ tools/call：status
        eq("xiaoai_status",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"facade=true cli=true port=8771 version=9.9.9\"}],\"isError\":false}}",
                call(h, 4, "xiaoai_status", null));

        // ------------------------------------------------------------ tools/call：submit（自动开会话）
        h.cliCalls.clear();
        eq("xiaoai_submit",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"好的，收到\"}],\"isError\":false}}",
                call(h, 7, "xiaoai_submit", "{\"text\":\"打开支付宝\"}"));
        eq("submit calls session.open", "{\"id\":\"mcp\",\"method\":\"session.open\",\"params\":{}}",
                h.cliCalls.get(0));
        eq("submit calls agent.submit",
                "{\"id\":\"mcp\",\"method\":\"agent.submit\",\"params\":{\"sessionId\":\"sid-1\",\"text\":\"打开支付宝\"}}",
                h.cliCalls.get(1));

        // sessionId 透传则不再开新会话
        h.cliCalls.clear();
        call(h, 7, "xiaoai_submit", "{\"text\":\"再说一次\",\"sessionId\":\"sid-9\"}");
        eq("submit reuses session", "{\"id\":\"mcp\",\"method\":\"agent.submit\",\"params\":{\"sessionId\":\"sid-9\",\"text\":\"再说一次\"}}",
                h.cliCalls.get(0));

        eq("submit missing text",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: text\"}],\"isError\":true}}",
                call(h, 7, "xiaoai_submit", null));

        // session.open 失败 → 错误并带摘要
        h.cliResponses.put("session.open", "ERR boom");
        eq("submit session.open failed",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"session.open failed: ERR boom\"}],\"isError\":true}}",
                call(h, 7, "xiaoai_submit", "{\"text\":\"hi\"}"));

        // CLI 未就绪
        h.cli = false;
        eq("submit cli not ready",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"ERR cli service not ready\"}],\"isError\":true}}",
                call(h, 7, "xiaoai_submit", "{\"text\":\"hi\",\"sessionId\":\"s\"}"));
        h.cli = true;

        // ------------------------------------------------------------ tools/call：todo_create（确定性）
        h.todoCalls = 0;
        h.todoResult = AppToolProtocol.ok("{\"noteId\":\"n1\"}");
        eq("xiaoai_todo_create default content",
                "{\"jsonrpc\":\"2.0\",\"id\":30,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"noteId\\\":\\\"n1\\\"}\"}],\"isError\":false}}",
                call(h, 30, "xiaoai_todo_create", "{\"title\":\"买牛奶\"}"));
        eq("todo title passed", "买牛奶", h.todoTitle);
        eq("todo default content passed", "- [ ] 买牛奶", h.todoContent);

        call(h, 31, "xiaoai_todo_create",
                "{\"title\":\"购物\",\"content\":\"- [ ] 牛奶\\n- [ ] 鸡蛋\"}");
        eq("todo explicit content preserved", "- [ ] 牛奶\n- [ ] 鸡蛋", h.todoContent);

        call(h, 32, "xiaoai_todo_create", "{\"title\":\" 空白 \",\"content\":\"   \"}");
        eq("todo blank content defaults", "- [ ] 空白", h.todoContent);

        eq("todo missing title",
                "{\"jsonrpc\":\"2.0\",\"id\":33,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: title\"}],\"isError\":true}}",
                call(h, 33, "xiaoai_todo_create", "{\"content\":\"- [ ] x\"}"));

        h.todoResult = AppToolProtocol.error("CALL_TIMEOUT", "handleRequest 超过 5000ms");
        String todoErr = call(h, 34, "xiaoai_todo_create", "{\"title\":\"超时\"}");
        ok("todo provider error isError", todoErr.contains("\"isError\":true"));
        ok("todo provider error structured", textOf(todoErr).contains("\"error\":\"CALL_TIMEOUT\""));

        h.todoThrow = true;
        String todoThrowOut = call(h, 35, "xiaoai_todo_create", "{\"title\":\"异常\"}");
        ok("todo host exception isError", todoThrowOut.contains("\"isError\":true"));
        ok("todo host exception structured", textOf(todoThrowOut).contains("\"error\":\"HOST_ERROR\""));
        h.todoThrow = false;
        h.todoResult = AppToolProtocol.ok("{\"noteId\":\"n1\"}");

        // ------------------------------------------------------------ tools/call：cli / logs / island
        h.cliResponses.put("auth.status", "AUTH-OK");
        eq("xiaoai_cli",
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"AUTH-OK\"}],\"isError\":false}}",
                call(h, 11, "xiaoai_cli", "{\"cmd\":\"{\\\"id\\\":\\\"1\\\",\\\"method\\\":\\\"auth.status\\\"}\"}"));

        call(h, 12, "xiaoai_logs", "{\"limit\":5,\"since\":100}");
        eq("xiaoai_logs passes limit", 5, h.logsLimit);
        eq("xiaoai_logs passes since", 100L, h.logsSince);
        call(h, 12, "xiaoai_logs", null);
        eq("xiaoai_logs default limit", 80, h.logsLimit);
        eq("xiaoai_logs default since", 0L, h.logsSince);

        call(h, 14, "xiaoai_stats", null);
        eq("xiaoai_stats default days", 7, h.statsDays);
        ok("xiaoai_stats returns today buckets", textOf(call(h, 14, "xiaoai_stats", null))
                .contains("\"today\":{\"third\":2,\"official\":1}"));
        call(h, 14, "xiaoai_stats", "{\"days\":3}");
        eq("xiaoai_stats honors days", 3, h.statsDays);
        call(h, 14, "xiaoai_stats", "{\"days\":999}");
        eq("xiaoai_stats clamps days", 7, h.statsDays);

        call(h, 13, "xiaoai_island", null);
        eq("island default text", "deepseek-chat", h.islandText);
        eq("island default force", true, h.islandForce);
        call(h, 13, "xiaoai_island", "{\"text\":\"自定义\",\"force\":false}");
        eq("island custom text", "自定义", h.islandText);
        eq("island custom force", false, h.islandForce);

        // ------------------------------------------------------------ tools/call：config
        eq("xiaoai_config",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"model\\\":\\\"deepseek-chat\\\",\\\"baseUrl\\\":\\\"https://api.deepseek.com/v1\\\","
                        + "\\\"enabled\\\":true,\\\"marker\\\":true,\\\"markerTemplate\\\":\\\"\\\",\\\"bridge\\\":true,\\\"lan\\\":false,\\\"apiKeySet\\\":true,\\\"tokenSet\\\":true,"
                        + "\\\"port\\\":8771,\\\"version\\\":\\\"9.9.9\\\"}\"}],\"isError\":false}}",
                call(h, 5, "xiaoai_config", null));

        // ------------------------------------------------------------ tools/call：switch_model
        eq("switch_model model only",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"ok\\\":true,\\\"model\\\":\\\"m2\\\",\\\"baseUrl\\\":\\\"https://api.deepseek.com/v1\\\","
                        + "\\\"apiKeySet\\\":true,\\\"changed\\\":true}\"}],\"isError\":false}}",
                call(h, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        eq("switch_model keeps baseUrl", "https://api.deepseek.com/v1", h.written.baseUrl);
        eq("switch_model keeps apiKey", "sk-test", h.written.apiKey);
        eq("switch_model keeps token", "tok-1", h.written.token);

        String swOut = call(h, 10, "xiaoai_switch_model", "{\"model\":\"deepseek-chat\"}");
        String swText = textOf(swOut);
        eq("switch_model same values changed=false", Boolean.FALSE,
                ((Map<?, ?>) Json.read(swText)).get("changed"));

        // WP4 D10：baseUrl/apiKey/bridge/lan 一律拒绝，且不写配置
        h.written = null;
        eq("switch_model rejects baseUrl",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"端点和密钥只能在手机 App 设置；xiaoai_switch_model 只接受 model / marker（baseUrl 已拒绝）\"}],\"isError\":true}}",
                call(h, 10, "xiaoai_switch_model",
                        "{\"model\":\"m2\",\"baseUrl\":\"https://x/\",\"apiKey\":\"k2\",\"marker\":false,\"bridge\":false}"));
        ok("switch_model 拒绝后未写配置", h.written == null);

        String[] banned = {"apiKey", "bridge", "lan", "token", "enabled", "markerTemplate"};
        for (String key : banned) {
            h.written = null;
            String rejOut = call(h, 10, "xiaoai_switch_model",
                    "{\"model\":\"m2\",\"" + key + "\":\"x\"}");
            ok("switch_model 拒绝 " + key, textOf(rejOut).contains("isError")
                    || rejOut.contains("\"isError\":true"));
            ok("switch_model " + key + " 未写配置", h.written == null);
        }

        // marker 是允许的
        h.written = null;
        call(h, 10, "xiaoai_switch_model", "{\"model\":\"m2\",\"marker\":false}");
        ok("switch_model 允许 marker", h.written != null && !h.written.marker);
        eq("switch_model 保留 baseUrl", "https://api.deepseek.com/v1", h.written.baseUrl);
        eq("switch_model 保留 apiKey", "sk-test", h.written.apiKey);
        eq("switch_model 保留 token", "tok-1", h.written.token);
        eq("switch_model 保留 bridge", true, h.written.bridge);

        eq("switch_model no model",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: model\"}],\"isError\":true}}",
                call(h, 10, "xiaoai_switch_model", "{\"marker\":false}"));

        h.cfgBase = "";
        eq("switch_model no endpoint",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: baseUrl（当前没有可用端点，请在手机 App 设置）\"}],\"isError\":true}}",
                call(h, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        h.cfgBase = "https://api.deepseek.com/v1";

        h.writeOk = false;
        eq("switch_model write failed",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"write prefs failed\"}],\"isError\":true}}",
                call(h, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        h.writeOk = true;

        // ------------------------------------------------------------ 未知工具与参数形态
        eq("unknown tool",
                "{\"jsonrpc\":\"2.0\",\"id\":20,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"unknown tool: zzz\"}],\"isError\":true}}",
                call(h, 20, "zzz", null));

        eq("tools/call without params",
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"unknown tool: \"}],\"isError\":true}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\"}", h));

        eq("tools/call non-object arguments",
                "{\"jsonrpc\":\"2.0\",\"id\":22,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: text\"}],\"isError\":true}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"xiaoai_submit\",\"arguments\":\"x\"}}", h));

        // ------------------------------------------------------------ 参数白名单
        ok("rejectedSwitchArg null", Mcp.rejectedSwitchArg(null) == null);
        ok("rejectedSwitchArg model/marker",
                Mcp.rejectedSwitchArg((Map<?, ?>) Json.read("{\"model\":\"m\",\"marker\":true}")) == null);
        ok("rejectedSwitchArg baseUrl",
                Mcp.rejectedSwitchArg((Map<?, ?>) Json.read("{\"baseUrl\":\"x\"}"))
                        .contains("端点和密钥只能在手机 App 设置"));

        return T.report("McpTest");
    }

    /** 便捷：构造 tools/call 报文并取响应。arguments 为 null 时不带 arguments 字段。 */
    private static String call(Mcp.Host h, int id, String name, String argsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"").append(name).append("\"");
        if (argsJson != null) {
            sb.append(",\"arguments\":").append(argsJson);
        }
        sb.append("}}");
        return Mcp.handle(sb.toString(), h);
    }

    /** 取 tools/call 响应里 content[0].text（text 内嵌 JSON）。 */
    private static String textOf(String rpcOut) {
        Object v = Json.read(rpcOut);
        Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) v).get("result");
        List<?> content = (List<?>) result.get("content");
        return (String) ((Map<?, ?>) content.get(0)).get("text");
    }

    private static String names(List<?> tools) {
        StringBuilder sb = new StringBuilder();
        for (Object t : tools) {
            if (sb.length() > 0) sb.append(',');
            sb.append(((Map<?, ?>) t).get("name"));
        }
        return sb.toString();
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
