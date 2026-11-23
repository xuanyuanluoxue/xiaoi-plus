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

    private static final class FakeHost implements Mcp.RuntimePort, Mcp.ConfigPort, Mcp.CliPort, Mcp.IslandPort, Mcp.TelemetryPort {
        String version = "9.9.9";
        boolean facade = true;
        boolean cli = true;
        int port = 8771;
        String hookSummary = "";
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
        int toolCalls;
        String lastTool = "";
        boolean lastOk;
        int lastArgs = -1;
        long lastElapsed = -1;

        public void onToolCall(String tool, boolean ok, long elapsedMs, int argCount) {
            toolCalls++;
            lastTool = tool;
            lastOk = ok;
            lastArgs = argCount;
            lastElapsed = elapsedMs;
        }

        public boolean facadeReady() { return facade; }
        public boolean cliReady() { return cli; }
        public int port() { return port; }
        public String versionName() { return version; }
        public String hookSummary() { return hookSummary; }

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

        /** 同一实例填入五个端口，保持 telemetry 计数与 CLI 响应共享。 */
        Mcp.Hosts hosts() {
            return new Mcp.Hosts(this, this, this, this, this);
        }
    }

    // ------------------------------------------------------------ 测试

    public static int run() {
        FakeHost h = new FakeHost();
        Mcp.Hosts hs = h.hosts();
        h.cliResponses.put("session.open", "{\"id\":\"mcp\",\"result\":{\"sessionId\":\"sid-1\"}}");
        h.cliResponses.put("agent.submit", "好的，收到");

        // ------------------------------------------------------------ 协议框架（黄金串）
        eq("initialize", "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\","
                        + "\"capabilities\":{\"tools\":{\"listChanged\":false}},"
                        + "\"serverInfo\":{\"name\":\"xiaoi-plus\",\"version\":\"9.9.9\"},"
                        + "\"instructions\":\"小爱同学+ 统一 MCP 端点（8771，唯一端口）：18 个工具都从这里调用"
                        + "（桥 12 + 设备动作 6，设备动作经控制面转发到 App 进程执行）。"
                        + "xiaoai_submit 让小爱执行一句话任务，xiaoai_nlp 走小爱语义引擎，"
                        + "xiaoai_status 探活，xiaoai_cli 走原始 CLI，"
                        + "xiaoai_stats 看三方/官方模型调用量，xiaoai_prompt 看系统提示词状态，"
                        + "xiaoai_memory 读写记忆（写需在 App 内开启）。"
                        + "端点与密钥只能在手机 App 内修改；远程只能切换已有端点的 model/marker。\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}", hs));

        ok("initialize 回显 2024-11-05",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}", hs)
                        .contains("\"protocolVersion\":\"2024-11-05\""));
        ok("initialize 未支持版本回落到 2025-03-26",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}", hs)
                        .contains("\"protocolVersion\":\"2025-03-26\""));

        eq("ping", "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", hs));

        eq("ping string id", "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"result\":{}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}", hs));

        eq("empty body", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"empty body\"}}",
                Mcp.handle("", hs));

        eq("parse error", "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}",
                Mcp.handle("this is not json", hs));

        eq("non-object body is parse error",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse error\"}}",
                Mcp.handle("[1,2]", hs));

        eq("missing method", "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32600,\"message\":\"missing method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":9}", hs));

        eq("unknown method", "{\"jsonrpc\":\"2.0\",\"id\":8,\"error\":{\"code\":-32601,\"message\":\"method not found: no/such/method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"no/such/method\"}", hs));

        eq("notification returns null", null,
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", hs));

        eq("explicit null id is notification", null,
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"notifications/initialized\"}", hs));

        eq("missing method before notification check",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32600,\"message\":\"missing method\"}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":null}", hs));

        // ------------------------------------------------------------ tools/list（结构 + 独立转录的描述）
        String out = Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}", hs);
        Object v = Json.read(out);
        Map<?, ?> root = (Map<?, ?>) v;
        eq("frame jsonrpc", "2.0", root.get("jsonrpc"));
        eq("frame id", Long.valueOf(3), root.get("id"));
        List<?> tools = (List<?>) ((Map<?, ?>) root.get("result")).get("tools");
        eq("tool count", 12, tools.size());
        eq("tool order", "xiaoai_status,xiaoai_nlp,xiaoai_submit,xiaoai_todo_create,xiaoai_cli,xiaoai_config,xiaoai_island,xiaoai_switch_model,xiaoai_logs,xiaoai_stats,xiaoai_prompt,xiaoai_memory",
                names(tools));

        Map<?, ?> submit = (Map<?, ?>) tools.get(2);
        eq("submit description independent transcription",
                "让小爱（手机端语音助手）执行一句话任务。默认返回它的回复文本；"
                        + "structured=true 时返回 {\"ok\",\"text\",\"sessionId\"}，可用 sessionId 续聊。"
                        + "不传 sessionId 时自动开新会话。",
                submit.get("description"));
        Map<?, ?> submitSchema = (Map<?, ?>) submit.get("inputSchema");
        eq("submit required", "[text]", ((List<?>) submitSchema.get("required")).toString());
        eq("submit additionalProperties", Boolean.FALSE, submitSchema.get("additionalProperties"));
        eq("submit props", "[text, sessionId, structured]",
                ((Map<?, ?>) submitSchema.get("properties")).keySet().toString());

        Map<?, ?> todo = (Map<?, ?>) tools.get(3);
        ok("todo description says deterministic/no LLM",
                ((String) todo.get("description")).contains("不经过 LLM"));
        ok("todo description recommends submit fallback",
                ((String) todo.get("description")).contains("xiaoai_submit"));
        Map<?, ?> todoSchema = (Map<?, ?>) todo.get("inputSchema");
        eq("todo required", "[title]", ((List<?>) todoSchema.get("required")).toString());
        eq("todo props", "[title, content]",
                ((Map<?, ?>) todoSchema.get("properties")).keySet().toString());
        eq("todo additionalProperties", Boolean.FALSE, todoSchema.get("additionalProperties"));

        Map<?, ?> sw = (Map<?, ?>) tools.get(7);
        Map<?, ?> swSchema = (Map<?, ?>) sw.get("inputSchema");
        eq("switch required", "[model]", ((List<?>) swSchema.get("required")).toString());
        eq("switch props", "[model, marker]",
                ((Map<?, ?>) swSchema.get("properties")).keySet().toString());

        Map<?, ?> lg = (Map<?, ?>) tools.get(8);
        ok("logs schema has no required",
                !((Map<?, ?>) lg.get("inputSchema")).containsKey("required"));

        String toolText = Mcp.toolListText();
        ok("工具清单含 xiaoai_stats", toolText.contains("xiaoai_stats"));
        ok("工具清单含 xiaoai_todo_create", toolText.contains("xiaoai_todo_create"));
        eq("工具清单行数", 12, toolText.split("\n").length);

        Map<?, ?> statsTool = (Map<?, ?>) tools.get(9);
        eq("stats props", "[days]",
                ((Map<?, ?>) ((Map<?, ?>) statsTool.get("inputSchema")).get("properties")).keySet().toString());

        Map<?, ?> st = (Map<?, ?>) tools.get(0);
        eq("status schema empty props", "{}",
                ((Map<?, ?>) ((Map<?, ?>) st.get("inputSchema")).get("properties")).toString());

        // ------------------------------------------------------------ tools/call：status
        eq("xiaoai_status",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"facade=true cli=true port=8771 version=9.9.9\"}],\"isError\":false}}",
                call(hs, 4, "xiaoai_status", null));

        // hook 摘要非空时追加在末尾；空串表示宿主未装载 hook，保持原样
        h.hookSummary = "hooks=partial compat=probe xiaoai=8.3.0";
        eq("xiaoai_status + hook 摘要",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"facade=true cli=true port=8771 version=9.9.9"
                        + " hooks=partial compat=probe xiaoai=8.3.0\"}],\"isError\":false}}",
                call(hs, 5, "xiaoai_status", null));
        h.hookSummary = "";

        // ------------------------------------------------------------ tools/call：submit（自动开会话）
        h.cliCalls.clear();
        eq("xiaoai_submit",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"好的，收到\"}],\"isError\":false}}",
                call(hs, 7, "xiaoai_submit", "{\"text\":\"打开支付宝\"}"));
        eq("submit calls session.open", "{\"id\":\"mcp\",\"method\":\"session.open\",\"params\":{}}",
                h.cliCalls.get(0));
        eq("submit calls agent.submit",
                "{\"id\":\"mcp\",\"method\":\"agent.submit\",\"params\":{\"sessionId\":\"sid-1\",\"text\":\"打开支付宝\"}}",
                h.cliCalls.get(1));

        // sessionId 透传则不再开新会话
        h.cliCalls.clear();
        call(hs, 7, "xiaoai_submit", "{\"text\":\"再说一次\",\"sessionId\":\"sid-9\"}");
        eq("submit reuses session", "{\"id\":\"mcp\",\"method\":\"agent.submit\",\"params\":{\"sessionId\":\"sid-9\",\"text\":\"再说一次\"}}",
                h.cliCalls.get(0));

        eq("submit missing text",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: text\"}],\"isError\":true}}",
                call(hs, 7, "xiaoai_submit", null));

        // 0.16.0：structured=true 时把 sessionId 一并返回（默认仍是纯文本，兼容旧提示词）
        h.cliCalls.clear();
        eq("xiaoai_submit structured",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"ok\\\":true,\\\"text\\\":\\\"好的，收到\\\",\\\"sessionId\\\":\\\"sid-1\\\"}\"}],"
                        + "\"isError\":false}}",
                call(hs, 7, "xiaoai_submit",
                        "{\"text\":\"打开支付宝\",\"structured\":true}"));
        // structured 也复用传入的 sessionId
        String structuredReuse = textOf(call(hs, 7, "xiaoai_submit",
                "{\"text\":\"继续\",\"sessionId\":\"sid-9\",\"structured\":true}"));
        ok("submit structured 复用 sessionId",
                ((Map<?, ?>) Json.read(structuredReuse)).get("sessionId").equals("sid-9"));

        StringBuilder longSubmit = new StringBuilder();
        for (int i = 0; i < Mcp.SUBMIT_TEXT_MAX + 1; i++) longSubmit.append('x');
        ok("submit text length cap",
                call(hs, 7, "xiaoai_submit", "{\"text\":\"" + longSubmit + "\"}")
                        .contains("text too long (max " + Mcp.SUBMIT_TEXT_MAX + ")"));

        // 0.16.0：未知参数不再静默忽略
        String unknownArg = call(hs, 7, "xiaoai_logs", "{\"count\":3}");
        ok("未知参数报错", unknownArg.contains("\"isError\":true"));
        ok("未知参数文案", textOf(unknownArg).contains("unknown argument: count"));
        String unknownArgSubmit = call(hs, 7, "xiaoai_submit",
                "{\"text\":\"hi\",\"sessonId\":\"typo\"}");
        ok("submit 未知参数报错", textOf(unknownArgSubmit).contains("unknown argument: sessonId"));
        ok("status 不接受参数",
                textOf(call(hs, 7, "xiaoai_status", "{\"x\":1}")).contains("unknown argument: x"));

        // session.open 失败 → 错误并带摘要
        h.cliResponses.put("session.open", "ERR boom");
        eq("submit session.open failed",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"session.open failed: ERR boom\"}],\"isError\":true}}",
                call(hs, 7, "xiaoai_submit", "{\"text\":\"hi\"}"));

        // CLI 未就绪
        h.cli = false;
        eq("submit cli not ready",
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"ERR cli service not ready\"}],\"isError\":true}}",
                call(hs, 7, "xiaoai_submit", "{\"text\":\"hi\",\"sessionId\":\"s\"}"));
        h.cli = true;

        // ------------------------------------------------------------ tools/call：todo_create（确定性）
        h.todoCalls = 0;
        h.todoResult = AppToolProtocol.ok("{\"noteId\":\"n1\"}");
        eq("xiaoai_todo_create default content",
                "{\"jsonrpc\":\"2.0\",\"id\":30,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"noteId\\\":\\\"n1\\\"}\"}],\"isError\":false}}",
                call(hs, 30, "xiaoai_todo_create", "{\"title\":\"买牛奶\"}"));
        eq("todo title passed", "买牛奶", h.todoTitle);
        eq("todo default content passed", "- [ ] 买牛奶", h.todoContent);

        call(hs, 31, "xiaoai_todo_create",
                "{\"title\":\"购物\",\"content\":\"- [ ] 牛奶\\n- [ ] 鸡蛋\"}");
        eq("todo explicit content preserved", "- [ ] 牛奶\n- [ ] 鸡蛋", h.todoContent);

        call(hs, 32, "xiaoai_todo_create", "{\"title\":\" 空白 \",\"content\":\"   \"}");
        eq("todo blank content defaults", "- [ ] 空白", h.todoContent);

        StringBuilder longTitle = new StringBuilder();
        for (int i = 0; i < Mcp.TODO_TITLE_MAX + 1; i++) longTitle.append('t');
        int callsBeforeLongTitle = h.todoCalls;
        ok("todo title length cap",
                call(hs, 36, "xiaoai_todo_create", "{\"title\":\"" + longTitle + "\"}")
                        .contains("title too long"));
        eq("todo title cap does not call host", callsBeforeLongTitle, h.todoCalls);

        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < Mcp.TODO_CONTENT_MAX + 1; i++) longContent.append('c');
        int callsBeforeLongContent = h.todoCalls;
        ok("todo content length cap",
                call(hs, 37, "xiaoai_todo_create",
                        "{\"title\":\"x\",\"content\":\"" + longContent + "\"}")
                        .contains("content too long"));
        eq("todo content cap does not call host", callsBeforeLongContent, h.todoCalls);

        eq("todo missing title",
                "{\"jsonrpc\":\"2.0\",\"id\":33,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: title\"}],\"isError\":true}}",
                call(hs, 33, "xiaoai_todo_create", "{\"content\":\"- [ ] x\"}"));

        h.todoResult = AppToolProtocol.error("CALL_TIMEOUT", "handleRequest 超过 5000ms");
        String todoErr = call(hs, 34, "xiaoai_todo_create", "{\"title\":\"超时\"}");
        ok("todo provider error isError", todoErr.contains("\"isError\":true"));
        ok("todo provider error structured", textOf(todoErr).contains("\"error\":\"CALL_TIMEOUT\""));

        h.todoThrow = true;
        String todoThrowOut = call(hs, 35, "xiaoai_todo_create", "{\"title\":\"异常\"}");
        ok("todo host exception isError", todoThrowOut.contains("\"isError\":true"));
        ok("todo host exception structured", textOf(todoThrowOut).contains("\"error\":\"HOST_ERROR\""));
        h.todoThrow = false;
        h.todoResult = AppToolProtocol.ok("{\"noteId\":\"n1\"}");

        // ------------------------------------------------------------ tools/call：cli / logs / island
        h.cliResponses.put("auth.status", "AUTH-OK");
        eq("xiaoai_cli",
                "{\"jsonrpc\":\"2.0\",\"id\":11,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"AUTH-OK\"}],\"isError\":false}}",
                call(hs, 11, "xiaoai_cli", "{\"cmd\":\"{\\\"id\\\":\\\"1\\\",\\\"method\\\":\\\"auth.status\\\"}\"}"));

        call(hs, 12, "xiaoai_logs", "{\"limit\":5,\"since\":100}");
        eq("xiaoai_logs passes limit", 5, h.logsLimit);
        eq("xiaoai_logs passes since", 100L, h.logsSince);
        call(hs, 12, "xiaoai_logs", null);
        eq("xiaoai_logs default limit", 80, h.logsLimit);
        eq("xiaoai_logs default since", 0L, h.logsSince);

        call(hs, 14, "xiaoai_stats", null);
        eq("xiaoai_stats default days", 7, h.statsDays);
        ok("xiaoai_stats returns today buckets", textOf(call(hs, 14, "xiaoai_stats", null))
                .contains("\"today\":{\"third\":2,\"official\":1}"));
        call(hs, 14, "xiaoai_stats", "{\"days\":3}");
        eq("xiaoai_stats honors days", 3, h.statsDays);
        call(hs, 14, "xiaoai_stats", "{\"days\":999}");
        eq("xiaoai_stats 透传原始 days（由上层夹到 90 + clamped）", 999, h.statsDays);

        call(hs, 13, "xiaoai_island", null);
        eq("island default text", "小爱同学plus", h.islandText);
        eq("island default force", true, h.islandForce);
        call(hs, 13, "xiaoai_island", "{\"text\":\"自定义\",\"force\":false}");
        eq("island custom text", "自定义", h.islandText);
        eq("island custom force", false, h.islandForce);

        // ------------------------------------------------------------ tools/call：config
        eq("xiaoai_config",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"model\\\":\\\"deepseek-chat\\\",\\\"baseUrl\\\":\\\"https://api.deepseek.com/v1\\\","
                        + "\\\"enabled\\\":true,\\\"marker\\\":true,\\\"markerTemplate\\\":\\\"\\\",\\\"bridge\\\":true,\\\"lan\\\":false,\\\"noAuth\\\":false,\\\"weakToken\\\":false,\\\"authMode\\\":\\\"token\\\",\\\"apiKeySet\\\":true,\\\"tokenSet\\\":true,"
                        + "\\\"port\\\":8771,\\\"version\\\":\\\"9.9.9\\\"}\"}],\"isError\":false}}",
                call(hs, 5, "xiaoai_config", null));

        // ------------------------------------------------------------ tools/call：switch_model
        eq("switch_model model only",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"ok\\\":true,\\\"model\\\":\\\"m2\\\",\\\"lastGoodModel\\\":\\\"m2\\\",\\\"baseUrl\\\":\\\"https://api.deepseek.com/v1\\\","
                        + "\\\"apiKeySet\\\":true,\\\"changed\\\":true}\"}],\"isError\":false}}",
                call(hs, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        eq("switch_model keeps baseUrl", "https://api.deepseek.com/v1", h.written.baseUrl);
        eq("switch_model keeps apiKey", "sk-test", h.written.apiKey);
        eq("switch_model keeps token", "tok-1", h.written.token);

        String swOut = call(hs, 10, "xiaoai_switch_model", "{\"model\":\"deepseek-chat\"}");
        String swText = textOf(swOut);
        eq("switch_model same values changed=false", Boolean.FALSE,
                ((Map<?, ?>) Json.read(swText)).get("changed"));

        // WP4 D10：baseUrl/apiKey/bridge/lan 一律拒绝，且不写配置
        h.written = null;
        eq("switch_model rejects baseUrl",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"端点和密钥只能在手机 App 设置；xiaoai_switch_model 只接受 model / marker（baseUrl 已拒绝）\"}],\"isError\":true}}",
                call(hs, 10, "xiaoai_switch_model",
                        "{\"model\":\"m2\",\"baseUrl\":\"https://x/\",\"apiKey\":\"k2\",\"marker\":false,\"bridge\":false}"));
        ok("switch_model 拒绝后未写配置", h.written == null);

        String[] banned = {"apiKey", "bridge", "lan", "token", "enabled", "markerTemplate"};
        for (String key : banned) {
            h.written = null;
            String rejOut = call(hs, 10, "xiaoai_switch_model",
                    "{\"model\":\"m2\",\"" + key + "\":\"x\"}");
            ok("switch_model 拒绝 " + key, textOf(rejOut).contains("isError")
                    || rejOut.contains("\"isError\":true"));
            ok("switch_model " + key + " 未写配置", h.written == null);
        }

        // marker 是允许的
        h.written = null;
        call(hs, 10, "xiaoai_switch_model", "{\"model\":\"m2\",\"marker\":false}");
        ok("switch_model 允许 marker", h.written != null && !h.written.marker);
        eq("switch_model 保留 baseUrl", "https://api.deepseek.com/v1", h.written.baseUrl);
        eq("switch_model 保留 apiKey", "sk-test", h.written.apiKey);
        eq("switch_model 保留 token", "tok-1", h.written.token);
        eq("switch_model 保留 bridge", true, h.written.bridge);

        eq("switch_model no model",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: model\"}],\"isError\":true}}",
                call(hs, 10, "xiaoai_switch_model", "{\"marker\":false}"));

        h.cfgBase = "";
        eq("switch_model no endpoint",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: baseUrl（当前没有可用端点，请在手机 App 设置）\"}],\"isError\":true}}",
                call(hs, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        h.cfgBase = "https://api.deepseek.com/v1";

        h.writeOk = false;
        eq("switch_model write failed",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"write prefs failed\"}],\"isError\":true}}",
                call(hs, 10, "xiaoai_switch_model", "{\"model\":\"m2\"}"));
        h.writeOk = true;

        // ------------------------------------------------------------ tools/call：prompt / memory（P2）
        FakeMemory mem = new FakeMemory();
        Mcp.Hosts p2 = new Mcp.Hosts(h, h, h, h, h, null, mem);
        eq("xiaoai_prompt 返回状态",
                "{\"jsonrpc\":\"2.0\",\"id\":30,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"{\\\"enabled\\\":true,\\\"mode\\\":\\\"append\\\"}\"}],\"isError\":false}}",
                call(p2, 30, "xiaoai_prompt", null));
        eq("xiaoai_prompt 拒绝参数", true,
                call(p2, 30, "xiaoai_prompt", "{\"x\":1}").contains("\"isError\":true"));

        call(p2, 31, "xiaoai_memory", null);
        eq("xiaoai_memory 默认 get 用 50 条", 50, mem.limit);
        call(p2, 31, "xiaoai_memory", "{\"op\":\"get\",\"limit\":3}");
        eq("xiaoai_memory 透传 limit", 3, mem.limit);
        call(p2, 31, "xiaoai_memory", "{\"limit\":9999}");
        eq("xiaoai_memory limit 夹到 200", 200, mem.limit);

        mem.writable = false;
        mem.section = null;
        String denied = call(p2, 32, "xiaoai_memory",
                "{\"op\":\"append\",\"section\":\"长期记忆\",\"text\":\"x\"}");
        ok("memoryWriteEnabled=false 时拒绝写", denied.contains("\"isError\":true"));
        ok("拒绝文案说明怎么打开", denied.contains("memoryWriteEnabled=false"));
        ok("被拒绝时没有落到端口", mem.section == null);

        mem.writable = true;
        ok("缺 section 被拒",
                call(p2, 32, "xiaoai_memory", "{\"op\":\"append\",\"text\":\"x\"}")
                        .contains("missing argument: section"));
        ok("缺 text 被拒",
                call(p2, 32, "xiaoai_memory", "{\"op\":\"append\",\"section\":\"长期记忆\"}")
                        .contains("missing argument: text"));
        ok("未知 op 被拒",
                call(p2, 32, "xiaoai_memory", "{\"op\":\"delete\",\"section\":\"x\",\"text\":\"y\"}")
                        .contains("unknown op: delete"));
        ok("未知参数被拒",
                call(p2, 32, "xiaoai_memory", "{\"op\":\"get\",\"nope\":1}")
                        .contains("unknown argument: nope"));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < plus.xiaoi.module.core.Contract.MEMORY_APPEND_MAX_BYTES + 10; i++) {
            tooLong.append('a');
        }
        ok("正文超限被拒",
                call(p2, 32, "xiaoai_memory", "{\"op\":\"append\",\"section\":\"长期记忆\",\"text\":\""
                        + tooLong + "\"}").contains("text too large"));

        String appended = call(p2, 33, "xiaoai_memory",
                "{\"op\":\"append\",\"section\":\"长期记忆/重要事实\",\"text\":\"新条目\"}");
        ok("append 成功不报错", !appended.contains("\"isError\":true"));
        eq("append 透传 section", "长期记忆/重要事实", mem.section);
        eq("append 透传 text", "新条目", mem.text);

        String replaced = call(p2, 34, "xiaoai_memory",
                "{\"op\":\"replace\",\"section\":\"长期记忆\",\"text\":\"- 全部替换\"}");
        ok("replace 成功不报错", !replaced.contains("\"isError\":true"));
        eq("replace 透传 section", "长期记忆", mem.section);

        mem.writeResult = "{\"ok\":false,\"code\":\"WRITE_FAILED\"}";
        ok("端口 ok=false → isError",
                call(p2, 35, "xiaoai_memory",
                        "{\"op\":\"append\",\"section\":\"长期记忆\",\"text\":\"x\"}")
                        .contains("\"isError\":true"));
        mem.writeResult = "{\"ok\":true,\"code\":\"APPENDED\"}";

        // 官方记忆：只读检索透传分页
        call(p2, 37, "xiaoai_memory",
                "{\"op\":\"official\",\"q\":\"羽毛球\",\"page\":2,\"pageSize\":5}");
        eq("official 透传 q", "羽毛球", mem.officialQ);
        eq("official 透传 page", 2, mem.officialPage);
        eq("official 透传 pageSize", 5, mem.officialSize);
        call(p2, 37, "xiaoai_memory", "{\"op\":\"official\",\"pageSize\":999}");
        eq("official pageSize 夹到 50", 50, mem.officialSize);

        // 官方写入：删除类指令、缺参、写开关
        ok("official_write 拒绝删除类指令",
                call(p2, 38, "xiaoai_memory", "{\"op\":\"official_write\",\"q\":\"忘掉我的生日\"}")
                        .contains("不支持删除类指令"));
        eq("被拒时没有落到端口", null, mem.officialWriteQ);
        ok("official_write 缺 q 被拒",
                call(p2, 38, "xiaoai_memory", "{\"op\":\"official_write\"}")
                        .contains("missing argument: q"));
        mem.writable = false;
        ok("official_write 受写开关约束",
                call(p2, 39, "xiaoai_memory", "{\"op\":\"official_write\",\"q\":\"我每周三打球\"}")
                        .contains("memoryWriteEnabled=false"));
        mem.writable = true;
        call(p2, 40, "xiaoai_memory", "{\"op\":\"official_write\",\"q\":\"我每周三打球\"}");
        eq("official_write 透传 q", "我每周三打球", mem.officialWriteQ);

        // 加密库（claw_memory.db）：stats / live
        call(p2, 40, "xiaoai_memory", "{\"op\":\"stats\"}");
        eq("stats 调到端口", 1, mem.statsCalls);
        call(p2, 40, "xiaoai_memory", "{\"op\":\"live\",\"q\":\"羽毛球\"}");
        eq("live 透传 q", "羽毛球", mem.liveQ);
        eq("live 默认 20 条", 20, mem.liveLimit);
        eq("live 默认 since=0", 0L, mem.liveSince);
        call(p2, 40, "xiaoai_memory", "{\"op\":\"live\",\"limit\":9999,\"since\":1700000000000}");
        eq("live limit 夹到 200", 200, mem.liveLimit);
        eq("live 透传 since", 1700000000000L, mem.liveSince);
        ok("live 不接受未知参数",
                call(p2, 40, "xiaoai_memory", "{\"op\":\"live\",\"bogus\":1}")
                        .contains("unknown argument"));
        ok("live 不需要写开关",
                !call(p2, 40, "xiaoai_memory", "{\"op\":\"live\"}").contains("memoryWriteEnabled"));
        ok("status 带 clawdb 探针",
                call(p2, 40, "xiaoai_status", null).contains("clawdb=ok"));

        // 局域网写：需要二次因子；只读不受影响
        ok("LAN 写无二次因子被拒",
                callLan(p2, 41, "xiaoai_memory",
                        "{\"op\":\"append\",\"section\":\"长期记忆\",\"text\":\"x\"}", true, false)
                        .contains("DENIED_LAN"));
        ok("LAN 写带二次因子放行",
                !callLan(p2, 42, "xiaoai_memory",
                        "{\"op\":\"append\",\"section\":\"长期记忆\",\"text\":\"x\"}", true, true)
                        .contains("DENIED_LAN"));
        ok("LAN 只读不受影响",
                !callLan(p2, 43, "xiaoai_memory", "{\"op\":\"get\",\"limit\":3}", true, false)
                        .contains("DENIED_LAN"));
        ok("LAN official_write 同样需要二次因子",
                callLan(p2, 44, "xiaoai_memory",
                        "{\"op\":\"official_write\",\"q\":\"记住我喜欢猫\"}", true, false)
                        .contains("DENIED_LAN"));

        // 入站画像（小爱反向调用）：只暴露只读/设备动作子集
        String roList = listProfile(p2, Mcp.PROFILE_READONLY);
        ok("readonly 工具表含 xiaoai_status", roList.contains("\"xiaoai_status\""));
        ok("readonly 工具表不含 xiaoai_submit", !roList.contains("\"xiaoai_submit\""));
        ok("readonly 工具表不含 xiaoai_cli", !roList.contains("\"xiaoai_cli\""));
        ok("readonly 工具表不含 xiaoai_memory", !roList.contains("\"xiaoai_memory\""));

        String roSubmit = callProfile(p2, 45, "xiaoai_submit", "{\"text\":\"hi\"}",
                Mcp.PROFILE_READONLY);
        ok("readonly 调用 submit 被拒", roSubmit.contains("FORBIDDEN_TOOL"));
        String roStatus = callProfile(p2, 46, "xiaoai_status", null, Mcp.PROFILE_READONLY);
        ok("readonly 调用 status 放行", !roStatus.contains("FORBIDDEN_TOOL"));
        ok("full 画像仍可 submit",
                !callProfile(p2, 47, "xiaoai_submit", "{\"text\":\"hi\"}",
                        Mcp.PROFILE_FULL).contains("FORBIDDEN_TOOL"));

        // 通道 A：nlp_query + apptools 探针
        FakeAppTool app = new FakeAppTool();
        Mcp.Hosts p3 = new Mcp.Hosts(h, h, h, h, h, null, mem, app);
        call(p3, 50, "xiaoai_nlp", "{\"text\":\"明天早上八点叫我\"}");
        eq("nlp 透传工具名", "nlp_query", app.tool);
        eq("nlp 透传 query", "明天早上八点叫我", app.query);
        ok("nlp 缺 text 被拒",
                call(p3, 50, "xiaoai_nlp", "{}").contains("missing argument: text"));
        ok("status 带 apptools 探针",
                call(p3, 51, "xiaoai_status", null).contains("apptools=ok methods=18"));
        ok("无 appTool 时 nlp 报不可用",
                call(p2, 52, "xiaoai_nlp", "{\"text\":\"hi\"}").contains("APPTOOL_UNAVAILABLE"));

        // 没有记忆端口时两个工具都明确报不可用，而不是伪造空结果
        ok("无端口时 xiaoai_prompt 报不可用",
                call(hs, 36, "xiaoai_prompt", null).contains("不可用"));
        ok("无端口时 xiaoai_memory 报不可用",
                call(hs, 36, "xiaoai_memory", null).contains("不可用"));

        // ------------------------------------------------------------ 未知工具与参数形态
        eq("unknown tool",
                "{\"jsonrpc\":\"2.0\",\"id\":20,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"unknown tool: zzz\"}],\"isError\":true}}",
                call(hs, 20, "zzz", null));

        eq("tools/call without params",
                "{\"jsonrpc\":\"2.0\",\"id\":21,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"unknown tool: \"}],\"isError\":true}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\"}", hs));

        eq("tools/call non-object arguments",
                "{\"jsonrpc\":\"2.0\",\"id\":22,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: text\"}],\"isError\":true}}",
                Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":22,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"xiaoai_submit\",\"arguments\":\"x\"}}", hs));

        // ------------------------------------------------------------ 参数白名单
        ok("rejectedSwitchArg null", Mcp.rejectedSwitchArg(null) == null);
        ok("rejectedSwitchArg model/marker",
                Mcp.rejectedSwitchArg((Map<?, ?>) Json.read("{\"model\":\"m\",\"marker\":true}")) == null);
        ok("rejectedSwitchArg baseUrl",
                Mcp.rejectedSwitchArg((Map<?, ?>) Json.read("{\"baseUrl\":\"x\"}"))
                        .contains("端点和密钥只能在手机 App 设置"));

        // tools/call 元数据回调（不含参数值）
        h.toolCalls = 0;
        call(hs, 98, "xiaoai_status", null);
        eq("onToolCall count", 1, h.toolCalls);
        eq("onToolCall tool", "xiaoai_status", h.lastTool);
        ok("onToolCall ok", h.lastOk);
        eq("onToolCall args", 0, h.lastArgs);
        ok("onToolCall elapsed >= 0", h.lastElapsed >= 0);
        call(hs, 99, "no_such_tool", null);
        eq("onToolCall failure count", 2, h.toolCalls);
        ok("onToolCall failure ok=false", !h.lastOk);

        return T.report("McpTest");
    }

    /** 便捷：构造 tools/call 报文并取响应。arguments 为 null 时不带 arguments 字段。 */
    private static String call(Mcp.Hosts hosts, int id, String name, String argsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"").append(name).append("\"");
        if (argsJson != null) {
            sb.append(",\"arguments\":").append(argsJson);
        }
        sb.append("}}");
        return Mcp.handle(sb.toString(), hosts);
    }

    /** 带来源标记的调用：lan=true 模拟局域网，writeOk=是否带写二次因子。 */
    private static String callLan(Mcp.Hosts hosts, int id, String name, String argsJson,
                                  boolean lan, boolean writeOk) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"").append(name).append("\"");
        if (argsJson != null) {
            sb.append(",\"arguments\":").append(argsJson);
        }
        sb.append("}}");
        return Mcp.handle(sb.toString(), hosts, lan, writeOk);
    }

    /** 指定画像的调用（readonly = 小爱入站）。 */
    private static String callProfile(Mcp.Hosts hosts, int id, String name, String argsJson,
                                      String profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
                .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"").append(name).append("\"");
        if (argsJson != null) {
            sb.append(",\"arguments\":").append(argsJson);
        }
        sb.append("}}");
        return Mcp.handle(sb.toString(), hosts, false, false, profile);
    }

    private static String listProfile(Mcp.Hosts hosts, String profile) {
        return Mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                hosts, false, false, profile);
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

    /** 通道 A 的假实现：记录调用参数，返回固定 JSON。 */
    private static final class FakeAppTool implements Mcp.AppToolPort {
        String tool;
        String query;

        @Override
        public String call(String tool, Map<String, Object> args) {
            this.tool = tool;
            this.query = args == null ? null : String.valueOf(args.get("query"));
            return "{\"ok\":true,\"data\":{\"alarm\":[]},\"render_token\":\"t1\"}";
        }

        @Override
        public String summary() {
            return "apptools=ok methods=18";
        }
    }

    /** P2：提示词/记忆端口的假实现。 */
    private static final class FakeMemory implements Mcp.MemoryPort {
        boolean writable = true;
        int limit = -1;
        String section;
        String text;
        String overview = "{\"ok\":true,\"code\":\"OK\",\"sections\":[]}";
        String writeResult = "{\"ok\":true,\"code\":\"APPENDED\",\"backup\":\"MEMORY.md.bak-1\"}";
        String officialQ;
        int officialPage = -1;
        int officialSize = -1;
        String officialWriteQ;
        String officialResult = "{\"ok\":true,\"code\":\"OK\",\"items\":[{\"text\":\"羽毛球\"}]}";
        int statsCalls;
        String liveQ;
        long liveSince = -1L;
        int liveLimit = -1;

        @Override
        public String promptStatus() {
            return "{\"enabled\":true,\"mode\":\"append\"}";
        }

        @Override
        public boolean writable() {
            return writable;
        }

        @Override
        public String memoryOverview(int limit) {
            this.limit = limit;
            return overview;
        }

        @Override
        public String memoryAppend(String section, String text) {
            this.section = section;
            this.text = text;
            return writeResult;
        }

        @Override
        public String memoryReplace(String section, String text) {
            this.section = section;
            this.text = text;
            return writeResult;
        }

        @Override
        public String officialRead(String keywords, int page, int pageSize) {
            this.officialQ = keywords;
            this.officialPage = page;
            this.officialSize = pageSize;
            return officialResult;
        }

        @Override
        public String officialWrite(String query) {
            this.officialWriteQ = query;
            return officialResult;
        }

        @Override
        public String clawProbe() {
            return "clawdb=ok";
        }

        @Override
        public String clawStats() {
            statsCalls++;
            return "{\"ok\":true,\"db\":\"claw_memory.db\",\"tables\":[],\"types\":[]}";
        }

        @Override
        public String clawLive(String q, long since, int limit) {
            this.liveQ = q;
            this.liveSince = since;
            this.liveLimit = limit;
            return "{\"ok\":true,\"count\":1,\"items\":[{\"mem_key\":\"羽毛球\",\"mem_value\":\"每周三\"}]}";
        }
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
