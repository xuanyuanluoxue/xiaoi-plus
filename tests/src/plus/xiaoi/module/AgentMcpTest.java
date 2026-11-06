package plus.xiaoi.module;

import java.util.List;
import java.util.Map;

import plus.xiaoi.module.agent.ActionDescriptor;
import plus.xiaoi.module.agent.ActionExecutor;
import plus.xiaoi.module.agent.ActionRegistry;
import plus.xiaoi.module.agent.ActionRequest;
import plus.xiaoi.module.agent.ActionResult;
import plus.xiaoi.module.agent.AgentMcp;
import plus.xiaoi.module.agent.Actions;

/**
 * WP1.2a 纯 JVM 测试：MCP 路由、initialize / ping / tools/list、capabilities、
 * xiaoai_action（device.info fake）、错误码、isError 映射、通知 202/null。
 */
public final class AgentMcpTest {

    private static final String VERSION = "0.12.0-test";

    private AgentMcpTest() {}

    public static int run() {
        initializeAndTools();
        capabilities();
        action();
        errorsAndNotifications();
        return T.report("AgentMcpTest");
    }

    // ------------------------------------------------------------- initialize / tools

    @SuppressWarnings("unchecked")
    private static void initializeAndTools() {
        AgentMcp mcp = new AgentMcp(VERSION);
        ActionRegistry reg = registry();

        Map<String, Object> init = rpc(mcp, reg, "initialize", 1L, null);
        T.eq("initialize jsonrpc", "2.0", init.get("jsonrpc"));
        Map<String, Object> result = (Map<String, Object>) init.get("result");
        T.eq("initialize protocolVersion", AgentMcp.PROTOCOL, result.get("protocolVersion"));

        Map<String, Object> info = (Map<String, Object>) result.get("serverInfo");
        T.eq("serverInfo name", "xiaoi-plus-agent", info.get("name"));
        T.eq("serverInfo version 来自构造参数", VERSION, info.get("version"));

        String instructions = Json.optString(result, "instructions", "");
        T.ok("instructions 提到 PC Agent 是记忆真相", instructions.contains("PC Agent"));
        T.ok("instructions 提到危险动作需确认", instructions.contains("确认"));
        T.ok("instructions 提到手机端无状态", instructions.contains("无状态"));

        Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
        T.ok("initialize capabilities.tools", caps.get("tools") instanceof Map);

        Map<String, Object> ping = rpc(mcp, reg, "ping", 2L, null);
        T.eq("ping id 回显", 2L, ping.get("id"));
        T.ok("ping result 是空对象", ping.get("result") instanceof Map
                && ((Map<?, ?>) ping.get("result")).isEmpty());

        Map<String, Object> listed = mcp.toolsList();
        List<Object> tools = (List<Object>) listed.get("tools");
        T.eq("tools/list 工具数", 4, tools.size());
        T.eq("tools[0] 名称", "xiaoai_capabilities",
                ((Map<?, ?>) tools.get(0)).get("name"));
        T.eq("tools[1] 名称", "xiaoai_action",
                ((Map<?, ?>) tools.get(1)).get("name"));
        T.eq("tools[2] 名称", "xiaoai_confirm",
                ((Map<?, ?>) tools.get(2)).get("name"));
        T.eq("tools[3] 名称", "xiaoai_audit",
                ((Map<?, ?>) tools.get(3)).get("name"));

        Map<?, ?> capsSchema = (Map<?, ?>) ((Map<?, ?>) tools.get(0)).get("inputSchema");
        T.eq("capabilities schema type", "object", capsSchema.get("type"));
        T.ok("capabilities schema 含 includeUnavailable",
                ((Map<?, ?>) capsSchema.get("properties")).containsKey("includeUnavailable"));

        Map<?, ?> actionSchema = (Map<?, ?>) ((Map<?, ?>) tools.get(1)).get("inputSchema");
        Map<?, ?> props = (Map<?, ?>) actionSchema.get("properties");
        String[] common = {"action", "args", "context", "sessionId", "idempotencyKey",
                "dryRun", "async", "timeoutMs", "clientId", "confirmToken", "requestId"};
        boolean all = true;
        for (String key : common) {
            if (!props.containsKey(key)) {
                all = false;
                System.out.println("        缺少公共字段: " + key);
            }
        }
        T.ok("xiaoai_action 暴露全部公共字段", all);
        T.ok("xiaoai_action 必填 action",
                ((List<?>) actionSchema.get("required")).contains("action"));
        T.eq("xiaoai_action 不允许额外顶层字段", Boolean.FALSE,
                actionSchema.get("additionalProperties"));

        Map<?, ?> confirmSchema = (Map<?, ?>) ((Map<?, ?>) tools.get(2)).get("inputSchema");
        Map<?, ?> confirmProps = (Map<?, ?>) confirmSchema.get("properties");
        T.ok("xiaoai_confirm 含 confirmToken", confirmProps.containsKey("confirmToken"));
        T.ok("xiaoai_confirm 含 approve", confirmProps.containsKey("approve"));
        T.ok("xiaoai_confirm 必填 confirmToken",
                ((List<?>) confirmSchema.get("required")).contains("confirmToken"));
        T.ok("xiaoai_confirm 必填 approve",
                ((List<?>) confirmSchema.get("required")).contains("approve"));

        Map<?, ?> auditSchema = (Map<?, ?>) ((Map<?, ?>) tools.get(3)).get("inputSchema");
        T.ok("xiaoai_audit 含 limit",
                ((Map<?, ?>) auditSchema.get("properties")).containsKey("limit"));
        T.eq("xiaoai_audit limit 无必填", 0,
                ((List<?>) auditSchema.get("required")).size());
    }

    // ------------------------------------------------------------- capabilities

    @SuppressWarnings("unchecked")
    private static void capabilities() {
        AgentMcp mcp = new AgentMcp(VERSION);
        ActionRegistry reg = registry();

        Map<String, Object> tool = callTool(mcp, reg, "xiaoai_capabilities", null);
        T.eq("capabilities isError=false", Boolean.FALSE, tool.get("isError"));
        String text = toolText(tool);
        T.ok("capabilities 文本是 JSON actions", text.contains("\"actions\""));
        Map<String, Object> parsed = (Map<String, Object>) Json.read(text);
        List<Object> actions = (List<Object>) parsed.get("actions");
        T.eq("capabilities 默认包含全部 5 个动作", 5, actions.size());

        Map<?, ?> device = cap(actions, "device.info");
        T.ok("capabilities 有 device.info", device != null);
        T.eq("device.info 可用", Boolean.TRUE, device.get("available"));
        Map<?, ?> app = cap(actions, "app.open");
        T.eq("app.open 不可用（本包未实现）", Boolean.FALSE, app.get("available"));

        List<?> execs = (List<?>) device.get("executors");
        T.eq("device.info 一个执行器", 1, execs.size());
        T.eq("device.info 执行器名", "android",
                ((Map<?, ?>) execs.get(0)).get("name"));

        Map<String, Object> onlyAvailable = callTool(mcp, reg, "xiaoai_capabilities",
                arguments("includeUnavailable", Boolean.FALSE));
        Map<String, Object> parsedOnly =
                (Map<String, Object>) Json.read(toolText(onlyAvailable));
        T.eq("includeUnavailable=false 只剩 device.info", 1,
                ((List<?>) parsedOnly.get("actions")).size());
    }

    // ------------------------------------------------------------- xiaoai_action

    @SuppressWarnings("unchecked")
    private static void action() {
        AgentMcp mcp = new AgentMcp(VERSION);
        ActionRegistry reg = registry();

        Map<String, Object> args = Json.obj();
        args.put("action", "device.info");
        args.put("args", Json.obj());
        Map<String, Object> tool = callTool(mcp, reg, "xiaoai_action", args);
        T.eq("device.info isError=false", Boolean.FALSE, tool.get("isError"));
        Map<String, Object> envelope =
                (Map<String, Object>) Json.read(toolText(tool));
        T.eq("device.info ok=true", Boolean.TRUE, envelope.get("ok"));
        T.eq("device.info action", "device.info", envelope.get("action"));
        T.eq("device.info executor", "android", envelope.get("executor"));
        Map<String, Object> result = (Map<String, Object>) envelope.get("result");
        Map<String, Object> fields = (Map<String, Object>) result.get("fields");
        T.eq("device.info fake model", "TestPhone", fields.get("model"));
        T.ok("device.info requestId 非空", envelope.get("requestId") != null);

        Map<String, Object> unknown = callTool(mcp, reg, "xiaoai_action",
                arguments("action", "no.such.action"));
        T.eq("未知动作 isError=true", Boolean.TRUE, unknown.get("isError"));
        Map<String, Object> unknownEnvelope =
                (Map<String, Object>) Json.read(toolText(unknown));
        T.eq("未知动作错误码", "UNKNOWN_ACTION", unknownEnvelope.get("code"));

        Map<String, Object> missing = callTool(mcp, reg, "xiaoai_action", Json.obj());
        Map<String, Object> missingEnvelope =
                (Map<String, Object>) Json.read(toolText(missing));
        T.eq("缺 action 错误码", "UNKNOWN_ACTION", missingEnvelope.get("code"));

        Map<String, Object> argsMap = Json.obj();
        argsMap.put("action", "device.info");
        argsMap.put("args", arguments("bogus", Boolean.TRUE));
        Map<String, Object> invalid = callTool(mcp, reg, "xiaoai_action", argsMap);
        Map<String, Object> invalidEnvelope =
                (Map<String, Object>) Json.read(toolText(invalid));
        T.eq("未知参数 INVALID_ARGUMENT", "INVALID_ARGUMENT",
                invalidEnvelope.get("code"));
        T.eq("参数失败 isError=true", Boolean.TRUE, invalid.get("isError"));

        Map<String, Object> badArgs = Json.obj();
        badArgs.put("action", "device.info");
        badArgs.put("args", "not-an-object");
        Map<String, Object> badArgsTool = callTool(mcp, reg, "xiaoai_action", badArgs);
        Map<String, Object> badArgsEnvelope =
                (Map<String, Object>) Json.read(toolText(badArgsTool));
        T.eq("args 非 object -> INVALID_ARGUMENT", "INVALID_ARGUMENT",
                badArgsEnvelope.get("code"));

        Map<String, Object> dry = Json.obj();
        dry.put("action", "device.info");
        dry.put("dryRun", Boolean.TRUE);
        Map<String, Object> dryTool = callTool(mcp, reg, "xiaoai_action", dry);
        T.eq("dryRun isError=false", Boolean.FALSE, dryTool.get("isError"));
        Map<String, Object> dryEnvelope =
                (Map<String, Object>) Json.read(toolText(dryTool));
        Map<String, Object> plan = (Map<String, Object>) dryEnvelope.get("result");
        T.eq("dryRun 标记", Boolean.TRUE, plan.get("dryRun"));
        T.ok("dryRun warnings 非空",
                ((List<?>) dryEnvelope.get("warnings")).size() > 0);

        Map<String, Object> confirm = Json.obj();
        confirm.put("action", "notification.send");
        confirm.put("args", arguments("title", "t", "text", "x"));
        Map<String, Object> confirmTool = callTool(mcp, reg, "xiaoai_action", confirm);
        Map<String, Object> confirmEnvelope =
                (Map<String, Object>) Json.read(toolText(confirmTool));
        T.eq("未实现动作 -> NOT_AVAILABLE", "NOT_AVAILABLE",
                confirmEnvelope.get("code"));
        T.eq("未实现动作 isError=true", Boolean.TRUE, confirmTool.get("isError"));
    }

    // ------------------------------------------------------------- 错误 / 通知

    private static void errorsAndNotifications() {
        AgentMcp mcp = new AgentMcp(VERSION);
        ActionRegistry reg = registry();

        Map<String, Object> unknownMethod = rpc(mcp, reg, "resources/list", 7L, null);
        T.eq("未知方法 -32601", -32601L,
                ((Map<?, ?>) unknownMethod.get("error")).get("code"));
        T.eq("未知方法保留 id", 7L, unknownMethod.get("id"));

        Map<String, Object> bad = parse(mcp.handle("{", reg));
        T.eq("坏 JSON -32700", -32700L,
                ((Map<?, ?>) bad.get("error")).get("code"));

        Map<String, Object> empty = parse(mcp.handle("", reg));
        T.eq("空 body -32700", -32700L,
                ((Map<?, ?>) empty.get("error")).get("code"));

        Map<String, Object> array = parse(mcp.handle("[1,2,3]", reg));
        T.eq("顶层数组 -32700", -32700L,
                ((Map<?, ?>) array.get("error")).get("code"));

        Map<String, Object> noMethod = parse(mcp.handle("{\"jsonrpc\":\"2.0\",\"id\":9}", reg));
        T.eq("缺 method -32600", -32600L,
                ((Map<?, ?>) noMethod.get("error")).get("code"));

        T.eq("通知无 id 返回 null",
                null, mcp.handle("{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}", reg));
        T.eq("tools/call 通知无 id 返回 null", null,
                mcp.handle("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"xiaoai_capabilities\",\"arguments\":{}}}", reg));

        Map<String, Object> internal = parse(mcp.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"xiaoai_action\",\"arguments\":{}}}", null));
        T.eq("registry 为 null -> -32603", -32603L,
                ((Map<?, ?>) internal.get("error")).get("code"));

        Map<String, Object> unknownTool = callTool(mcp, reg, "no_such_tool", null);
        T.eq("未知工具 isError=true", Boolean.TRUE, unknownTool.get("isError"));
        T.ok("未知工具文案", toolText(unknownTool).contains("unknown tool"));
    }

    // ------------------------------------------------------------- 辅助

    private static ActionRegistry registry() {
        ActionRegistry reg = new ActionRegistry();
        for (ActionDescriptor d : Actions.builtins()) {
            reg.register(d, null);
        }
        reg.register(Actions.deviceInfo(), new ActionExecutor() {
            @Override
            public String name() {
                return "android";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public ActionResult execute(ActionRequest request) {
                Map<String, Object> values = Json.obj();
                values.put("model", "TestPhone");
                values.put("battery", 88L);
                Map<String, Object> result = Json.obj();
                result.put("fields", values);
                return ActionResult.ok("device.info", "android", result);
            }
        });
        return reg;
    }

    private static Map<String, Object> arguments(Object... kv) {
        Map<String, Object> m = Json.obj();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, Object> rpc(AgentMcp mcp, ActionRegistry reg,
                                           String method, long id,
                                           Map<String, Object> params) {
        Map<String, Object> req = Json.obj();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) req.put("params", params);
        return parse(mcp.handle(Json.write(req), reg));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        Object v = Json.read(json);
        return (Map<String, Object>) v;
    }

    private static Map<String, Object> callTool(AgentMcp mcp, ActionRegistry reg,
                                                String name, Map<String, Object> args) {
        Map<String, Object> params = Json.obj();
        params.put("name", name);
        if (args != null) params.put("arguments", args);
        Map<String, Object> response = rpc(mcp, reg, "tools/call", 100L, params);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        return result;
    }

    private static String toolText(Map<String, Object> tool) {
        List<?> content = (List<?>) tool.get("content");
        Map<?, ?> part = (Map<?, ?>) content.get(0);
        return Json.optString(part, "text", "");
    }

    private static Map<?, ?> cap(List<Object> actions, String name) {
        for (Object o : actions) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (name.equals(m.get("name"))) return m;
        }
        return null;
    }
}
