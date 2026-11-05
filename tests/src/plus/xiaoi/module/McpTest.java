package plus.xiaoi.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        + "所有调用都发生在手机本机回环地址上。\"}}",
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
        eq("tool count", 8, tools.size());
        eq("tool order", "xiaoai_status,xiaoai_submit,xiaoai_cli,xiaoai_config,xiaoai_island,xiaoai_switch_model,xiaoai_logs,xiaoai_stats",
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

        Map<?, ?> sw = (Map<?, ?>) tools.get(5);
        Map<?, ?> swSchema = (Map<?, ?>) sw.get("inputSchema");
        eq("switch required", "[model]", ((List<?>) swSchema.get("required")).toString());
        eq("switch props", "[model, baseUrl, apiKey, marker, bridge]",
                ((Map<?, ?>) swSchema.get("properties")).keySet().toString());

        Map<?, ?> lg = (Map<?, ?>) tools.get(6);
        ok("logs schema has no required",
                !((Map<?, ?>) lg.get("inputSchema")).containsKey("required"));

        String toolText = Mcp.toolListText();
        ok("工具清单含 xiaoai_stats", toolText.contains("xiaoai_stats"));
        eq("工具清单行数", 8, toolText.split("\n").length);

        Map<?, ?> statsTool = (Map<?, ?>) tools.get(7);
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

        call(h, 10, "xiaoai_switch_model", "{\"model\":\"m2\",\"baseUrl\":\"https://x/\",\"apiKey\":\"k2\",\"marker\":false,\"bridge\":false}");
        eq("switch_model full override base", "https://x/", h.written.baseUrl);
        eq("switch_model full override key", "k2", h.written.apiKey);
        eq("switch_model full override marker", false, h.written.marker);
        eq("switch_model full override bridge", false, h.written.bridge);

        eq("switch_model missing model",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: model\"}],\"isError\":true}}",
                call(h, 10, "xiaoai_switch_model", "{\"baseUrl\":\"https://x/\"}"));

        h.cfgBase = "";
        eq("switch_model no endpoint",
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"missing argument: baseUrl（当前没有可用端点）\"}],\"isError\":true}}",
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

        // ------------------------------------------------------------ mergeConfig（/config 缺失字段保持当前值）
        Mcp.Snapshot cur = new Mcp.Snapshot("https://api.x/v1", "sk-1", "m-1", true, true, "tok-old");

        Mcp.Snapshot m = Mcp.mergeConfig((Map<?, ?>) Json.read("{}"), cur);
        eq("merge empty body keeps all", "https://api.x/v1", m.baseUrl);
        eq("merge empty body keeps key", "sk-1", m.apiKey);
        eq("merge empty body keeps model", "m-1", m.model);
        eq("merge empty body keeps marker", true, m.marker);
        eq("merge empty body keeps bridge", true, m.bridge);
        eq("merge empty body keeps lan", false, m.lan);
        eq("merge empty body keeps token", "tok-old", m.token);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"token\":\"tok-new\"}"), cur);
        eq("merge token only changes token", "tok-new", m.token);
        eq("merge token only keeps model", "m-1", m.model);
        eq("merge token only keeps baseUrl", "https://api.x/v1", m.baseUrl);
        eq("merge token only keeps apiKey", "sk-1", m.apiKey);
        eq("merge token only keeps template", "", m.markerTemplate);

        Mcp.Snapshot curT = new Mcp.Snapshot("https://api.x/v1", "sk-1", "m-1", true, true, "tok", "[模型: {model}]");
        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"markerTemplate\":\"[{model}]\"}"), curT);
        eq("merge template replace", "[{model}]", m.markerTemplate);
        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{}"), curT);
        eq("merge template keep", "[模型: {model}]", m.markerTemplate);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"baseUrl\":\"\"}"), cur);
        eq("merge explicit empty clears", "", m.baseUrl);
        eq("merge explicit empty keeps rest", "m-1", m.model);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"marker\":false}"), cur);
        eq("merge explicit false flips", false, m.marker);
        eq("merge explicit false keeps bridge", true, m.bridge);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"lan\":true}"), cur);
        eq("merge lan flips", true, m.lan);
        eq("merge lan keeps model", "m-1", m.model);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read("{\"token\":null}"), cur);
        eq("merge explicit null clears token", "", m.token);

        m = Mcp.mergeConfig((Map<?, ?>) Json.read(
                "{\"baseUrl\":\"https://b/\",\"apiKey\":\"k2\",\"model\":\"m2\","
                        + "\"marker\":false,\"bridge\":false,\"token\":\"t2\"}"), cur);
        eq("merge full replace base", "https://b/", m.baseUrl);
        eq("merge full replace key", "k2", m.apiKey);
        eq("merge full replace model", "m2", m.model);
        eq("merge full replace marker", false, m.marker);
        eq("merge full replace bridge", false, m.bridge);
        eq("merge full replace token", "t2", m.token);

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
