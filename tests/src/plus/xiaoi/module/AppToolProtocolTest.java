package plus.xiaoi.module;

import java.util.Map;

import plus.xiaoi.module.agent.AppToolProtocol;

/** 纯 JVM 的 Notes provider 协议测试：请求体、成功解析、结构化错误。 */
public final class AppToolProtocolTest {

    private AppToolProtocolTest() {}

    public static int run() {
        eq("default content", "- [ ] 买牛奶", AppToolProtocol.defaultContent(" 买牛奶 "));

        eq("todo request exact",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"todo_create\",\"arguments\":"
                        + "{\"title\":\"买牛奶\",\"content\":\"- [ ] 买牛奶\"}}}",
                AppToolProtocol.buildTodoRequest("买牛奶", "- [ ] 买牛奶"));

        String okRaw = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"{\\\"noteId\\\":\\\"n1\\\"}\"}],"
                + "\"isError\":false}}";
        AppToolProtocol.Result ok = AppToolProtocol.parseResponse(okRaw);
        ok("parse success", ok.ok);
        eq("parse text", "{\"noteId\":\"n1\"}", ok.text);

        String businessError = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                + "{\"type\":\"text\",\"text\":\"标题不能为空\"}],\"isError\":true}}";
        AppToolProtocol.Result be = AppToolProtocol.parseResponse(businessError);
        ok("business error isError", !be.ok);
        eq("business error code", "PROVIDER_ERROR", be.code);
        ok("business error keeps detail", be.text.contains("标题不能为空"));
        ok("business error has hint", be.text.contains("xiaoai_submit"));

        AppToolProtocol.Result rpc = AppToolProtocol.parseResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600,"
                        + "\"message\":\"Caller not authorized\"}}");
        ok("rpc error isError", !rpc.ok);
        ok("rpc error code in detail", rpc.text.contains("-32600"));
        ok("rpc error message in detail", rpc.text.contains("Caller not authorized"));

        AppToolProtocol.Result bad = AppToolProtocol.parseResponse("not json");
        ok("invalid response isError", !bad.ok);
        eq("invalid response code", "INVALID_RESPONSE", bad.code);

        AppToolProtocol.Result empty = AppToolProtocol.parseResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[],\"isError\":false}}");
        ok("empty content isError", !empty.ok);
        eq("empty content code", "EMPTY_RESULT", empty.code);

        AppToolProtocol.Result blank = AppToolProtocol.parseResponse(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":["
                        + "{\"type\":\"text\",\"text\":\"  \"}],\"isError\":false}}");
        ok("blank text isError", !blank.ok);
        eq("blank text code", "EMPTY_RESULT", blank.code);

        AppToolProtocol.Result nil = AppToolProtocol.parseResponse(null);
        ok("null response isError", !nil.ok);
        eq("null response code", "EMPTY_RESPONSE", nil.code);

        Object parsed = Json.read(AppToolProtocol.errorJson("CALL_TIMEOUT", "timeout", "raw"));
        Map<?, ?> err = (Map<?, ?>) parsed;
        eq("error json ok=false", Boolean.FALSE, err.get("ok"));
        eq("error json code", "CALL_TIMEOUT", err.get("error"));
        eq("error json message", "timeout", err.get("message"));
        ok("error json hint", ((String) err.get("hint")).contains("xiaoai_submit"));

        eq("json escapes title",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"todo_create\",\"arguments\":"
                        + "{\"title\":\"a\\\"b\",\"content\":\"- [ ] a\\\"b\"}}}",
                AppToolProtocol.buildTodoRequest("a\"b", "- [ ] a\"b"));

        return T.report("AppToolProtocolTest");
    }

    private static void ok(String n, boolean c) { T.ok(n, c); }
    private static void eq(String n, Object e, Object a) { T.eq(n, e, a); }
}
